"""Check signed APK contents independently of Gradle's success exit code."""
import io
import json
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/build/outputs/apk/debug/app-debug.apk"
baseline = json.loads((root / "docs/engine-baseline.json").read_text(encoding="utf-8-sig"))
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    assert "lib/arm64-v8a/libpython3.13.so" in names, "Python runtime missing"
    assert not any(n.startswith("lib/") and not n.startswith("lib/arm64-v8a/") for n in names), "Unexpected ABI"
    assert "assets/engine-baseline.json" in names, "Baseline missing"
    assert "source_repository" not in json.loads(archive.read("assets/engine-baseline.json")), "Absolute provenance path leaked"
    with zipfile.ZipFile(io.BytesIO(archive.read("assets/chaquopy/app.imy"))) as python_archive:
        python_names = set(python_archive.namelist())
        for item in baseline["files"]:
            name = item["path"]
            assert name in python_names or name.removesuffix(".py") + ".pyc" in python_names, f"Missing engine file: {name}"
        assert "android_probe.py" in python_names or "android_probe.pyc" in python_names
        assert not any("diagnostic-auth" in n for n in python_names)
print("PASS: arm64 runtime, adapter, all 31 engine files and sanitized baseline packaged")
