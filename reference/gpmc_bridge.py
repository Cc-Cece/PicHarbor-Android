#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PicHarbor - Google Photos Bridge (gpmc bridge)
Receives json config via file, executes gpmc operations,
emits NDJSON progress events to stderr, and writes execution summary to stdout.
"""

import sys
import os
import json
import threading
import warnings
from pathlib import Path

# Suppress urllib3 and ssl verification warnings to keep output clean
warnings.filterwarnings("ignore")
try:
    import urllib3
    urllib3.disable_warnings()
except Exception:
    pass

import mimetypes
try:
    mimetypes.add_type("image/heic", ".heic")
    mimetypes.add_type("image/heif", ".heif")
    mimetypes.add_type("image/dng", ".dng")
    mimetypes.add_type("image/webp", ".webp")
    mimetypes.add_type("video/quicktime", ".mov")
    mimetypes.add_type("video/mp4", ".mp4")
except Exception:
    pass

# Ensure standard output and error are UTF-8 encoded
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def output_result(payload):
    """Outputs structured result to stdout prefixed with __GPMC_RESULT__."""
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    print(f"__GPMC_RESULT__:{text}", file=sys.stdout, flush=True)


def output_error(error_msg, code=1):
    """Outputs structured error and terminates process."""
    output_result({"status": "error", "error": str(error_msg)})
    try:
        print(f"[gpmc error] {error_msg}", file=sys.stderr, flush=True)
    except Exception:
        pass
    sys.exit(code)


def normalize_proxy(proxy_str):
    """Normalizes proxy URL or port to full URL."""
    p = (proxy_str or "").strip()
    if not p:
        return ""
    if "://" not in p:
        if p.isdigit():
            return f"http://127.0.0.1:{p}"
        return f"http://{p}"
    return p


def clean_auth_token(token_str):
    """Cleans input token, extracting oauth_token value if pasted with key or in cookie header."""
    s = (token_str or "").strip()
    if not s:
        return ""
    # If already in GmsCore / photos.native query format
    if "androidId=" in s:
        return s

    # If user pasted raw cookie line e.g. oauth_token=xxx or multiple cookies
    if "oauth_token=" in s:
        for part in s.split(";"):
            part = part.strip()
            if part.startswith("oauth_token="):
                return part.split("=", 1)[1].strip()

    return s


def main():
    if len(sys.argv) < 2:
        output_error("Usage: gpmc_bridge.py <config.json>", code=1)

    config_path = sys.argv[1]
    if not os.path.exists(config_path):
        output_error(f"Config file not found: {config_path}", code=1)

    try:
        with open(config_path, "r", encoding="utf-8-sig") as f:
            config = json.load(f)
    except Exception as e:
        output_error(f"Failed to read config json: {e}", code=1)

    # 1. Setup gpmc import path (strictly prioritize bundled gpmc & blackboxprotobuf in runtime folder)
    script_dir = os.path.dirname(os.path.abspath(__file__))
    while script_dir in sys.path:
        sys.path.remove(script_dir)
    sys.path.insert(0, script_dir)

    custom_gpmc = config.get("gpmc_path")
    if custom_gpmc and os.path.isdir(custom_gpmc) and custom_gpmc not in sys.path:
        sys.path.append(custom_gpmc)

    try:
        from gpmc import Client
        from gpmc import utils
    except Exception as e:
        output_error(f"Failed to import gpmc module: {e}", code=2)

    action = config.get("action", "upload")
    raw_auth_data = config.get("auth_data", "").strip()
    auth_data = clean_auth_token(raw_auth_data)
    proxy = normalize_proxy(config.get("proxy", ""))
    timeout = int(config.get("timeout", 60))

    if not auth_data:
        env_auth = os.getenv("GP_AUTH_DATA", "").strip()
        if env_auth:
            auth_data = clean_auth_token(env_auth)
        else:
            output_error("auth_data is required and GP_AUTH_DATA is not set", code=3)

    # Action 1: Test connection & credentials
    if action == "test":
        try:
            client = Client(auth_data=auth_data, proxy=proxy, timeout=timeout, log_level="WARNING")
            _ = client.api.bearer_token
            try:
                email = utils.parse_email(client.auth_data)
            except Exception:
                email = "Google Account"

            exchanged = client.auth_data if "androidId=" in client.auth_data and "androidId=" not in auth_data else None
            output_result({
                "status": "ok",
                "email": email,
                "exchanged_auth_data": exchanged
            })
            sys.exit(0)
        except Exception as e:
            output_error(str(e), code=1)

    # Action 2: Upload
    elif action == "upload":
        progress_lock = threading.Lock()

        def emit_progress(event):
            try:
                with progress_lock:
                    print(json.dumps(event, ensure_ascii=False, separators=(",", ":")), file=sys.stderr, flush=True)
            except Exception:
                pass

        target = config.get("target_dir")
        if not target:
            raw_paths = config.get("target_paths") or []
            valid_media = []
            valid_exts = {
                ".jpg", ".jpeg", ".png", ".heic", ".heif", ".webp", ".dng", ".gif",
                ".tif", ".tiff", ".bmp", ".raw", ".mov", ".mp4", ".m4v", ".avi",
                ".mkv", ".3gp", ".3g2", ".hevc"
            }
            for p in raw_paths:
                ext = os.path.splitext(p)[1].lower()
                m = mimetypes.guess_type(p)[0]
                if (m and (m.startswith("image/") or m.startswith("video/"))) or ext in valid_exts:
                    valid_media.append(p)
            target = valid_media

        if not target:
            output_error("No target media files found to upload", code=4)

        album_name = config.get("album_name")
        album_id = config.get("album_id")
        threads = max(1, int(config.get("threads", 1)))
        use_quota = bool(config.get("use_quota", False))
        saver = bool(config.get("saver", False))
        skip_existing = bool(config.get("skip_existing_filenames", False))
        auto_retries = int(config.get("auto_retries", 3))
        retry_delay = float(config.get("retry_delay", 2.0))

        try:
            client = Client(auth_data=auth_data, proxy=proxy, timeout=timeout, log_level="WARNING")
            # Only files selected and verified by the desktop preparation step may be uploaded.
            client.prepared_files = {
                os.path.normcase(os.path.abspath(item["path"])): item
                for item in config.get("prepared_files", [])
            } if "prepared_files" in config else None
            if client.prepared_files is not None:
                # A mapping avoids directory expansion or a second filesystem scan by gpmc.
                target = {Path(p): None for p in target}
            results = client.upload(
                target=target,
                album_name=album_name,
                album_id=album_id,
                use_quota=use_quota,
                saver=saver,
                recursive=True,
                threads=threads,
                skip_existing_filenames=skip_existing,
                progress_callback=emit_progress,
                auto_retries=auto_retries,
                retry_delay=retry_delay,
            )
            output_result({"status": "ok", "results": results or {}})
            sys.exit(0)
        except Exception as e:
            output_error(str(e), code=1)
    else:
        output_error(f"Unknown action: {action}", code=5)


if __name__ == "__main__":
    main()
