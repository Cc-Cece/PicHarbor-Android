"""Inspect release contents; signature and manifest also verified with Android SDK tools."""
import hashlib
import io
import json
import zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/release/app-release.apk'
baseline=json.loads((root/'docs/engine-baseline.json').read_text(encoding='utf-8-sig'))
extension=json.loads((root/'docs/engine-extension.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(apk) as archive:
    names=archive.namelist()
    assert 'lib/arm64-v8a/libpython3.13.so' in names
    assert all(name.startswith('lib/arm64-v8a/') for name in names if name.startswith('lib/'))
    assert 'assets/LICENSE.txt' in names and 'assets/NOTICE.txt' in names
    assert json.loads(archive.read('assets/engine-extension.json')) == extension, 'Stale packaged extension manifest'
    assert not any('diagnostic-auth' in name or name.startswith('assets/demo/') for name in names)
    with zipfile.ZipFile(io.BytesIO(archive.read('assets/chaquopy/app.imy'))) as python:
        contents=set(python.namelist())
        for item in baseline['files']+extension['files']:
            name=item['path']; compiled=name.removesuffix('.py')+'.pyc'
            assert name in contents or compiled in contents, f'Missing {name}'
            if name in contents:
                assert hashlib.sha256(python.read(name)).hexdigest()==item['sha256'], f'Changed {name}'
        assert not any(name.startswith(('android_probe.', 'cloud_probe.')) for name in contents)
        assert not any('diagnostic-auth' in name for name in contents)
print('PASS: release arm64 runtime, complete frozen engine + extensions, licenses; no prototype entry or demo assets')
