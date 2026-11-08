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


def run(arguments, include_stderr=False):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Signing verification command failed; check key/password configuration or APK validity.")
    return result.stdout + (result.stderr if include_stderr else b"")


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
    output = run([executable("java"), "-Duser.language=en", "-Duser.country=US", "-jar", str(jar),
                  "verify", "--print-certs", str(path)], include_stderr=True).decode("utf-8", "replace")
    # SDK releases may label signers by number or by SDK range, and can use stderr.
    # This is public APK certificate output only; keystore DER handling stays separate.
    signers = {value.replace(":", "").lower() for value in
               re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output, re.IGNORECASE)}
    signer_ids = set(re.findall(r"Signer #(\d+)", output, re.IGNORECASE))
    if len(signers) != 1 or len(signer_ids) > 1 or not re.fullmatch(r"[0-9a-f]{64}", next(iter(signers), "")):
        raise RuntimeError("Could not identify exactly one APK certificate; public verifier output: " + output[:3000])
    return signers.pop()


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
