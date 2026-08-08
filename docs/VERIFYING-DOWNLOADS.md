# Verifying Your STICam Download

STICam's source code is private, so you cannot compile it yourself to check what you are
installing. That makes verifying the binary more important, not less. This page explains
exactly how.

Everything here takes about a minute and needs no extra software.

## 1. Download only from official sources

| Source | URL |
| --- | --- |
| GitHub Releases | <https://github.com/idhamdotdev/STICam/releases> |
| Website | <https://idham.dev> |

STICam is not currently published on Google Play. Unless this page is updated with an
official listing, a Play Store app using the STICam name is not an official release.

Anything from a download portal, an APK mirror, a file-sharing link, or a "modded"
build is not an official release. It is not supported and may have been altered.

## 2. Check the SHA-256 checksum

Every release publishes a SHA-256 checksum for every downloadable file, in the release
notes. Compare the checksum of your file against the published one. If they differ by a
single character, delete the file.

**Windows PowerShell:**

```powershell
Get-FileHash .\STICam_v1.0.1.apk -Algorithm SHA256
Get-FileHash .\STICam_Installer_v1.0.1.exe -Algorithm SHA256
```

**Linux or macOS:**

```bash
sha256sum STICam_v1.0.1.apk STICam_Installer_v1.0.1.exe
```

## 3. Check the Android signing certificate

Every official STICam APK is signed with the same private key. The certificate's SHA-256
fingerprint is published in [SECURITY.md](../SECURITY.md) and is the strongest single
proof that an APK came from the Developer — a checksum proves the file is intact, but the
signature proves who built it.

Android enforces this for you: it refuses to install an update signed by a different
certificate. If an "update" asks you to uninstall STICam first, that is not an official
update. Stop.

To check it yourself with the Android SDK build tools:

```bash
apksigner verify --print-certs STICam_v1.0.1.apk
```

A debug-signed or locally built APK is not an official release.

## 4. Verify the Windows installer

The v1.0.1 installer is not code-signed, so Windows may show an unrecognized-publisher
warning and no **Digital Signatures** tab will be present. Confirm the SHA-256 checksum
from the release notes before running it.

The installer also runs the Microsoft .NET Runtime installer when your PC lacks .NET 10.
That file is redistributed unmodified from Microsoft and is Authenticode-signed
`CN=.NET, O=Microsoft Corporation`. Its version and SHA-256 are recorded in the release
notes.

## 5. Scan it, if you want to

Uploading an installer to [VirusTotal](https://www.virustotal.com) is reasonable and the
Developer has no objection to it. Two things worth knowing before you read the result:

- **A handful of engines may flag STICam.** It installs a system-wide COM component (the
  virtual camera), bundles `adb.exe`, and opens a network listener. That combination
  can trigger heuristic detections in some scanners. Review the named detections and
  verify the file's SHA-256; do not rely on the detection count alone.
- **Unsigned installers trigger SmartScreen.** Windows may warn about an unrecognized
  publisher until the release has built enough download reputation.

## What "no source code" does and does not mean

You cannot read STICam's source. You *can* independently confirm:

- **What it is made of** — every third-party library, model, font, and binary is listed
  with its version and license in [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md).
- **Who built it** — the signing certificate fingerprint is fixed and published.
- **That your copy is unaltered** — via the published SHA-256.
- **What it does on the network** — the security model, including its limitations, is
  documented in [SECURITY.md](../SECURITY.md). STICam's own stream goes to your PC on
  port `8765`. Android v1.0.0 also contains MediaPipe's upstream metrics transport;
  v1.0.1 removes it. You can confirm the distinction with Wireshark or a firewall monitor.
- **Its privacy boundary** — see the version-specific disclosure in
  [PRIVACY.md](../PRIVACY.md). Every feature works with internet access blocked; v1.0.1
  additionally removes Google Data Transport and Firebase from the APK.

## Something looks wrong

If a checksum does not match, a signature is unexpected, or a scan result looks
alarming, report it before installing:

**hello@idham.dev**

For suspected vulnerabilities, use the private reporting channel in
[SECURITY.md](../SECURITY.md) instead of a public issue.
