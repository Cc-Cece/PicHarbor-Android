"""Commit one two-part Live Photo with the joint (field 24) protocol.

Android always uses joint / 原始照片配对. Native motion photos stay on the
whole-file upload path and never reach this module.
Schema reference: xob0t/gotohp create_media_items.go (MIT, copyright 2024 xob0t).
Still-first field notes: g8row/PhotosBackup (MIT). This file does not include GPL code.
"""
import base64
import hashlib
import json
import os
import time

from blackboxprotobuf import decode_message
from gpmc.db_update_parser import _get_nested
from gpmc.message_types import COMMIT_UPLOAD_RESPONSE, FIND_REMOTE_MEDIA_BY_HASH_RESPONSE
from gpmc.mobile import Cancelled, error_code

RESULT_MASK = base64.b64decode("CnsKAggBEAEaAggBIgIIASoQCgIIARICCAEaAggBIgIIATICCAGCAQIIAYoBAggBmgECCAGiAQIIAaoBCggBKgIgATICCAHKAQIIAfIBBggBEgIIAfoBAggBggICCAGKAgQKAggBkgIGCAESAggBogICCAGqAgC6AgDCAgAQASpiCAESKggBEhIIARoGCAESAggBIgQIASIAKAEiBggBEgIQASoGCAESAggBMAE4ARoWCAESDAgBGgIIASICCAEoARoCCAEwASIKCAESBggBEgIIASoOCgQIATABEgYIARICCAE6AggBQgIIAUoKCAESAggBGgISAFoSEgIIARoCCAEiCAgBEgQIARACYgIIAXISEgIIARoCCAEiCAgBEgQIARACeggKAggBIgIIAYoBBAoCCAGSAQQIARABqgECCgA=")


class PairFailure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def varint(value):
    value &= (1 << 64) - 1
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def integer(field, value):
    return varint(field << 3) + varint(value)


def blob(field, value):
    return varint((field << 3) | 2) + varint(len(value)) + value


def map_quality(value):
    """job.quality strings from SettingsUi: saver, quota, otherwise original."""
    if value == "saver":
        return "saver"
    if value == "quota":
        return "quota"
    return "original"


def unix_seconds(value):
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    if number > 10_000_000_000:
        number //= 1000
    return number if number > 0 else 0


def safe_name(name):
    if not isinstance(name, str) or not name or "/" in name or "\\" in name or len(name.encode()) > 2048:
        raise PairFailure("INVALID_PAIR_NAME")
    return name


def emit(callback, phase, completed=0, total=0):
    if callback is not None and callback.isCancelled():
        raise Cancelled()
    if callback is not None:
        callback.onEvent(json.dumps({"phase": phase, "completed": int(completed), "total": int(total)}))


