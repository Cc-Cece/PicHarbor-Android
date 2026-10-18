"""Android platform adapter. gpmc is imported unchanged on Python's main thread.

Only allowlisted, token-free results cross into UI/reporting. Credentials are
returned separately to the encrypted Android store, never in public results.
"""
import hashlib
import json
import os
import shutil
import time
from pathlib import Path

_client = None
_home = None


def initialize(home):
    global _home
    _home = Path(home).resolve()
    _home.mkdir(parents=True, exist_ok=True)
    os.environ["HOME"] = str(_home)
    os.chdir(_home)
    from gpmc import Client  # signal.signal must run on Python's main thread
    return json.dumps({"ok": True, "engine": "gpmc", "source_unchanged": True})


def failure(exc):
    # Inspect known markers, but never expose raw exception/HTTP bodies or URLs.
    value = str(exc)
    markers = {
        "TokenEncrypted": "ENCRYPTED_TOKEN_UNSUPPORTED",
        "encrypted auth token": "ENCRYPTED_TOKEN_UNSUPPORTED",
        "MissingDroidguard": "DEVICE_VERIFICATION_REQUIRED",
        "device verification": "DEVICE_VERIFICATION_REQUIRED",
        "NeedsBrowser": "LOGIN_REQUIRED",
        "fresh browser sign-in": "LOGIN_REQUIRED",
        "requires browser sign-in": "LOGIN_REQUIRED",
        "BadAuthentication": "CREDENTIAL_REJECTED",
        "rejected the credential": "CREDENTIAL_REJECTED",
    }
    code = next((code for marker, code in markers.items() if marker in value), "ENGINE_ERROR")
    if isinstance(exc, (OSError, TimeoutError)):
        code = "NETWORK_OR_IO_ERROR"
    return json.dumps({"ok": False, "error": code, "exception_type": type(exc).__name__})


def authenticate(credential):
    global _client
    _client = None
    try:
        from gpmc import Client, utils
        candidate = Client(auth_data=credential, timeout=60, log_level="CRITICAL")
        _ = candidate.api.bearer_token
        _client = candidate
        return json.dumps({"ok": True, "credential": candidate.auth_data,
                           "account": utils.parse_email(candidate.auth_data),
                           "expiry": int(candidate.api.auth_response_cache["Expiry"]),
                           "checked_at": int(time.time())})
    except Exception as exc:
        return failure(exc)


def check_token():
    try:
        if _client is None:
            return json.dumps({"ok": False, "error": "LOGIN_REQUIRED"})
        before = int(_client.api.auth_response_cache.get("Expiry", "0"))
        checked_at = int(time.time())
        _ = _client.api.bearer_token
        after = int(_client.api.auth_response_cache["Expiry"])
        return json.dumps({"ok": True, "previous_expiry": before, "expiry": after,
                           "checked_at": checked_at,
                           "actual_expiry_refresh_observed": 0 < before <= checked_at < after})
    except Exception as exc:
        return failure(exc)


def upload(files_json, callback):
    try:
        if _client is None:
            return json.dumps({"ok": False, "error": "LOGIN_REQUIRED"})
        files = json.loads(files_json)
        rows = []
        for item in files:
            path = Path(item["path"]).resolve()
            # Kotlin supplies prepared private files; do not accept arbitrary paths.
            if _home not in path.parents:
                raise ValueError("File outside private workspace")
            row = {"id": item["id"], "sha256": item["sha256"], "size": item["size"],
                   "format": path.suffix.lower(),
                   "upload": "pending", "original_quality": "unknown", "quota": "unknown",
                   "roundtrip": "unknown", "server_quota_bytes": None}
            rows.append(row)
            digest = hashlib.sha1()
            verify = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
                    verify.update(chunk)
            if verify.hexdigest() != item["sha256"] or path.stat().st_size != item["size"]:
                row["upload"] = "source_changed"
                continue
            if _client.api.find_remote_media_by_hash(digest.digest()) is not None:
                row["upload"] = "duplicate_excluded"
                continue

            was_skipped = False

            def progress(event):
                nonlocal was_skipped
                if event.get("phase") == "skipped":
                    was_skipped = True
                safe = {k: event[k] for k in ("phase", "bytes_completed", "bytes_total",
                                               "retry_attempt", "retry_max") if k in event}
                safe["id"] = item["id"]
                callback.onProgress(json.dumps(safe))

            result = _client.upload(target={path: {"filename": item["name"]}},
                                    use_quota=False, saver=False, threads=1,
                                    show_progress=False, progress_callback=progress)
            row["upload"] = "duplicate_excluded" if was_skipped else ("submitted" if result else "failed")
            # No quota claim from gpmc's optimistically populated local cache.
        return json.dumps({"ok": True, "files": rows, "parameters": {
            "use_quota": False, "saver": False, "threads": 1},
            "quota_evidence": "official_details_required", "checked_at": int(time.time())})
    except Exception as exc:
        result = json.loads(failure(exc))
        result["files"] = locals().get("rows", [])
        return json.dumps(result)


def logout():
    global _client
    _client = None
    if _home:
        for name in (".gpmc", "media"):
            target = _home / name
            if target.is_dir():
                shutil.rmtree(target)
        log = _home / "failed_skipped_files.log"
        if log.exists():
            log.unlink()
    return json.dumps({"ok": True})
