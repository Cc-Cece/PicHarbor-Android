"""Verify the full copied source set against the frozen baseline; no old repo needed."""
import hashlib
import json
import sys
from pathlib import Path
from sync_gpmc import check as check_gpmc

ROOT = Path(__file__).resolve().parents[1]


def verify():
    check_gpmc(ROOT)
    if (ROOT / 'gpmc.lock.json').read_bytes() != (ROOT / 'app/src/main/assets/gpmc.lock.json').read_bytes():
        raise ValueError('Packaged gpmc lock is stale')
    baseline_file = ROOT / "app/src/main/assets/engine-baseline.json"
    baseline = json.loads(baseline_file.read_text(encoding="utf-8"))
    source = ROOT / "app/src/main/python"
    # The historical baseline remains intact as provenance. Its gpmc entries
    # are superseded by the canonical package lock; blackboxprotobuf stays frozen.
    expected = {item["path"]: item["sha256"] for item in baseline["files"] if not item["path"].startswith('gpmc/')}
    extension_file = ROOT / "app/src/main/assets/engine-extension.json"
    extension = json.loads(extension_file.read_text(encoding="utf-8")) if extension_file.exists() else {"files": []}
    extra = {item["path"]: item["sha256"] for item in extension["files"]}
    if expected.keys() & extra.keys():
        raise ValueError("Extensions must not override frozen baseline files")
    expected.update(extra)
    actual = {}
    for package in ("blackboxprotobuf",):
        for path in (source / package).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                actual[path.relative_to(source).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name in extra:
        path = source / name
        if path.is_file():
            actual[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    differences = sorted(set(expected) ^ set(actual))
    differences += [name for name in expected.keys() & actual.keys() if expected[name] != actual[name]]
    for item in baseline.get("supporting_files", []):
        if item["path"].startswith("reference/"):
            continue
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            differences.append(item["path"])
    if "source_repository" in baseline:
        differences.append("absolute source path in packaged baseline")
    if differences:
        print("Engine baseline mismatch: " + ", ".join(differences))
        return 1
    print(f"PASS: frozen protobuf dependency and {len(extra)} platform adapter files verified; gpmc uses its canonical lock")
    return 0


if __name__ == "__main__":
    sys.exit(verify())
