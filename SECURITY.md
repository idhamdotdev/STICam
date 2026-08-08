# Security Policy

## Supported Versions

| Version | Security fixes |
| --- | --- |
| Latest official release | Supported |
| Older releases and development builds | Not supported |

Install the newest version from the official
[GitHub Releases](https://github.com/idhamdotdev/STICam/releases) page before reporting
a problem.

## Reporting a Vulnerability

Do not disclose suspected vulnerabilities in public issues, discussions, or social
media.

Use GitHub's private
[Report a vulnerability](https://github.com/idhamdotdev/STICam/security/advisories/new)
form, or email **hello@idham.dev**. Include:

- affected STICam version;
- Android device and OS version;
- Windows version;
- connection mode: Wi-Fi or USB;
- clear reproduction steps;
- expected and observed impact;
- logs or a minimal proof of concept when safe;
- whether the issue is already being exploited.

STICam is maintained by one independent developer. Reports are handled on a best-effort
basis — please allow time for validation and a coordinated fix before public disclosure.
You will get an acknowledgement, and credit in the release notes if you want it.

### Security research and the EULA

The STICam license restricts reverse engineering, but that restriction explicitly does
**not** apply to good-faith security research on a copy you lawfully obtained and run on
your own device, provided findings are reported through this channel and not published
before a coordinated fix. See [EULA.md](EULA.md) §3.1. You will not be pursued for
looking.

## Security Model

STICam is designed for a phone and PC controlled by the same user.

### Wi-Fi

- Video and control traffic are not authenticated.
- Traffic is not encrypted.
- The Windows host listens on TCP port `8765`.
- Use Wi-Fi mode only on a trusted private network.
- Do not use Wi-Fi mode on public, shared, guest, hotel, school, workplace, or other
  untrusted networks.

A device able to observe or interfere with the local connection may view the video
stream, interrupt it, or send camera-control commands. Reports that only restate this
documented limitation may be closed as known behavior; bypasses of future authentication
controls, or impacts beyond this model, remain valid security reports.

### USB

USB mode uses Android Debug Bridge and `adb reverse` to connect the Android app to the
Windows listener. In USB mode the host binds the listener to loopback only, so the
stream is not reachable from the network; Wi-Fi mode binds every interface.

Enabling USB debugging grants powerful access to an approved computer. Only approve
computers you trust, revoke old debugging authorizations, and disable USB debugging when
it is no longer needed.

### Virtual Camera and Bundled Tools

The Windows application registers a COM media source (`SticamVCam`) that Windows loads
into the Frame Server process, and passes frames to it through a shared-memory mapping
whose DACL grants Everyone read/write. Any local process can therefore read frames or
write them into the virtual camera while it is running; this matches the Wi-Fi model,
where the same video is already served unauthenticated.

Installation is elevated because COM registration is machine-wide. Only install official
releases and do not replace bundled executables or DLLs with files from untrusted
sources.

### Telemetry boundary

Android v1.0.0 contains MediaPipe Tasks' upstream metrics transport. According to
Google's MediaPipe privacy notice, it sends API performance and utilization metrics, not
the image/video input. Android v1.0.1 selects MediaPipe's no-op logger and removes Google
Data Transport and Firebase from the APK. See [PRIVACY.md](PRIVACY.md) for the full
version-specific disclosure.

## Verifying Official Releases

Each official release publishes a SHA-256 checksum for every downloadable asset. Verify
the checksum before installation. Full instructions:
[docs/VERIFYING-DOWNLOADS.md](docs/VERIFYING-DOWNLOADS.md).

Windows PowerShell:

```powershell
Get-FileHash .\STICam_v1.0.1.apk -Algorithm SHA256
Get-FileHash .\STICam_Installer_v1.0.1.exe -Algorithm SHA256
```

Linux or macOS:

```bash
sha256sum STICam_v1.0.1.apk STICam_Installer_v1.0.1.exe
```

The current official Android release certificate SHA-256 fingerprint is:

```text
CF:FD:EE:3D:95:EB:BD:D9:32:5A:3D:52:1B:6D:62:06:12:F7:DB:4D:6F:93:5F:3D:0E:5D:20:35:7B:37:66:10
```

Android normally rejects an update signed by a different certificate. A locally built or
debug-signed APK is not an official release.

The Windows installer is not currently Authenticode-signed. Windows may show an
unknown-publisher warning; verify the published SHA-256 checksum before running it.

## Scope Guidance

Examples of useful reports include:

- unexpected remote code execution or memory corruption;
- unauthorized access outside the documented trusted-network model;
- unsafe command or packet parsing;
- privilege escalation or insecure installer behavior;
- signing-key, update-integrity, or release-supply-chain issues;
- leakage of data beyond the intended phone-to-PC stream.

General bugs, compatibility problems, and feature requests that do not create a security
impact should be filed as normal
[GitHub issues](https://github.com/idhamdotdev/STICam/issues).
