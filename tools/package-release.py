"""Public release/source packaging. No secrets, local config, build caches or diagnostic injection."""
import hashlib
import json
import shutil
import os
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'dist'
out.mkdir(exist_ok=True)
metadata = json.loads((root / 'app/build/outputs/apk/release/output-metadata.json').read_text(encoding='utf-8'))
version = metadata['elements'][0]['versionName']
assert version and all(c.isdigit() or c == '.' for c in version), 'Unexpected release version'
apk = out / f'PicHarbor-{version}-arm64.apk'
shutil.copy2(root / 'app/build/outputs/apk/release/app-release.apk', apk)
archive = out / f'PicHarbor-{version}-source.zip'
allowed = {'app', 'gradle', 'tools'}
top = {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', '.gitignore', 'README.md', 'NOTICE.md', 'LICENSE'}
excluded = {'node_modules', 'build', '.gradle', '.kotlin', '.git', '.venv', '__pycache__', 'test-results'}


def source_files():
    for name in sorted(top):
        candidate = root / name
        if candidate.is_file():
            yield candidate
    for name in sorted(allowed):
        for directory, directories, files in os.walk(root / name):
            directories[:] = sorted(part for part in directories if part not in excluded)
            for filename in sorted(files):
                path = Path(directory) / filename
                if path.suffix in {'.pyc', '.jks', '.keystore', '.dpapi', '.p12', '.pfx', '.log'} or path.name in {'local.properties', 'diagnostic-auth.txt'}:
                    continue
                yield path


with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
    for path in source_files():
        bundle.write(path, path.relative_to(root).as_posix())

checks = {p.name: {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size} for p in (apk, archive)}
(out / f'PicHarbor-{version}-SHA256SUMS.json').write_text(json.dumps(checks, indent=2) + '\n', encoding='utf-8')
print(json.dumps(checks, indent=2))
