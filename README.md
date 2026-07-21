# STICam: Android Webcam System

[![GitHub Stars](https://img.shields.io/github/stars/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev/STICam/stargazers)
[![GitHub Followers](https://img.shields.io/github/followers/idhamdotdev?style=for-the-badge&color=2A7AE2)](https://github.com/idhamdotdev)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/idhamdotdev/STICam/build.yml?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/idhamdotdev/STICam/actions/workflows/build.yml)

**Your phone already has a better camera than most webcams. STICam puts it to work.**

STICam streams H.264 video from an Android phone to a Windows PC over Wi-Fi or USB, with
on-device AI face tracking that keeps you framed like a camera operator — free, open
source, and fully offline.

## Features

- **AI auto-framing** — on-device MediaPipe face tracking (468 landmarks) keeps you
  centred with a natural 1.5–2× punch-in. It *holds the shot steady* while you talk and
  glides to re-frame only when you actually move, instead of constantly hunting.
- **Manual camera control** — zoom, exposure, ISO, focus, flashlight, camera selection,
  and resolution, all driven from the Windows app.
- **Wi-Fi and USB** — USB mode configures the ADB reverse tunnel automatically.
- **Automatic reconnection** — the stream resumes on its own after an interruption.
- **Virtual camera** — appears as **STICam Camera** in applications with DirectShow
  support (experimental — see [Current Limitations](#current-limitations)).
- **Free and fully offline** — no subscription, no account, no cloud. Face tracking and
  video never leave your own devices.

> [!WARNING]
> Wi-Fi video and controls are not authenticated or encrypted. Use Wi-Fi mode only on a
> trusted private network. Prefer USB on shared or public networks.

## Installation

Download the following files from the official
[Releases](https://github.com/idhamdotdev/STICam/releases) page:

- `STICam.apk`
- `STICamHost_Installer.exe`

Install the APK on your Android phone, then run the Windows installer.

## How to Use

### Wi-Fi

1. Connect the phone and the PC to the same trusted private network.
2. On Windows, open STICam Host, select **Wi-Fi**, and click **Connect**. The host now
   listens on port `8765` and displays your PC's local IP address.
3. On Android, open STICam, select **Wi-Fi**, and enter that **PC IP address**.
4. Start the stream on the phone. The phone connects outward to the PC.

If Windows prompts for firewall access, allow STICam on private networks.

### USB

1. Enable USB debugging on the Android phone.
2. Connect the phone to the PC by USB and approve the debugging prompt.
3. Select **USB** in both applications.
4. Click **Connect** in STICam Host and start the stream on Android.

The Windows host configures the required ADB reverse tunnel automatically.

### Virtual Camera

Once the preview appears, open the Windows host menu and start the virtual camera, then
select **STICam Camera** in your video application.

## Current Limitations

- The virtual camera uses **DirectShow**. It appears in applications such as Zoom, Discord
  and OBS, but **not** in Media Foundation applications such as the built-in Windows
  Camera app.
- Enabling face tracking switches the stream to **1920×1080**.
- The local MP4 recording backend is not yet connected to a user-facing control.
- A standalone RTSP server is not currently included.
- Wi-Fi traffic is not authenticated or encrypted.

## Build Instructions

### Android Client

Open `android/` in Android Studio, or run:

```powershell
cd android
./gradlew.bat assembleDebug
```

For release signing, copy `android/keystore.properties.example` to
`android/keystore.properties` and configure your keystore. Without this file,
`assembleRelease` falls back to the debug key and must not be distributed as an official
release.

### Windows Host

Install the .NET 10 SDK, then run:

```powershell
cd windows/SticamHost
dotnet restore
dotnet build -c Release
```

The executable is generated under `windows/SticamHost/bin/Release/net10.0-windows/`.

For USB mode, place `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll` from Android
Platform Tools in `windows/SticamHost/tools/` before publishing.

To create the Windows installer:

```powershell
cd windows/SticamHost
dotnet publish -c Release -r win-x64 --self-contained -o publish
```

Then compile `windows/SticamInstaller.iss` with
[Inno Setup 6](https://jrsoftware.org/isinfo.php).

## Security

See [SECURITY.md](SECURITY.md) for the security model, supported versions, private
vulnerability reporting, release checksums, and the APK certificate fingerprint.

## License

STICam is licensed under the [GNU General Public License v3.0](LICENSE).

### Third-Party Software

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for component versions, upstream
sources, license references, and distribution notes.

- **Android Debug Bridge:** Apache License 2.0.
- **FFmpeg:** GNU GPL v3.0 for the distributed GPL-enabled build; source is available from
  [ffmpeg.org](https://ffmpeg.org/download.html).
- **Sdcb.FFmpeg:** MIT License.
- **OBS Virtual Camera:** DirectShow module from
  [OBS Studio](https://github.com/obsproject/obs-studio) 32.0.4, redistributed as part of
  this GPL-3.0 project.
- **MediaPipe runtime and Face Landmarker model:** Apache License 2.0.
- **Jetpack Compose, AndroidX, and Kotlin Coroutines:** Apache License 2.0.
- **Lalezar font:** SIL Open Font License 1.1.

## Built With OpenAI Codex

STICam existed before OpenAI Build Week. This section documents specifically what was
built and changed with **OpenAI Codex (GPT-5.6)** during the Submission Period
(13–21 July 2026), separated from earlier work.

### Security hardening branch — 13 July

Branch: [`codex/v1.1.0-hardening`](https://github.com/idhamdotdev/STICam/tree/codex/v1.1.0-hardening)
(based on the `v1.1.0` tag — **not merged into `main`**, which continued to v1.5.0 on a
separate track). 54 files changed, +4,359 / −1,183.

Codex replaced the unauthenticated plaintext connection with an authenticated, encrypted
transport: pairing-key handshake with PBKDF2-HMAC-SHA256 derivation, AES-256-GCM records
with directional keys, authenticated sequence numbers, and replay/order rejection. Keys
are stored with DPAPI on Windows and a non-exportable Android Keystore key on the phone.
It wrote deterministic test vectors for both platforms plus a negative test proving an
invalid handshake proof is rejected, and documented the design — including its explicit
lack of forward secrecy — in `docs/PROTOCOL.md`.

It also made MP4 recording actually reachable and keyframe-safe (MediaStore-aware on API
29+), added camera-generation guards against stale Camera2 callbacks, corrected MediaCodec
buffer ownership, added keyframe-aware congestion recovery, removed unsafe null assertions
and unused permissions, hardened the ADB and receiver lifecycles, and removed the automatic
COM virtual-camera registration along with its bundled OBS binary. It added the project's
first automated tests, CI for both platforms, Gradle and NuGet lock files, and Actions
pinned to reviewed commit SHAs.

I chose to ship v1.5.0 (MediaPipe face tracking, .NET 10) first, so this branch remains
unmerged and unreleased — it is published for review, not as a shipped build.

### Windows packaging and code signing — 14 July

Codex explored the Windows distribution path. It prepared the MSIX packaging route for
Microsoft Store submission (product identity, publisher fields, `.msixupload` format);
when I decided to defer Store certification, it pivoted to local code signing — producing
a locally signed development build, the development certificate, and the trust steps
needed to run it.

This build was for local testing only and was not published as a release; the published
downloads remain the v1.5.0 assets. Deferring Store submission was my decision, and Codex
implemented and verified the local signing path.

### Documentation audit and rewrite — 20 July

Codex performed a claim-by-claim comparison of the README against the committed source and
surfaced several defects that had gone unnoticed:

- **Wi-Fi setup was documented backwards** — the README told users to enter the phone's IP
  into the Windows host, but the host only listens; the phone connects outward and needs
  the *PC's* IP. Following the old instructions could prevent connection entirely.
- **Local recording was advertised but unreachable** — the recording code exists, but
  `startRecording()` has no callers, so no user could trigger it.
- **RTSP output was overstated** — FFmpeg is launched as an RTSP *publisher*, while nothing
  listens at the target address.
- **Virtual-camera status could report a false positive** — registration errors were
  silently swallowed and detection always returned a default name.
- **The trusted-network warning** from `SECURITY.md` was missing from the README's Wi-Fi
  setup instructions.

Codex then rewrote `README.md`, `AGENTS.md`, `CONTRIBUTING.md`, and `SECURITY.md`, and
authored `THIRD_PARTY_NOTICES.md`.

### Prior work

The streaming pipeline, MediaPipe face tracking and auto-framing, manual camera controls,
and the virtual-camera integration predate the Submission Period and are not part of the
work described above.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull
request.
