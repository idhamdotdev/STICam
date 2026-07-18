# Security Policy

## Reporting a Vulnerability

**Please do not report security issues in public GitHub issues or pull requests.**
Public disclosure gives attackers a head start before a fix is available.

Instead, report privately using GitHub's **[Private vulnerability reporting](https://github.com/idhamdotdev/STICam/security/advisories/new)**
(the *Security → Report a vulnerability* button on this repository). This keeps
the report confidential until a fix is released, and never exposes an email
address to spam.

When reporting, please include:

- What the issue is and the potential impact.
- Steps to reproduce (a proof of concept helps a lot).
- The STICam version and your device / OS.

This is a community project maintained in spare time, so responses are
best-effort — but security reports are taken seriously and prioritized.

## Supported Versions

Only the **latest release** receives security fixes. Please make sure you are
on the newest version before reporting.

## Security Model — Please Read

STICam streams video between your phone and PC over your **local network**.
By design, the connection is **not authenticated or encrypted** — it is built
for convenience on a **trusted home network**.

- ✅ Intended use: your own private Wi-Fi / a direct USB connection.
- ⚠️ Not recommended: shared, public, or untrusted networks, where another
  device on the same network could observe or interfere with the stream.

This is an intentional design trade-off, not a bug — reports that amount to
"the LAN stream is unauthenticated" are already known and documented here.
If you have ideas for optional authentication, contributions are welcome.

## Verifying Your Download

Only download STICam from the **official
[Releases](https://github.com/idhamdotdev/STICam/releases)** page. Copies from
other sites may be modified.

Each release lists the **SHA-256 checksum** of every file — verify it after
downloading:

- Windows (PowerShell): `Get-FileHash .\STICam.apk -Algorithm SHA256`
- Linux / macOS: `sha256sum STICam.apk`

The Android APK is signed with STICam's release key. Its certificate
fingerprint (SHA-256) is:

```
CF:FD:EE:3D:95:EB:BD:D9:32:5A:3D:52:1B:6D:62:06:12:F7:DB:4D:6F:93:5F:3D:0E:5D:20:35:7B:37:66:10
```

A genuine update always shares this signature; Android will refuse to install
a build signed by anyone else over a real STICam install.
