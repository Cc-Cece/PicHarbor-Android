"""Verify the pinned release identity. Never prints passwords or private key material."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def executable(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else "")) if home else None
    return str(candidate) if candidate and candidate.is_file() else shutil.which(name) or name


def run(arguments):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Signing verification command failed; check key/password configuration or APK validity.")
    return result.stdout


def verify_keystore(path):
    if not os.environ.get("PICHARBOR_STORE_PASSWORD"):
        raise RuntimeError("PICHARBOR_STORE_PASSWORD is required")
    certificate = run([executable("keytool"), "-exportcert", "-alias", "picharbor", "-keystore", str(path),
                       "-storepass:env", "PICHARBOR_STORE_PASSWORD"])
    return hashlib.sha256(certificate).hexdigest()


def verify_apk(path):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    jars = [p for p in (Path(sdk) / "build-tools").glob("*/lib/apksigner.jar") if "rc" not in p.parts[-3]]
    if not jars:
        raise RuntimeError("Android SDK apksigner.jar is missing")
    jar = max(jars, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parts[-3])))
    output = run([executable("java"), "-jar", str(jar), "verify", "--print-certs", str(path)]).decode("utf-8", "replace")
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", output)
    if len(signers) != 1:
        raise RuntimeError("Expected exactly one APK signer")
    return signers[0].lower()


def main():
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--keystore", type=Path)
    source.add_argument("--apk", type=Path)
    args = parser.parse_args()
    expected = (ROOT / "tools/release-signing.sha256").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise RuntimeError("Invalid pinned release fingerprint")
    actual = verify_keystore(args.keystore) if args.keystore else verify_apk(args.apk)
    if actual != expected:
        raise RuntimeError(f"Release signing identity mismatch: expected {expected}, got {actual}")
    print(f"PASS: release signing certificate SHA-256 {actual}")


if __name__ == "__main__":
    main()
