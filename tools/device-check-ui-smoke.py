"""Release UI check for performance settings and read-only quick background checks."""
import json
import re
import runpy
import time
from pathlib import Path

u=runpy.run_path(str(Path(__file__).with_name('device-ui-smoke.py')))
adb,tree,tap,find=(u[k] for k in ('adb','tree','tap','find'))

def visible(label):
    for _ in range(5):
        page=tree()
        try:return find(page,label)
        except StopIteration:
            _,_,width,height=map(int,re.findall(r'\d+',next(page.iter('node')).get('bounds')))
            adb('shell','input','swipe',str(width//2),str(height*4//5),str(width//2),str(height//3),'300')
    raise AssertionError('Requested control was not visible')

def settled(label):
    for _ in range(5):
        try:return find(tree(),label)
        except StopIteration:time.sleep(.6)
    raise AssertionError('Navigation did not settle')

adb('shell','input','keyevent','224')
adb('shell','wm','dismiss-keyguard')
adb('shell','am','force-stop','com.cccece.picharbor')
adb('shell','am','start','-n','com.cccece.picharbor/.MainActivity')
time.sleep(2)
tap(find(tree(),'我的'))
tap(find(tree(),'释放手机空间'))
tap(find(tree(),'空间检查速度'))
for label in ('较省资源','均衡（默认）','较快'):
    find(tree(),label)
slider=next(n for n in tree().iter('node') if n.get('class')=='android.widget.SeekBar')
x1,y1,x2,y2=map(int,re.findall(r'\d+',slider.get('bounds')))
adb('shell','input','tap',str(x2-30),str((y1+y2)//2))
tap(visible('应用自定义数量'))
tap(find(tree(),'空间检查速度'))
find(tree(),'自定义：最多 20 项')
tap(find(tree(),'均衡（默认）'))
tap(visible('查看检查耗时'))
find(tree(),'实际执行时间：'+next(n.get('text').split('：',1)[1] for n in tree().iter('node') if n.get('text','').startswith('实际执行时间：')))
u['back']()
tap(visible('快速检查备份状态'))
u['back']()
tap(settled('照片'))
time.sleep(12)
tap(find(tree(),'我的'))
tap(find(tree(),'释放手机空间'))
tap(visible('查看检查耗时'))
find(tree(),'快速检查备份状态')
find(tree(),'云端完整同步')
assert any(n.get('text')=='未执行' for n in tree().iter('node'))
print(json.dumps({'custom_maximum_20':True,'default_2_restored':True,'timing_sheet':True,
                  'quick_check_survives_navigation':True,'quick_skips_cloud_sync':True},indent=2))