def digest_file(path, callback):
    sha1 = hashlib.sha1()
    sha256 = hashlib.sha256()
    size = os.path.getsize(path)
    completed = 0
    with open(path, "rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            emit(callback, "hashing", completed, size)
            sha1.update(chunk)
            sha256.update(chunk)
            completed += len(chunk)
    if completed != size:
        raise PairFailure("SOURCE_CHANGED")
    return sha1.digest(), sha1.hexdigest(), sha256.hexdigest(), completed


def check_size(handle, size):
    stated = int(handle.getSize())
    if stated >= 0 and stated != size:
        raise PairFailure("SOURCE_CHANGED")


class _UploadBody:
    def __init__(self, path, callback, total):
        self.stream = open(path, "rb")
        self.callback = callback
        self.total = total
        self.sent = 0

    def read(self, size=-1):
        if self.callback is not None and self.callback.isCancelled():
            raise Cancelled()
        data = self.stream.read(size)
        self.sent += len(data)
        if self.callback is not None:
            self.callback.onEvent(json.dumps({"phase": "uploading", "completed": self.sent, "total": self.total}))
        return data

    def __len__(self):
        return self.total

    def close(self):
        self.stream.close()


def upload_raw(api, path, digest, callback, total):
    token = api.get_upload_token(base64.b64encode(digest).decode("ascii"), total)
    headers = {
        "Accept-Encoding": "gzip",
        "Accept-Language": api.language,
        "User-Agent": api.user_agent,
        "Authorization": "Bearer " + api.bearer_token,
    }
    body = _UploadBody(path, callback, total)
    try:
        with api._new_session() as session:
            response = session.put(
                "https://photos.googleapis.com/data/upload/uploadmedia/interactive?upload_id=" + token,
                headers=headers,
                data=body,
                timeout=(15, max(int(getattr(api, "timeout", 60) or 60), 300)),
            )
    finally:
        body.close()
    response.raise_for_status()
    try:
        decoded, _ = decode_message(response.content, {"1": {"type": "int"}, "2": {"type": "bytes"}})
    except Exception as error:
        raise PairFailure("UPLOAD_RESPONSE_INVALID") from error
    if decoded.get("1") != 2 or not isinstance(decoded.get("2"), (bytes, bytearray)) or not decoded["2"]:
        raise PairFailure("UPLOAD_RESPONSE_INVALID")
    return response.content


def post(api, method, body, schema, commit=False):
    headers = {
        "Authorization": "Bearer " + api.bearer_token,
        "User-Agent": api.user_agent,
        "Content-Type": "application/x-protobuf",
        "Accept-Language": api.language,
        "Accept-Encoding": "gzip",
        "x-goog-ext-173412678-bin": "CgcIAhClARgC",
        "x-goog-ext-174067345-bin": "CgIIAg==",
    }
    with api._new_session() as session:
        response = session.post(
            "https://photosdata-pa.googleapis.com/6439526531001121323/" + method,
            data=body,
            headers=headers,
            timeout=api.timeout,
        )
    if commit and response.status_code == 400:
        raise PairFailure("PAIR_COMMIT_REJECTED")
    response.raise_for_status()
    try:
        return decode_message(response.content, schema)[0]
    except PairFailure:
        raise
    except Exception as error:
        raise PairFailure("COMMIT_UNCONFIRMED" if commit else "ENGINE_ERROR") from error


def motion_key(api, digest):
    query = blob(1, digest) + integer(5, 2)
    response = post(api, "5084965799730810217", blob(1, blob(1, query) + blob(2, b"")), FIND_REMOTE_MEDIA_BY_HASH_RESPONSE)
    key = _get_nested(response, "1", "2", "2", "1")
    return key if isinstance(key, str) and key else ""


def commit_joint(api, quality, name, created, photo_hash, video_hash, photo_receipt, video_receipt):
    """Field 24 links the video to a new still. Route is always joint."""
    policy = 1 if quality == "saver" else 3
    model = {"original": "Pixel XL", "quota": "Pixel 8", "saver": "Pixel 2"}[quality]
    blueprint = blob(1, photo_receipt) + blob(2, name.encode()) + blob(3, photo_hash)
    blueprint += blob(5, integer(1, created)) + blob(6, integer(1, created)) + integer(7, policy) + integer(10, 1)
    blueprint += blob(24, blob(1, video_receipt) + blob(2, video_hash))
    device = blob(3, model.encode()) + blob(4, b"Google") + integer(5, api.android_api_version)
    response = post(api, "16538846908252377752", blob(1, blueprint) + blob(2, device) + blob(5, RESULT_MASK), COMMIT_UPLOAD_RESPONSE, commit=True)
    key = _get_nested(response, "1", "3", "1")
    if not isinstance(key, str) or not key:
        raise PairFailure("COMMIT_UNCONFIRMED")
    return key


def call_with_retries(callback, retries, action):
    try:
        maximum = int(retries)
    except (TypeError, ValueError):
        maximum = 3
    maximum = max(0, min(3, maximum))
    for attempt in range(maximum + 1):
        try:
            return action()
        except Cancelled:
            raise
        except PairFailure:
            raise
        except Exception as error:
            code = error_code(error)
            if code not in ("NETWORK_ERROR", "RATE_LIMITED") or attempt >= maximum:
                raise
            emit(callback, "retrying", attempt + 1, maximum)
            deadline = time.monotonic() + min(30, 2 ** (attempt + 1))
            while time.monotonic() < deadline:
                if callback is not None and callback.isCancelled():
                    raise Cancelled()
                time.sleep(0.2)
    raise PairFailure("ENGINE_ERROR")


def part_info(sha1_hex, sha256_hex, size):
    return {"sha1": sha1_hex, "sha256": sha256_hex, "size": size}


def done(remote_key, origin, photo, video):
    if not isinstance(remote_key, str) or not remote_key:
        raise PairFailure("COMMIT_UNCONFIRMED")
    return {
        "remote_key": remote_key,
        "origin": origin,
        "state": "done",
        "sha1": photo["sha1"],
        "sha256": photo["sha256"],
        "size": photo["size"],
        "photo": photo,
        "video": video,
    }


def run_pair(service, values, bridge, callback):
    if bridge is None:
        raise PairFailure("FILE_ACCESS")
    photo = values.get("photo")
    video = values.get("video")
    if not isinstance(photo, dict) or not isinstance(video, dict):
        raise PairFailure("INVALID_PAIR")
    if not isinstance(photo.get("uri"), str) or not isinstance(video.get("uri"), str):
        raise PairFailure("FILE_ACCESS")
    quality = map_quality(values.get("quality"))
    photo_name = safe_name(photo.get("name") if isinstance(photo.get("name"), str) and photo.get("name") else "photo.jpg")
    video_name = safe_name(video.get("name") if isinstance(video.get("name"), str) and video.get("name") else "video.mp4")
    created = unix_seconds(photo.get("created") if photo.get("created") else photo.get("modified"))
    api = service.client.api
    photo_handle = None
    video_handle = None
    try:
        photo_handle = bridge.open(photo["uri"], photo_name)
        photo_path = str(photo_handle.getPath())
        photo_sha1, photo_sha1_hex, photo_sha256, photo_size = digest_file(photo_path, callback)
        check_size(photo_handle, photo_size)
        video_handle = bridge.open(video["uri"], video_name)
        video_path = str(video_handle.getPath())
        video_sha1, video_sha1_hex, video_sha256, video_size = digest_file(video_path, callback)
        check_size(video_handle, video_size)
        emit(callback, "checking", 0, photo_size + video_size)
        photo_info = part_info(photo_sha1_hex, photo_sha256, photo_size)
        video_info = part_info(video_sha1_hex, video_sha256, video_size)

        def lookup():
            still = api.find_remote_media_by_hash(photo_sha1) or ""
            movie = motion_key(api, video_sha1)
            return still if isinstance(still, str) else "", movie

        still_key, movie_key = call_with_retries(callback, values.get("retries", 3), lookup)
        if still_key and still_key == movie_key:
            return done(still_key, "content_exists", photo_info, video_info)

        def upload_joint():
            emit(callback, "uploading", 0, video_size)
            video_receipt = upload_raw(api, video_path, video_sha1, callback, video_size)
            emit(callback, "uploading", 0, photo_size)
            photo_receipt = upload_raw(api, photo_path, photo_sha1, callback, photo_size)
            emit(callback, "finalizing", photo_size + video_size, photo_size + video_size)
            return commit_joint(api, quality, photo_name, created, photo_sha1, video_sha1, photo_receipt, video_receipt)

        key = call_with_retries(callback, values.get("retries", 3), upload_joint)
        return done(key, "live_pair", photo_info, video_info)
    finally:
        for handle in (video_handle, photo_handle):
            if handle is not None:
                try:
                    handle.close()
                except Exception:
                    pass


def pair_live(service, payload, bridge=None, callback=None):
    try:
        if service is None:
            return json.dumps({"ok": False, "error": "LOGIN_REQUIRED"})
        with service.operation(False):
            if service.client is None or getattr(service.client, "api", None) is None:
                return json.dumps({"ok": False, "error": "LOGIN_REQUIRED"})
            values = json.loads(payload) if isinstance(payload, str) else payload
            if not isinstance(values, dict):
                return json.dumps({"ok": False, "error": "INVALID_PAIR"})
            account = values.get("_account")
            if not isinstance(account, str) or account != service.account:
                return json.dumps({"ok": False, "error": "ACCOUNT_CHANGED"})
            result = run_pair(service, values, bridge, callback)
            return json.dumps({"ok": True, **result})
    except PairFailure as error:
        return json.dumps({"ok": False, "error": error.code})
    except Exception as error:
        return json.dumps({"ok": False, "error": error_code(error)})
