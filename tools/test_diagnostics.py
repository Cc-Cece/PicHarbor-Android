"""Transport instrumentation safety checks; no network or Google account required."""
import json
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import harbor_diagnostics as diag


class Sink:
    def __init__(self): self.events = []
    def record(self, name, context, attrs): self.events.append((name, json.loads(context), json.loads(attrs)))


class DiagnosticTransportTest(unittest.TestCase):
    def setUp(self):
        self.sink = Sink()
        diag.initialize(self.sink)

    def test_request_instrumentation_does_not_capture_payload_or_credentials(self):
        response = type("Response", (), {"status_code": 200})()
        with patch.object(diag.requests.Session, "request", return_value=response) as original:
            with diag.context({"trace_id": "1"*32, "span_id": "2"*16}):
                actual = diag.ObservedSession().put("https://photos.googleapis.com/data/upload/uploadmedia/interactive?upload_id=secret-sentinel", headers={"Authorization": "Bearer secret-sentinel"}, data=b"photo-sentinel", timeout=60)
        self.assertIs(actual, response)
        self.assertEqual(original.call_count, 1)
        encoded = json.dumps(self.sink.events)
        self.assertNotIn("secret-sentinel", encoded)
        self.assertNotIn("photo-sentinel", encoded)
        self.assertEqual([e[0] for e in self.sink.events], ["http.started", "http.finished"])
        self.assertEqual(self.sink.events[0][1]["span_id"], self.sink.events[1][1]["span_id"])

    def test_timeout_preserves_exception_and_unknown_remote_outcome(self):
        error = diag.requests.exceptions.ReadTimeout("token=secret-sentinel")
        with patch.object(diag.requests.Session, "request", side_effect=error):
            with self.assertRaises(diag.requests.exceptions.ReadTimeout) as captured:
                diag.ObservedSession().post("https://photosdata-pa.googleapis.com/rpc", timeout=60)
        self.assertIs(captured.exception, error)
        self.assertEqual(self.sink.events[-1][2]["outcome"], "unknown")
        self.assertEqual(self.sink.events[-1][2]["category"], "receive")
        self.assertNotIn("secret-sentinel", json.dumps(self.sink.events))

    def test_observed_reentrant_lock_unwinds_after_error(self):
        lock = threading.RLock()
        with self.assertRaises(ValueError):
            with diag.observed_lock(lock, "content_lock"):
                with diag.observed_lock(lock, "content_lock"):
                    raise ValueError("secret-sentinel")
        self.assertTrue(lock.acquire(blocking=False))
        lock.release()
        self.assertEqual(diag._locks[id(lock)]["owner"], "")
        self.assertNotIn("secret-sentinel", json.dumps(self.sink.events))

    def test_broken_sink_does_not_change_business_operation(self):
        class BrokenSink:
            def record(self, *args): raise OSError("disk full")
        diag.initialize(BrokenSink())
        with diag.operation("commit"):
            result = 42
        self.assertEqual(result, 42)


if __name__ == "__main__": unittest.main()
