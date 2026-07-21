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

**OpenAI Codex (GPT-5.6)** audited this repository and rewrote its documentation.

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

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull
request.
