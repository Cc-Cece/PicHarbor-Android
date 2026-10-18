"""Only creates and reversibly trashes its own unique disposable JPEG. Never exports credentials."""
import json, sqlite3, time, uuid
from pathlib import Path
from contextlib import closing
from picharbor_engine import service
from java import jclass
report = {}
existing=sorted(service.home.glob("PicHarbor-disposable-*.jpg"),key=lambda p:p.stat().st_mtime)
fixture=existing[-1] if existing else service.home / ("PicHarbor-disposable-"+uuid.uuid4().hex+".jpg")
if not fixture.exists():
    Bitmap = jclass("android.graphics.Bitmap")
    Config = jclass("android.graphics.Bitmap$Config")
    Format = jclass("android.graphics.Bitmap$CompressFormat")
    bitmap = Bitmap.createBitmap(640, 480, Config.ARGB_8888)
    bitmap.eraseColor(-16711936)
    canvas = jclass("android.graphics.Canvas")(bitmap)
    paint = jclass("android.graphics.Paint")()
    paint.setColor(-16777216)
    paint.setTextSize(20.0)
    canvas.drawText("PicHarbor disposable " + uuid.uuid4().hex[:12], 20.0, 100.0, paint)
    out = jclass("java.io.FileOutputStream")(str(fixture))
    try: bitmap.compress(Format.JPEG, 93, out)
    finally: out.close(); bitmap.recycle()
class Handle:
    def getPath(self): return str(fixture)
    def getSize(self): return fixture.stat().st_size
    def close(self): pass
class Bridge:
    def open(self, *_): return Handle()
item={"uri":"test", "name":fixture.name,"quality":"original","album_mode":"none", "modified":int(time.time()*1000)}
key = dedup = None
trashed = False
try:
    report["stage"]="upload"
    result=service.process(item,Bridge(),None)
    report["upload_state"]=result.get("state")
    key=result.get("remote_key")
    if not key: raise RuntimeError("FIXTURE_UPLOAD_FAILED")
    def snapshot():
        service.synchronize(full=True)
        with closing(sqlite3.connect(service.db_path)) as db:
            row=db.execute("SELECT payload FROM cloud WHERE media_key=?",(key,)).fetchone()
        value=json.loads(row[0]) if row else {}
        return value
    report["stage"]="normal_snapshot"
    value=snapshot()
    report["normal"]={k:value.get(k) for k in ("trash","trash_marker_absent","trash_shape","original","status","size")}
    report["stage"]="original_access"
    report["original_access"]=service.original_access(key,fixture.stat().st_size)
    normal=service.verify_cleanup(item,Bridge(),None)
    report["normal_cleanup"]={k:normal.get(k) for k in ("eligible","reason")}
    dedup=value.get("dedup")
    if dedup:
        report["stage"]="trash"
        service.client.api.move_remote_media_to_trash([dedup]);trashed=True
        time.sleep(2)
        value=snapshot()
        report["trashed"]={"present":bool(value),"trash":value.get("trash"),"status":value.get("status")}
        verification=service.verify_cleanup(item,Bridge(),None)
        report["trashed_cleanup"]={k:verification.get(k) for k in ("eligible","reason")}
        report["stage"]="restore"
        service.client.api.restore_from_trash([dedup]);trashed=False
        time.sleep(2)
        value=snapshot()
        report["restored"]={"present":bool(value),"trash":value.get("trash"),"status":value.get("status")}
    verification=service.verify_cleanup(item,Bridge(),None)
    report["restored_cleanup"]={k:verification.get(k) for k in ("eligible","reason")}
    report["fixture_retained"]=True
except Exception as error:
    report["error_type"]=type(error).__name__
finally:
    if trashed and dedup:
        try: service.client.api.restore_from_trash([dedup]);report["restored_in_finally"]=True
        except Exception: report["fixture_restore_required"]=True
report_json=json.dumps(report)

(service.home / "cloud-probe.json").write_text(report_json)
