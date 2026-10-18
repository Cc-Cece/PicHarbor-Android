"""Open confirmation for the ONE disposable item prepared by prepareDisposableCleanup.

Stops before the Android confirmation; never taps its delete button automatically.
"""
import json
import runpy
import time
from pathlib import Path

ui = runpy.run_path(str(Path(__file__).with_name('device-ui-smoke.py')))
adb, tree, tap, find = (ui[name] for name in ('adb', 'tree', 'tap', 'find'))

adb('shell', 'am', 'start', '-n', 'com.cccece.picharbor/.MainActivity')
time.sleep(1)
tap(find(tree(), '我的'))
tap(find(tree(), '释放手机空间'))
page = tree()
find(page, '1 项已核验，可由你确认清理')
release = next(n for n in page.iter('node') if n.get('text', '').startswith('释放 ') and n.get('text', '').endswith(' 空间'))
tap(release)
confirm = tree()
assert any(n.get('text', '').startswith('本次最多移除 1 个已核验的手机文件') for n in confirm.iter('node'))
tap(find(confirm, '继续并确认清理'))
dialog = tree()
print(json.dumps({'packages': sorted({n.get('package') for n in dialog.iter('node')}),
                  'labels': [n.get('text') for n in dialog.iter('node') if n.get('text')]}, ensure_ascii=True, indent=2))
