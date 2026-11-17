"""Build the pinned iOS-compatible Clash source as an app-private Android process.

No downloaded executable is trusted. Go verifies go.sum; the source commit is
checked before building. Outputs are generated and excluded from source control.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '31c2931a1834ce82f452d648fdd05b94aba6c8f9'
TAG = 'v1.19.32-clash.3'
URL = 'https://github.com/ProjectClash/Clash.git'

def prepare(abis):
    source = ROOT / '.runtime-cache/clash-src'
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--depth', '1', '--branch', TAG, URL, str(source)], check=True)
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != COMMIT or subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip():
        raise RuntimeError('Proxy source must be the unmodified pinned commit')
    go = shutil.which('go') or str(Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Go/bin/go.exe')
    for abi in abis:
        target = ROOT / 'app/build/generated/proxy/jniLibs' / abi / 'libharbor_proxy.so'
        provenance = target.with_suffix('.json')
        cached = False
        if target.exists() and provenance.exists():
            data = json.loads(provenance.read_text())
            if data.get('source_commit') == COMMIT and data.get('sha256') == hashlib.sha256(target.read_bytes()).hexdigest():
                cached = True
        if not cached:
            target.parent.mkdir(parents=True, exist_ok=True)
            env = dict(os.environ, GOOS='android', GOARCH={'arm64-v8a':'arm64', 'x86_64':'amd64'}[abi], CGO_ENABLED='0')
            subprocess.run([go, 'build', '-trimpath', '-buildmode=pie', '-ldflags=-s -w', '-o', str(target), '.'], cwd=source, env=env, check=True)
            provenance.write_text(json.dumps({'repository': URL, 'tag': TAG, 'source_commit': COMMIT,
                'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}, indent=2)+'\n')
        notices = ROOT / 'app/build/generated/proxy/assets/proxy-notices'
        notices.mkdir(parents=True, exist_ok=True)
        for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_LICENSES.md', 'go.mod', 'go.sum'):
            shutil.copyfile(source / name, notices / name)
        shutil.copyfile(provenance, notices / (abi+'.json'))
    print('PASS: pinned Android proxy core built:', ', '.join(abis))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abis', nargs='+', default=['arm64-v8a'], choices=['arm64-v8a'])
    prepare(parser.parse_args().abis)
