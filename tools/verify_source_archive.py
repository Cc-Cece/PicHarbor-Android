"""Prove the delivered source ZIP contains all offline engine build inputs."""
import argparse
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import zipfile


def verify(path):
    with tempfile.TemporaryDirectory(prefix="picharbor-source-check-") as temporary:
        destination = Path(temporary)
        with zipfile.ZipFile(path) as bundle:
            for name in bundle.namelist():
                relative = PurePosixPath(name)
                if relative.is_absolute() or ".." in relative.parts or "\\" in name:
                    raise ValueError("Unsafe source archive path")
                if relative.suffix in {".jks", ".keystore", ".p12", ".pfx", ".dpapi"} or relative.name in {"local.properties", "diagnostic-auth.txt"}:
                    raise ValueError("Private configuration in source archive")
            for required in ("gpmc.lock.json", "tools/sync_gpmc.py", "tools/verify_engine.py", "gradle/wrapper/gradle-wrapper.properties"):
                if required not in bundle.namelist():
                    raise ValueError("Incomplete source archive: " + required)
            bundle.extractall(destination)
        subprocess.run([sys.executable, "tools/verify_engine.py"], cwd=destination, check=True)
    print(f"PASS: {path.name}: source archive verifies independently of the original checkout")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archives", nargs="+", type=Path)
    for archive in parser.parse_args().archives:
        verify(archive)
