import hashlib
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "reference/probe-android"))
import android_probe as probe


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        probe._home = Path(self.temp.name).resolve()
        probe._client = Mock()
        probe._client.api.find_remote_media_by_hash.return_value = None
        probe._client.upload.return_value = {"test": "remote-key"}
        self.path = probe._home / "test.jpg"
        self.path.write_bytes(b"new-test-image")
        self.item = {"id": "test", "path": str(self.path), "name": "test.jpg", "size": self.path.stat().st_size,
                     "sha256": hashlib.sha256(self.path.read_bytes()).hexdigest()}

    def tearDown(self):
        probe._client = None
        self.temp.cleanup()

    def test_upload_preserves_engine_parameters_and_never_asserts_free_quota(self):
        result = json.loads(probe.upload(json.dumps([self.item]), Mock()))
        self.assertTrue(result["ok"])
        row = result["files"][0]
        self.assertEqual("submitted", row["upload"])
        self.assertIsNone(row["server_quota_bytes"])
        self.assertEqual("unknown", row["quota"])
        kwargs = probe._client.upload.call_args.kwargs
        self.assertFalse(kwargs["use_quota"])
        self.assertFalse(kwargs["saver"])
        self.assertEqual(1, kwargs["threads"])

    def test_remote_duplicate_excluded_without_upload(self):
        probe._client.api.find_remote_media_by_hash.return_value = "existing"
        result = json.loads(probe.upload(json.dumps([self.item]), Mock()))
        self.assertEqual("duplicate_excluded", result["files"][0]["upload"])
        probe._client.upload.assert_not_called()

    def test_duplicate_detected_inside_engine_is_also_excluded(self):
        def duplicate_upload(**kwargs):
            kwargs["progress_callback"]({"phase": "skipped"})
            return {"file": "existing-key"}
        probe._client.upload.side_effect = duplicate_upload
        result = json.loads(probe.upload(json.dumps([self.item]), Mock()))
        self.assertEqual("duplicate_excluded", result["files"][0]["upload"])

    def test_changed_file_rejected(self):
        self.path.write_bytes(b"changed")
        result = json.loads(probe.upload(json.dumps([self.item]), Mock()))
        self.assertEqual("source_changed", result["files"][0]["upload"])
        probe._client.upload.assert_not_called()

    def test_paths_outside_private_workspace_rejected(self):
        self.item["path"] = str(probe._home.parent / "outside.jpg")
        result = json.loads(probe.upload(json.dumps([self.item]), Mock()))
        self.assertFalse(result["ok"])
        probe._client.upload.assert_not_called()

    def test_failures_never_export_exception_contents(self):
        secret = "VERY_SECRET_CREDENTIAL"
        result = probe.failure(RuntimeError("encrypted auth token " + secret))
        self.assertNotIn(secret, result)
        self.assertEqual("ENCRYPTED_TOKEN_UNSUPPORTED", json.loads(result)["error"])

    def test_expiry_refresh_requires_actual_expiration(self):
        class Api:
            def __init__(self, expiry):
                self.auth_response_cache = {"Expiry": str(expiry)}
            @property
            def bearer_token(self):
                if int(self.auth_response_cache["Expiry"]) <= int(time.time()):
                    self.auth_response_cache["Expiry"] = str(int(time.time()) + 3600)
                return "not-exported"
        probe._client.api = Api(int(time.time()) + 3600)
        self.assertFalse(json.loads(probe.check_token())["actual_expiry_refresh_observed"])
        probe._client.api = Api(int(time.time()) - 1)
        result = probe.check_token()
        self.assertTrue(json.loads(result)["actual_expiry_refresh_observed"])
        self.assertNotIn("not-exported", result)

    def test_partial_batch_survives_network_error(self):
        second = dict(self.item, id="second")
        probe._client.api.find_remote_media_by_hash.side_effect = [None, OSError("secret URL")]
        result = json.loads(probe.upload(json.dumps([self.item, second]), Mock()))
        self.assertFalse(result["ok"])
        self.assertEqual("submitted", result["files"][0]["upload"])
        self.assertNotIn("secret URL", json.dumps(result))

    def test_progress_callback_only_exports_allowlisted_fields(self):
        callback = Mock()
        def fake_upload(**kwargs):
            kwargs["progress_callback"]({"phase": "retrying", "error": "SECRET_TOKEN", "path": "/private/path",
                                         "bytes_completed": 10, "bytes_total": 20})
            return {"file": "key"}
        probe._client.upload.side_effect = fake_upload
        probe.upload(json.dumps([self.item]), callback)
        value = callback.onProgress.call_args.args[0]
        self.assertNotIn("SECRET_TOKEN", value)
        self.assertNotIn("/private/path", value)
        self.assertEqual("retrying", json.loads(value)["phase"])

    def test_failed_authentication_clears_previous_client(self):
        with patch("gpmc.Client", side_effect=RuntimeError("BadAuthentication SECRET")):
            result = json.loads(probe.authenticate("SECRET"))
        self.assertFalse(result["ok"])
        self.assertIsNone(probe._client)
        self.assertNotIn("SECRET", json.dumps(result))

    def test_upload_requires_authenticated_client(self):
        probe._client = None
        self.assertEqual("LOGIN_REQUIRED", json.loads(probe.upload(json.dumps([self.item]), Mock()))["error"])


if __name__ == "__main__":
    unittest.main()
