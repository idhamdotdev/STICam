# STICam — Professional Android Webcam System

[![GitHub Stars](https://img.shields.io/github/stars/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev/STICam/stargazers)
[![GitHub Followers](https://img.shields.io/github/followers/idhamdotdev?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2)](LICENSE)

Transform your Android device into a professional-grade PC webcam with full manual
Camera2 hardware control — ISO, shutter, focus, and white balance — over Wi-Fi or USB.

<!-- 
### 📸 Screenshots
![STICam App Screenshot](sticam_media/design_after_connected_mobile.png) 
-->

---

## 🌟 Support & Attribution

This project is 100% free and open-source. If you wish to fork, modify, or recreate this project:
1. **Give Credit**: You must retain attribution to the original creator, **[@idhamdotdev](https://github.com/idhamdotdev)**, and link back to this repository.
2. **Share-Alike**: Since this project is licensed under the **GPL-2.0 License**, any derivative projects you publish must also remain open-source and free under the same license.
3. **Show Love**: Consider starring ⭐ the repository and following my profile to stay updated on future releases!

---

```
┌─────────────────────────────────────────────────────────────────┐
│  ANDROID (STICam Node)                                          │
│  Camera2 (AE_OFF, AF_OFF)                                       │
│       ↓ zero-copy Surface pipeline                              │
│  MediaCodec H.264 Encoder                                       │
│       ↓ Annex-B NALs  ←── SPS/PPS/Frame typed packets          │
│  StreamServer (TCP :8765)                                       │
└────────────────┬────────────────────────────────────────────────┘
                 │  Wi-Fi or USB (ADB forward)
┌────────────────▼────────────────────────────────────────────────┐
│  WINDOWS (STICam Host)                                          │
│  H264Receiver → VideoDecoder (FFmpeg.AutoGen)                   │
│       ↓ Bitmap frames                                           │
│  PictureBox preview  +  VirtualCameraManager                    │
│       ↓                                                         │
│  STICam Camera (DirectShow) OR RTSP rtsp://127.0.0.1:8554/...  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Project Layout

```
STICam/
├── android/                    # Kotlin / Jetpack Compose app (STICam Node)
│   ├── app/src/main/java/com/sticam/
│   │   ├── engine/
│   │   │   ├── CameraEngine.kt         # Camera2 → MediaCodec H.264 pipeline
│   │   │   └── RecordingSession.kt     # MediaMuxer MP4 local recording
│   │   ├── server/
│   │   │   └── StreamServer.kt         # TCP server + typed-packet protocol
│   │   ├── ui/
│   │   │   ├── components/
│   │   │   │   └── HudComponents.kt    # OLED HUD sliders, badges, toggles
│   │   │   ├── theme/Theme.kt          # Material3 dark / OLED color scheme
│   │   │   ├── SticamScreen.kt         # Main HUD screen (Compose)
│   │   │   └── SticamViewModel.kt      # State management + engine lifecycle
│   │   ├── ConnectionMode.kt           # USB / Wi-Fi sealed class
│   │   └── MainActivity.kt             # Entry point + permission handling
│   └── ...
│
└── windows/SticamHost/                 # C# .NET 8 WinForms app (STICam Host)
    ├── Adb/AdbForwarder.cs             # Automatic ADB port-forward polling
    ├── Stream/
    │   ├── H264Receiver.cs             # TCP client + typed-packet parser
    │   └── VideoDecoder.cs             # FFmpeg.AutoGen H.264 → BGR24 Bitmap
    ├── VirtualCamera/
    │   ├── FfmpegPipe.cs               # BGR24 → ffmpeg stdin → RTSP/DirectShow
    │   ├── VirtualCameraManager.cs     # OBS VirtualCam detection + fallback RTSP
    │   └── MfNative.cs                 # Win11 22H2 MF P/Invoke (future)
    ├── MainForm.cs                     # WinForms UI: preview + controls + log
    └── STICamHost.csproj               # .NET 8 Windows target
```

---

## Android — Build & Deploy

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Hedgehog 2023.1+ | or IntelliJ IDEA with Android plugin |
| JDK | 17+ | bundled with Android Studio |
| Android device | API 26+ (Android 8.0+) | Camera2 full-manual support |
| USB cable | — | for USB mode; Wi-Fi needs same LAN |

### Steps

```powershell
# 1. Open the android/ folder in Android Studio as the project root
#    (it contains settings.gradle at the top level)

# 2. Let Gradle sync finish

# 3. Enable Developer Options + USB Debugging on your Android device

# 4. Run → "app" on your connected device (or Build → Generate Signed APK)
```

### First Launch

1. Grant **Camera** permission when prompted.
2. Choose **USB** or **Wi-Fi** mode from the connection row at the bottom.
3. For Wi-Fi — enter the Windows PC IP shown in `ipconfig`.
4. Tap **STREAM** — the device starts the TCP server on port **8765**.
5. Connect from the Windows Host app.

### Local MP4 Recording

- Tap the **⬤ REC** button in the HUD — records to `Movies/STICam/` on device storage.
- No re-encoding: the same H.264 stream is muxed directly to MP4.
- Requires `WRITE_EXTERNAL_STORAGE` on API < 29 (API 29+ uses scoped storage automatically).

---

## Windows Host — Build & Run

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| .NET SDK | 8.0 | `winget install Microsoft.DotNet.SDK.8` |
| Visual Studio 2022 | Community+ | or `dotnet build` from CLI |
| ffmpeg.exe | 6.x | See below |
| adb.exe | 35+ | See below |

### Place Required Tools

```
windows/SticamHost/tools/
    adb.exe             ← from Android Platform Tools
    AdbWinApi.dll       ← same package
    AdbWinUsbApi.dll    ← same package
    ffmpeg.exe          ← from https://www.gyan.dev/ffmpeg/builds/
                           (ffmpeg-release-essentials.zip, essentials build)
```

**Download links:**
- ADB: https://developer.android.com/tools/releases/platform-tools
- FFmpeg: https://www.gyan.dev/ffmpeg/builds/ → `ffmpeg-release-essentials.zip`

### Build

```powershell
cd "windows\SticamHost"
dotnet restore
dotnet build -c Release
# Or open STICamHost.csproj in Visual Studio → F5
```

### Connect

1. Launch **STICamHost.exe**.
2. **USB mode** — just click ▶ CONNECT. ADB forward runs automatically.
3. **Wi-Fi mode** — select Wi-Fi, type the Android device IP, click ▶ CONNECT.
4. The preview window shows the live H.264 feed once SPS/PPS are received.

### Virtual Webcam Output

| Path | When Available | Usage |
|------|---------------|-------|
| **STICam Camera** | Always (registered automatically on launch) | Appears as a real webcam in Zoom/Teams/Meet |
| **RTSP stream** | Always (needs ffmpeg.exe) | Add as Media Source in OBS, or open in VLC |

Click **▶ START VIRTUAL CAM** — the app auto-selects the best path.

To use RTSP in OBS:
- Sources → + → Media Source → uncheck "Local file"
- Input: `rtsp://127.0.0.1:8554/sticam`

---

## Typed-Packet Wire Protocol

All traffic between Android and Windows uses a 5-byte header:

```
[type : u8] [length : u32 big-endian] [payload : length bytes]
```

| Type | Hex | Direction | Payload |
|------|-----|-----------|---------|
| Frame | 0x00 | Android → Windows | Annex-B H.264 NAL unit |
| SPS | 0x01 | Android → Windows | Raw SPS NAL |
| PPS | 0x02 | Android → Windows | Raw PPS NAL |
| Cmd | 0x10 | Windows → Android | UTF-8 JSON command |

### JSON Commands (Windows → Android)

```json
{ "cmd": "set_params", "iso": 400, "shutterNs": 16666666, "focusDiopters": 0.0, "wbKelvin": 5500 }
{ "cmd": "request_idr" }
```

---

## Camera Controls Reference

| Parameter | Android API | Range | Notes |
|-----------|------------|-------|-------|
| ISO | `SENSOR_SENSITIVITY` | Device-reported | Read from `SENSOR_INFO_SENSITIVITY_RANGE` |
| Shutter | `SENSOR_EXPOSURE_TIME` | Device-reported ns | Read from `SENSOR_INFO_EXPOSURE_TIME_RANGE` |
| Focus | `LENS_FOCUS_DISTANCE` | 0 – max diopters | 0 = infinity, max = macro |
| White Balance | `COLOR_CORRECTION_GAINS` | 2000–10000 K | Tanner Helland Kelvin→RGGB approximation |

All four axes are set with `CONTROL_AE_MODE_OFF` + `CONTROL_AF_MODE_OFF` + `CONTROL_AWB_MODE_OFF`
so the hardware never overrides manual values.

---



## Troubleshooting

### Android — "No camera permission"
- Go to Settings → Apps → STICam → Permissions → Camera → Allow.

### Android — Black preview but streaming works
- Confirm the `TextureView` `SurfaceTexture` is valid before calling `engine.start()`.
- The `SticamViewModel` waits for `onSurfaceTextureAvailable` before starting.

### Windows — "Decoder initialized" but no video
- Check that SPS + PPS packets arrived before the first frame (watch the log).
- Try clicking **▶ CONNECT** again — IDR frame triggers a new config sequence.

### Windows — ADB forward fails
- Ensure USB Debugging is enabled and the device is trusted on this PC.
- Run `adb devices` in a terminal — the device should appear as `device` (not `unauthorized`).

### Windows — RTSP stream not available in OBS
- Confirm `ffmpeg.exe` is in `tools/` next to `STICamHost.exe`.
- ffmpeg log appears in the Host app's LOG pane.

### Windows — Build error: FFmpeg.AutoGen missing
- Run `dotnet restore` — NuGet pulls `FFmpeg.AutoGen` and `FFmpeg.runtime.windows-x64` automatically.

---

## License & Third-Party Credits

This project is licensed under the **GNU General Public License v2.0** - see the [LICENSE](LICENSE) file for details.

### Third-Party Software and Licenses
* **ADB (Android Debug Bridge)**: Developed by Google, distributed under the **Apache License 2.0**.
* **FFmpeg**: Bundled and distributed under the **GNU GPL v2.0**.
* **OBS Virtual Camera**: DirectShow components (`obs-virtualcam-module64.dll`) from the OBS Project, distributed under the **GNU GPL v2.0**.
