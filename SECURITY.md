# Security Policy

## Supported Versions

| Version | Security fixes |
| --- | --- |
| Latest GitHub release | Supported |
| Older releases and development builds | Not supported |

Install the newest version from the official [GitHub Releases](https://github.com/idhamdotdev/STICam/releases) page before reporting a problem.

## Reporting a Vulnerability

Do not disclose suspected vulnerabilities in public issues, pull requests, discussions, or social media.

Use GitHub's private [Report a vulnerability](https://github.com/idhamdotdev/STICam/security/advisories/new) form. Include:

- affected STICam version;
- Android device and OS version;
- Windows version;
- connection mode: Wi-Fi or USB;
- clear reproduction steps;
- expected and observed impact;
- logs or a minimal proof of concept when safe;
- whether the issue is already being exploited.

Reports are handled on a best-effort basis by a community maintainer. Please allow time for validation and a coordinated fix before public disclosure.

## Security Model

STICam is designed for a phone and PC controlled by the same user.

### Wi-Fi

- Video and control traffic are not authenticated.
- Traffic is not encrypted.
- The Windows host listens on TCP port `8765`.
- Use Wi-Fi mode only on a trusted private network.
- Do not use Wi-Fi mode on public, shared, guest, hotel, school, workplace, or other untrusted networks.

A device able to observe or interfere with the local connection may view the video stream, interrupt it, or send camera-control commands. Reports that only restate this documented limitation may be closed as known behavior; bypasses of future authentication controls or impacts beyond this model remain valid security reports.

### USB

USB mode uses Android Debug Bridge and `adb reverse` to connect the Android app to the Windows listener. Enabling USB debugging grants powerful access to an approved computer. Only approve computers you trust, revoke old debugging authorizations, and disable USB debugging when it is no longer needed.

### Virtual Camera and Bundled Tools

The Windows application may interact with DirectShow registration, shared memory, ADB, FFmpeg, and other native components. Only install official releases and do not replace bundled executables or DLLs with files from untrusted sources.

## Verifying Official Releases

Each official release should publish a SHA-256 checksum for every downloadable asset. Verify the checksum before installation.

Windows PowerShell:

```powershell
Get-FileHash .\STICam.apk -Algorithm SHA256
Get-FileHash .\STICamHost_Installer.exe -Algorithm SHA256
```

Linux or macOS:

```bash
sha256sum STICam.apk STICamHost_Installer.exe
```

The current official Android release certificate SHA-256 fingerprint is:

```text
CF:FD:EE:3D:95:EB:BD:D9:32:5A:3D:52:1B:6D:62:06:12:F7:DB:4D:6F:93:5F:3D:0E:5D:20:35:7B:37:66:10
```

Android normally rejects an update signed by a different certificate. A locally built or debug-signed APK is not an official release.

## Release-Maintenance Requirements

For every release:

- build the APK with the protected release key;
- verify the signing certificate before publishing;
- publish SHA-256 checksums for all assets;
- update this fingerprint if an intentional signing-key rotation occurs;
- explain any key rotation prominently because users may need to uninstall the previous APK;
- review `THIRD_PARTY_NOTICES.md` when dependencies or bundled binaries change;
- never publish the Gradle debug-signing fallback as an official release.

## Scope Guidance

Examples of useful reports include:

- unexpected remote code execution or memory corruption;
- unauthorized access outside the documented trusted-network model;
- unsafe command or packet parsing;
- privilege escalation or insecure installer behavior;
- signing-key, update-integrity, or release-supply-chain issues;
- leakage of data beyond the intended phone-to-PC stream.

General bugs, compatibility problems, and feature requests that do not create a security impact should be filed as normal GitHub issues.
