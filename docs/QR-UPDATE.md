# STICam v1.0.3 QR update (Android code 12)

An opt-in **pre-release** of STICam's Android transmitter and Windows Host. The app
version stays 1.0.3. The existing stable v1.0.3 release and its files remain unchanged.

## Downloads

Install both files from this release to use QR setup:

- `STICam_v1.0.3_code12.apk`: signed Android application, version code 12.
- `STICam_Installer_v1.0.3_QR.exe`: Windows Host and virtual-camera installer.
- `SHA256SUMS.txt`: checksums for the two downloads above.

Requires Android 8.0+ and Windows 11 22H2+ on an x64 PC. The installer supplies the
required Microsoft .NET 10 runtime when missing. Windows 10, Linux, and Windows ARM64
are not supported by this release. This update does not add a microphone.

## What changed

- Optional offline QR setup for Wi-Fi, with manual IP entry retained.
- Scanning fills the PC address and selects Wi-Fi on Android. It does not start the
  camera stream until you tap Connect.
- Mode-specific connection instructions on Windows, without an extra QR caption.
- Local USB pipeline diagnostics for troubleshooting. Android stage logging is
  debug-only; Windows baseline logging is opt-in. No diagnostic upload service.
- Android keeps `com.sticam`, version name 1.0.3, and the official signing certificate.

## Wi-Fi QR setup

1. Put the phone and PC on the same trusted private network.
2. Start STICam Host, select Wi-Fi, then click Connect on Windows.
3. On Android, tap Scan QR and scan the code displayed by your own host.
4. Check the filled-in address, then tap Connect on Android.
5. Select STICam as the camera in your compatible Windows video application.

Manual IP entry remains available. USB setup is unchanged.

## Preview limitations and testing scope

- The host's 30-second connection timeout includes time spent scanning. If it times
  out, click Connect on Windows again.
- Changing USB/Wi-Fi while the host is waiting can leave the previous listener active.
  Restart the host when changing modes, then choose the intended mode before Connect.
- A PC with several network adapters may advertise the wrong address. Check that it
  belongs to the phone's network and use manual IP entry if necessary.
- Build, Android lint, 40 Android unit tests, and 65 Windows tests passed. Android
  signature and package validation passed. A local debug USB run on TECNO CM5 / Android
  16 and Windows 11 was measured at approximately 30 FPS in portrait. That is a single
  configuration, not a performance guarantee or a comparison with the old release.
- The exact new Windows installer has not yet had an install smoke test. Full
  regression testing of the signed release pair remains pending. Use the stable release
  if you do not want to test this preview.

## Privacy and security

QR processing happens locally. The QR contains a PC address, not an authentication
secret. It does not authenticate or encrypt the stream. Wi-Fi video and controls are
unauthenticated and unencrypted: use a trusted private network, or use USB instead.

The Windows installer is **not Authenticode-signed**. Windows SmartScreen may warn
about an unknown publisher. Verify the download checksums before running it.

## SHA-256 checksums

```text
77B95DF53D92FC079BC74A1D0191EAFAD11B97928D0AFC3ED47E2E1B29664AAF  STICam_v1.0.3_code12.apk
82DE7C9F44E460D0D0576D946E640FD46A43858D823CFC268AED87A8FAA58B49  STICam_Installer_v1.0.3_QR.exe
```

Android signing-certificate SHA-256 (unchanged):

```text
CF:FD:EE:3D:95:EB:BD:D9:32:5A:3D:52:1B:6D:62:06:12:F7:DB:4D:6F:93:5F:3D:0E:5D:20:35:7B:37:66:10
```

The installer includes the reviewed Android Platform-Tools 36.0.0 and the unmodified
Microsoft .NET Runtime 10.0.10 x64 bootstrapper. The bootstrapper SHA-256 is
`38CF0578B18F98FEBBB9FE63FC12671AFE951D12BB5F2F3EFF3F801CC0D37993`.

GitHub's automatic "Source code" archives contain only this public documentation and
media repository, not the private STICam application source. They are not installers.

STICam is proprietary freeware. See the release-tagged
[EULA](https://github.com/idhamdotdev/STICam/blob/v1.0.3-code12/EULA.md),
[Privacy Policy](https://github.com/idhamdotdev/STICam/blob/v1.0.3-code12/PRIVACY.md),
[Security Policy](https://github.com/idhamdotdev/STICam/blob/v1.0.3-code12/SECURITY.md), and
[third-party notices](https://github.com/idhamdotdev/STICam/blob/v1.0.3-code12/THIRD-PARTY-NOTICES.md).

User support: [hello@idham.dev](mailto:hello@idham.dev).
Business/collaboration: [idhamdev0001@gmail.com](mailto:idhamdev0001@gmail.com).
