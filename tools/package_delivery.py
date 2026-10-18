"""Package source without machine-local data, credentials, caches or signing keys."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "dist"
output.mkdir(exist_ok=True)
apk = output / "gmgc-probe-0.1.0-debug-arm64.apk"
shutil.copy2(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
source = output / "gmgc-apk-0.1.0-source.zip"
roots = ["app/src", "gradle", "tools", "tests", "docs", "reference"]
files = [root / name for name in ["README.md", "NOTICE.md", "LICENSE", ".gitignore", "settings.gradle.kts",
         "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts",
         "app/gradle.lockfile", "app/requirements.lock"]]
files.extend(path for name in roots for path in (root / name).rglob("*") if path.is_file())
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(set(files)):
        if "__pycache__" in path.parts or path.suffix in {".pyc", ".log", ".jks", ".keystore"}:
            continue
        archive.write(path, path.relative_to(root).as_posix())
manifest = {path.name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
            for path in (apk, source)}
(output / "SHA256SUMS.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print(json.dumps(manifest, indent=2))
