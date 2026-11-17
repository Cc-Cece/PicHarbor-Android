"""Keep verification builds separate from immutable public releases."""
import argparse
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def validate_tag(version, requested):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?", version):
        raise ValueError("Invalid application release version")
    expected = "v" + version
    tag = requested or expected
    if tag != expected:
        raise ValueError(f"Release tag must match application version: {expected}")
    return tag


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", default="")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
    tag = validate_tag(version, args.tag)
    if args.publish:
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        existing = subprocess.run(["git", "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}"], cwd=ROOT, capture_output=True, text=True)
        if existing.returncode == 0 and existing.stdout.strip() != head:
            raise ValueError("Existing release tag points at another commit; bump the app version")
        repository = os.environ["GITHUB_REPOSITORY"]
        result = subprocess.run(["gh", "api", f"repos/{repository}/releases/tags/{tag}"], capture_output=True, text=True)
        if result.returncode == 0:
            raise ValueError("Release already exists; published assets are immutable. Bump the app version")
        if "HTTP 404" not in result.stderr:
            raise RuntimeError("Could not verify release absence; refusing publication")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as handle:
            handle.write(f"tag={tag}\npublish={'true' if args.publish else 'false'}\n")
    print(f"PASS: {'publish' if args.publish else 'build-only'} preflight for {tag}")


if __name__ == "__main__":
    main()
