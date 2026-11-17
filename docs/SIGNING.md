# PicHarbor Mobile signing identity

New application ID: `com.cccece.picharbor.mobile`. The old application, its data and signing identity remain unchanged. Legacy instructions: [legacy/SIGNING-before-mobile.md](legacy/SIGNING-before-mobile.md).

- Keystore: `%LOCALAPPDATA%\PicHarborMobileBuild\signing\picharbor-release.jks`
- Alias: `picharbor`
- Password: `password.dpapi` in the same protected directory.
- Public certificate SHA-256: `65de9c795940ee1a05dade807814a8e70ca395f6a1ab1e0075faab7cea2133f7`
- Public pin: `tools/release-signing.sha256`.

`tools/initialize-mobile-signing.ps1` creates the identity only when both key and password are absent. Once initialized it verifies the retained identity, refusing incomplete or mismatched files. Never reset the pin or regenerate a replacement key for this app.

Build using `powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-release.ps1 -Verify`. Missing identity files stop release builds. The keystore and resulting APK certificate are both verified.

CI uses separate secrets: `PICHARBOR_MOBILE_KEYSTORE_BASE64`, `PICHARBOR_MOBILE_STORE_PASSWORD`, `PICHARBOR_MOBILE_KEY_PASSWORD`. Do not overwrite legacy secrets. Process-scoped build environment names remain `PICHARBOR_KEYSTORE`, `PICHARBOR_STORE_PASSWORD`, `PICHARBOR_KEY_PASSWORD`; scripts clear them after use.

The private directory is restricted to the current Windows user and LocalSystem. `tools/backup-signing.ps1 -Destination <new-file.p12>` creates an encrypted portable backup with a separate password. Keep an off-device copy and hold its password separately. DPAPI alone cannot be recovered under another Windows identity. No off-device destination has been supplied; external archival remains an operator task.