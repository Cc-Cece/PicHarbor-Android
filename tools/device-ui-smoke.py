"""ADB smoke checks. Only opens media created by DeviceRegressionTest; never deletes media."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe')


def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True).stdout.decode('utf-8', errors='replace')


def tree():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/PicHarbor-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/data/local/tmp/PicHarbor-ui.xml'))


def find(root, label):
    return next(n for n in root.iter('node') if n.get('text') == label or n.get('content-desc') == label)


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def tap(node):
    adb('shell', 'input', 'tap', *center(node))
    time.sleep(.6)


def fixture(root):
    return next(n for n in root.iter('node') if n.get('content-desc', '').startswith('PicHarbor-disposable-queue-'))


def back():
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(.6)


def run():
    adb('shell', 'am', 'force-stop', 'com.cccece.picharbor')
    adb('shell', 'am', 'start', '-n', 'com.cccece.picharbor/.MainActivity')
    time.sleep(2)
    tap(find(tree(), '照片'))
    for _ in range(3):
        x, y = center(fixture(tree()))
        adb('shell', 'input', 'swipe', x, y, x, y, '650')
        time.sleep(.4)
        find(tree(), '备份所选')
        back()
        find(tree(), '照片')
    tap(fixture(tree()))
    detail = tree()
    find(detail, '所在文件夹')
    for _ in range(3):
        if any(n.get('text') in ('重新核对', '立即备份') for n in detail.iter('node')):
            break
        bounds = next(detail.iter('node')).get('bounds')
        _, _, width, height = map(int, re.findall(r'\d+', bounds))
        adb('shell', 'input', 'swipe', str(width // 2), str(height * 4 // 5), str(width // 2), str(height // 3), '350')
        detail = tree()
    labels = {n.get('text') for n in detail.iter('node')}
    tap(find(detail, '重新核对' if '重新核对' in labels else '立即备份'))
    time.sleep(2)
    after = tree()
    assert any(n.get('text') in ('所在文件夹', '文件大小', '类型', '释放空间') for n in after.iter('node'))
    assert not any(n.get('text') == '我的' for n in after.iter('node'))
    back()
    tap(find(tree(), '我的'))
    account = next(n for n in tree().iter('node') if '@' in n.get('text', ''))
    tap(account)
    panel = tree()
    find(panel, '添加 Google 账号')
    find(panel, '当前账号')
    back()
    tap(find(tree(), '备份'))
    tap(find(tree(), '备份设置'))
    settings = tree()
    assert not any(n.get('text') == '我的' for n in settings.iter('node'))
    back()
    find(tree(), '我的')
    adb('shell', 'rm', '/data/local/tmp/PicHarbor-ui.xml')
    return {'selection_animation_cycles': 3, 'manual_submit_stays_in_detail': True,
            'saved_account_panel': True, 'root_detail_navigation': True}


if __name__ == '__main__':
    print(json.dumps(run(), indent=2))
