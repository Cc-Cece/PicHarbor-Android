import unittest
from release_preflight import validate_tag


class ReleasePreflightTests(unittest.TestCase):
    def test_preview_uses_current_version(self):
        self.assertEqual(validate_tag("1.0.9-beta.5", ""), "v1.0.9-beta.5")

    def test_mismatched_or_untrusted_tag_is_rejected(self):
        for tag in ("v1.0.8", 'v1.0.9-beta.5"; command', "../main"):
            with self.assertRaises(ValueError):
                validate_tag("1.0.9-beta.5", tag)


if __name__ == "__main__":
    unittest.main()
