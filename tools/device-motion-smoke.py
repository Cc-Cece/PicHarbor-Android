"""Read-only dark-theme route/flash QA. Recording stays in the host temp directory."""
import json
import os
import runpy
import subprocess
import tempfile
import time
from pathlib import Path

u = runpy.run_path(str(Path(__file__).with_name('device-ui-smoke.py')))
adb, tree, tap, find = (u[k] for k in ('adb', 'tree', 'tap', 'find'))
adb('shell', 'input', 'keyevent', '224')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'am', 'start', '-n', 'com.cccece.picharbor/.MainActivity')
time.sleep(1)
tap(find(tree(), '\u5907\u4efd'))
remote = '/data/local/tmp/PicHarbor-motion.mp4'
rec = subprocess.Popen([u['ADB'], 'shell', 'screenrecord', '--time-limit', '45', remote],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1)
lists = []
for title in ('\u5df2\u5907\u4efd', '\u5f85\u68c0\u67e5', '\u9700\u8981\u5904\u7406'):
    tap(find(tree(), title))
    page = tree()
    assert any(n.get('text', '').startswith('\u5171 ') and n.get('text', '').endswith(' \u9879') for n in page.iter('node'))
    lists.append(title)
    u['back']()
tap(find(tree(), '\u6211\u7684'))
tap(find(tree(), '\u91ca\u653e\u624b\u673a\u7a7a\u95f4'))
tap(find(tree(), '\u7a7a\u95f4\u68c0\u67e5\u901f\u5ea6'))
options = tree()
for label in ('\u8f83\u7701\u8d44\u6e90', '\u5747\u8861\uff08\u9ed8\u8ba4\uff09', '\u66f4\u5feb'):
    find(options, label)
u['back']()
u['back']()
tap(find(tree(), '\u5907\u4efd'))
tap(find(tree(), '\u5907\u4efd\u8bbe\u7f6e'))
u['back']()
rec.wait(timeout=50)
video = Path(tempfile.gettempdir()) / 'PicHarbor-motion.mp4'
adb('pull', remote, str(video))
adb('shell', 'rm', remote)
ffmpeg = Path('D:/sdk/ffmpeg-7.1.1-full_build/bin/ffmpeg.exe')
pixels = subprocess.run([str(ffmpeg), '-v', 'error', '-i', str(video), '-vf',
                         'fps=15,scale=64:144,format=gray', '-f', 'rawvideo', '-'],
                        check=True, capture_output=True).stdout
size = 64 * 144
frames = [pixels[i:i+size] for i in range(0, len(pixels)-size+1, size)]
white = [sum(p > 235 for p in frame) / size for frame in frames]
dark = [sum(p < 100 for p in frame) / size for frame in frames]
assert frames and max(white) < .8, 'Detected a near-full-screen white frame'
assert max(dark) > .6, 'Recording did not include the dark application'
print(json.dumps({'statistic_lists_opened': len(lists), 'cleanup_speed_choices': [1, 2, 3],
                  'frames_checked': len(frames), 'sample_fps': 15,
                  'max_near_white_pixel_fraction': round(max(white), 4),
                  'near_full_screen_white_frames': sum(v >= .8 for v in white)}, indent=2))
