"""Debug APK only: read a local credential file and send through adb stdin, not argv."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("credential_file", type=Path)
parser.add_argument("--serial", help="Device serial when more than one device is connected")
args = parser.parse_args()
credential = args.credential_file.read_text(encoding="utf-8-sig").strip()
if not 16 <= len(credential) <= 16384:
    parser.error("Invalid credential length")
adb = ["adb"] + (["-s", args.serial] if args.serial else [])
subprocess.run(adb + ["shell", "am", "force-stop", "io.gmgc.probe"], check=True, capture_output=True)
result = subprocess.run(adb + ["shell", "run-as", "io.gmgc.probe", "sh", "-c",
                              "'cat > files/diagnostic-auth.txt'"],
                        input=credential.encode(), capture_output=True)
if result.returncode:
    raise SystemExit("Injection failed. Install and launch the debug APK once; authorize adb.")
subprocess.run(adb + ["shell", "am", "start", "-n", "io.gmgc.probe/.MainActivity"],
               check=True, capture_output=True)
print("Diagnostic credential injected. This does NOT pass self-service authentication acceptance.")
