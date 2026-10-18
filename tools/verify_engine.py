"""Verify the full copied source set against the frozen baseline; no old repo needed."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify():
    baseline = json.loads((ROOT / "docs/engine-baseline.json").read_text(encoding="utf-8-sig"))
    source = ROOT / "app/src/main/python"
    expected = {item["path"]: item["sha256"] for item in baseline["files"]}
    extension_file=ROOT / "docs/engine-extension.json"
    extension=json.loads(extension_file.read_text(encoding="utf-8")) if extension_file.exists() else {"files":[]}
    extra={item["path"]:item["sha256"] for item in extension["files"]}
    if expected.keys() & extra.keys():
        raise ValueError("Extensions must not override frozen baseline files")
    expected.update(extra)
    actual = {}
    for package in ("gpmc", "blackboxprotobuf"):
        for path in (source / package).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                actual[path.relative_to(source).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name in extra:
        path=source/name
        if path.is_file():actual[name]=hashlib.sha256(path.read_bytes()).hexdigest()
    differences = sorted(set(expected) ^ set(actual))
    differences += [name for name in expected.keys() & actual.keys() if expected[name] != actual[name]]
    for item in baseline.get("supporting_files", []):
        path = ROOT / item["path"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            differences.append(item["path"])
    packaged = json.loads((ROOT / "docs/baseline-assets/engine-baseline.json").read_text(encoding="utf-8"))
    if packaged.get("files") != baseline["files"] or packaged.get("source_commit") != baseline["source_commit"]:
        differences.append("packaged baseline")
    if "source_repository" in packaged:
        differences.append("absolute source path in packaged baseline")
    if differences:
        print("Engine baseline mismatch: " + ", ".join(differences))
        return 1
    print(f"PASS: {len(baseline['files'])} frozen engine files unchanged; {len(extra)} declared extension files verified")
    return 0


if __name__ == "__main__":
    sys.exit(verify())
