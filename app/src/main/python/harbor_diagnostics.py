"""Android diagnostics adapter. Never serializes request bodies, URLs, headers or exception text."""
from contextlib import contextmanager
from contextvars import ContextVar
import json
import time
import uuid
import requests

_sink = None
_locks = {}
_progress_state = ContextVar("harbor_progress_state", default=None)
_context = ContextVar("harbor_diagnostic_context", default=None)


def initialize(sink):
    global _sink
    _sink = sink


def emit(name, attrs=None):
    if _sink is None:
        return
    try:
        _sink.record(name, json.dumps(_context.get() or {}), json.dumps(attrs or {}))
    except Exception:
        # Diagnostics must not change upload/commit behavior.
        pass


@contextmanager
def context(value):
    token = _context.set(value)
    try:
        yield
    finally:
        _context.reset(token)


def safe_error(error):
    # Do not include str(error), request/response objects or locals.
    import traceback
    chain = []
    seen = set()
    cause = error
    while cause is not None and len(chain) < 8 and id(cause) not in seen:
        seen.add(id(cause))
        chain.append(type(cause).__name__)
        cause = cause.__cause__ or cause.__context__
    frames = [f"{frame.name}:{frame.lineno}" for frame in traceback.extract_tb(error.__traceback__)[-24:]]
    category = "unknown"
    if isinstance(error, requests.exceptions.SSLError): category = "tls"
    elif isinstance(error, requests.exceptions.ProxyError): category = "proxy"
    elif isinstance(error, requests.exceptions.ConnectTimeout): category = "connect"
    elif isinstance(error, requests.exceptions.ReadTimeout): category = "receive"
    elif isinstance(error, requests.exceptions.ConnectionError): category = "transport_unknown"
    elif isinstance(error, OSError): category = "source_or_storage"
    return {"error_type": type(error).__name__, "cause_types": chain, "stack": frames,
            "category": category, "errno": getattr(error, "errno", None)}


@contextmanager
def operation(name, event_family="operation", **attrs):
    previous = _context.get() or {}
    current = dict(previous, parent_span_id=previous.get("span_id", ""), span_id=uuid.uuid4().hex[:16])
    start = time.monotonic()
    with context(current):
        emit(event_family + ".started", {"operation": name, **attrs})
        try:
            yield
        except BaseException as error:
            emit(event_family + ".finished", {"operation": name, "outcome": "error", "duration_ms": int((time.monotonic()-start)*1000), **safe_error(error), **attrs})
            raise
        else:
            emit(event_family + ".finished", {"operation": name, "outcome": "success", "duration_ms": int((time.monotonic()-start)*1000), **attrs})


@contextmanager
def observed_lock(lock, name):
    # A bounded owner map uses random lock IDs, never raw memory addresses.
    record = _locks.get(id(lock))
    if record is None and len(_locks) < 256:
        record = {"lock": lock, "reference": uuid.uuid4().hex, "owner": "", "depth": []}
        _locks[id(lock)] = record
    prior = dict(_context.get() or {})
    owner = record["owner"] if record else "unknown"
    with operation(name, "wait", kind=name, owner=owner):
        lock.acquire()
    if record:
        record["depth"].append(record["owner"])
        record["owner"] = prior.get("span_id", "unknown")
    try:
        yield
    finally:
        if record:
            record["owner"] = record["depth"].pop()
        lock.release()


def endpoint(url, method):
    # Only registered host/path combinations. Query strings never leave the transport.
    from urllib.parse import urlsplit
    parsed = urlsplit(url)
    if parsed.hostname == "android.googleapis.com" and parsed.path == "/auth":
        return "google_auth"
    if parsed.hostname == "photos.googleapis.com" and parsed.path == "/data/upload/uploadmedia/interactive":
        return "photos_upload_body" if method.upper() == "PUT" else "photos_upload_token"
    if parsed.hostname == "photosdata-pa.googleapis.com":
        return "photos_rpc"
    return "unknown"


class ObservedSession(requests.Session):
    def request(self, method, url, **kwargs):
        limit = kwargs.get("timeout")
        connect, read = limit if isinstance(limit, tuple) else (limit, limit)
        attrs = {"endpoint": endpoint(url, method), "method": method.upper(),
                 "timeout_connect_ms": int(connect*1000) if connect is not None else None,
                 "timeout_read_ms": int(read*1000) if read is not None else None,
                 "socket_send_progress": "unavailable"}
        prior = _context.get() or {}
        child = dict(prior, parent_span_id=prior.get("span_id", ""), span_id=uuid.uuid4().hex[:16])
        start = time.monotonic()
        with context(child):
            emit("http.started", attrs)
            try:
                response = super().request(method, url, **kwargs)
            except BaseException as error:
                emit("http.finished", {**attrs, **safe_error(error), "outcome": "unknown",
                                       "duration_ms": int((time.monotonic()-start)*1000)})
                raise
            emit("http.finished", {**attrs, "status": response.status_code,
                                   "outcome": "response_received", "duration_ms": int((time.monotonic()-start)*1000)})
            return response


def progress(phase, completed, total):
    if phase not in ("uploading", "hashing"):
        return
    now = time.monotonic()
    state = _progress_state.get()
    if state is None:
        state = {}; _progress_state.set(state)
    previous = state.get("last")
    if previous and previous[0] == phase and previous[1] == completed:
        return
    if previous and previous[0] == phase and now-previous[2] < 1 and completed != total:
        return
    state["last"] = (phase, completed, now)
    if phase in ("uploading", "hashing"):
        emit("transfer.progress", {"phase": phase, "source_read_bytes": completed,
                                  "expected_bytes": total, "transport_submitted_bytes": None,
                                  "remote_confirmed_bytes": None, "measurement": "source_reader"})


def install(service):
    """Wrap only the mobile integration; the frozen protocol implementation stays intact."""
    from gpmc.mobile import MobileApi

    def session(api):
        result = ObservedSession()
        result.verify = True
        if api.proxy:
            result.proxies.update({"https": api.proxy, "http": api.proxy})
        return result

    MobileApi._new_session = session
    for name, stage in (("hashes", "hash"), ("synchronize", "cloud_sync"),
                        ("verify_cleanup", "verify"), ("connect", "connect"),
                        ("reconcile", "reconcile"), ("albums", "album")):
        original_method = getattr(service, name)

        def wrapped(*args, _method=original_method, _stage=stage, **kwargs):
            with operation(_stage):
                return _method(*args, **kwargs)
        setattr(service, name, wrapped)


def install_api(api):
    if getattr(api, "_diagnostics_installed", False):
        return
    api._diagnostics_installed = True
    for name, stage in (("find_remote_media_by_hash", "cloud_lookup"), ("get_upload_token", "upload_token"),
                        ("upload_file", "upload_body"), ("commit_upload", "commit"),
                        ("create_album", "album"), ("add_media_to_album", "album")):
        method = getattr(api, name, None)
        if method is None:
            continue
        def wrapped(*args, _method=method, _stage=stage, **kwargs):
            with operation(_stage):
                return _method(*args, **kwargs)
        setattr(api, name, wrapped)
