# Release signing identity

All release builds must use the existing `picharbor` key. The public certificate SHA-256 is pinned in `tools/release-signing.sha256`; the private key and passwords must never be committed.

GitHub Actions requires repository Actions Secrets:

- `PICHARBOR_KEYSTORE_BASE64`: Base64-encoded JKS file. Base64 is encoding, not encryption.
- `PICHARBOR_STORE_PASSWORD`: JKS store password.
- `PICHARBOR_KEY_PASSWORD`: private-key password; explicitly configured even when equal to store password.

Missing secrets stop publishing. The workflow verifies the keystore certificate before building, then uses Android apksigner to verify the complete signed APK and compare its certificate to the same pinned identity before uploading any release assets.

Local builds use `%LOCALAPPDATA%\PicHarborBuild\signing\picharbor-release.jks` and its existing DPAPI-protected password. `tools/build-release.ps1` fails if either is missing; it must not silently create a replacement key. `tools/backup-signing.ps1` can create a portable encrypted PKCS12 copy with a separately held backup password. Keep at least one copy outside this computer and keep its password separately.

This identity matches the locally retained 1.0.8 APK. Installations signed with another key, including the superseded temporary-key beta.5, do not automatically become compatible. Replacing a release download does not change the signature of an already installed APK. No claim is made that every historically published APK used this identity.
