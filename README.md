# STICam: Android Webcam System

[![GitHub Stars](https://img.shields.io/github/stars/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev/STICam/stargazers)
[![GitHub Followers](https://img.shields.io/github/followers/idhamdotdev?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2)](LICENSE)

STICam is a high-performance system that streams low-latency H.264 video from an Android camera device to a Windows PC host over Wi-Fi or USB connection. The Windows application decodes the stream and exposes it as a DirectShow virtual webcam device or RTSP stream for use in video conferencing applications and broadcasting software.

## System Architecture

```
+-----------------------------------------------------------------+
|  ANDROID (STICam Node)                                          |
|  Camera2 API (Manual AE, AF, AWB control)                       |
|       |                                                         |
|       v Zero-copy Surface pipeline                              |
|  MediaCodec H.264 Encoder                                       |
|       |                                                         |
|       v Annex-B NAL units (SPS, PPS, Frame packets)             |
|  StreamServer (TCP Port 8765)                                   |
+-------------------------------+---------------------------------+
                                |
                                | Wi-Fi or USB (ADB port forward)
                                v
+-----------------------------------------------------------------+
|  WINDOWS (STICam Host)                                          |
|  H264Receiver -> VideoDecoder (FFmpeg.AutoGen)                  |
|       |                                                         |
|       v BGR24 Bitmap frames                                     |
|  PictureBox Preview + VirtualCameraManager                      |
|       |                                                         |
|       +-> STICam Camera (DirectShow Virtual Cam)                |
|       +-> RTSP Server (rtsp://127.0.0.1:8554/sticam)            |
+-----------------------------------------------------------------+
```

## Features

* **Low-Latency Streaming**: Encodes video on-device using Android MediaCodec H.264 and streams via raw TCP packets.
* **Manual Camera Controls**: Full remote control over ISO, shutter speed, focus distance, exposure compensation, white balance Kelvin temperature, and flashlight torch mode.
* **Face Tracking**: On-device real-time face detection using Google ML Kit. Includes:
  * A noise gate and exponential moving average filter to eliminate frame-to-frame jitter.
  * Auto-zooming and panning to keep the user's face framed at 58% of the height.
  * Zero-sway, ease-out movement that stops instantly when the face is stationary.
* **Dual Connection Modes**: Wi-Fi (direct TCP connection) and USB (automated ADB port forwarding configuration).
* **Local Recording**: Saves direct H.264 stream muxed to MP4 files on the device storage with zero re-encoding overhead.
* **Windows Virtual Webcam**: Registers as a DirectShow camera, making it compatible with Zoom, Microsoft Teams, Google Meet, and OBS Studio.
* **RTSP Server Output**: Mounts an RTSP broadcast point via FFmpeg for integration into streaming setups.
* **OpenGLES Rendering Pipeline**: Supports custom lookup tables (LUTs) and live AR filters.

## Project Structure

* **android/**: Kotlin and Jetpack Compose codebase for the Android transmitter node.
  * `app/src/main/java/com/sticam/engine/`: Capture pipeline, MediaCodec encoding, and ML Kit face detection.
  * `app/src/main/java/com/sticam/server/`: StreamServer handling the custom TCP packet protocol.
  * `app/src/main/java/com/sticam/ui/`: Compose layouts, custom HUD controls, and ViewModel state management.
* **windows/**: C# .NET 8 WinForms codebase for the Windows host receiver.
  * `SticamHost/Adb/`: Automatic ADB polling and port-forwarding management.
  * `SticamHost/Stream/`: TCP stream reception and FFmpeg decoding pipeline.
  * `SticamHost/VirtualCamera/`: DirectShow virtual webcam registration and FFmpeg RTSP pipeline.

## Build and Installation

### Android Client

#### Prerequisites
* Android Studio Hedgehog 2023.1 or newer.
* JDK 17 or newer.
* Android device running API 26 (Android 8.0) or higher with Camera2 manual hardware support.

#### Build Steps
1. Open the `android/` directory in Android Studio.
2. Allow Gradle sync to complete.
3. Enable USB Debugging on the Android device and connect it.
4. Run the project or generate a debug APK using:
   ```powershell
   cd android
   ./gradlew.bat assembleDebug
   ```

### Windows Host

#### Prerequisites
* .NET 8.0 SDK.
* Visual Studio 2022 or `dotnet` CLI tool.
* Binaries for ADB and FFmpeg.

#### Setup External Tools
Download and place the following files in the `windows/SticamHost/tools/` directory:
* From Android Platform Tools (SDK): `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`.
* From FFmpeg Essentials Build: `ffmpeg.exe`.

#### Build Steps
1. Navigate to the host project folder:
   ```powershell
   cd windows/SticamHost
   dotnet restore
   dotnet build -c Release
   ```
2. Launch the host application located in `windows/SticamHost/bin/Release/net8.0-windows/STICamHost.exe`.

## How to Use

1. Grant Camera permission to the Android app on launch.
2. Select USB or Wi-Fi mode on both the device and host application.
3. **USB Mode**: Click Connect in the Windows Host application. ADB port-forwarding will initialize automatically.
4. **Wi-Fi Mode**: Enter the Android device's local IP address in the Windows Host app and click Connect.
5. Once connected, click Start Virtual Cam in the Host application to enable the virtual webcam driver or output the RTSP stream.

## Wire Protocol

All data exchanged between the client and host uses a 5-byte header:
```
[type : u8] [length : u32 big-endian] [payload : length bytes]
```

The message types are defined as follows:

| Type | Hex | Direction | Payload |
|------|-----|-----------|---------|
| Frame | 0x00 | Android to Windows | H.264 Annex-B NAL unit |
| SPS | 0x01 | Android to Windows | H.264 Sequence Parameter Set NAL |
| PPS | 0x02 | Android to Windows | H.264 Picture Parameter Set NAL |
| Command | 0x10 | Windows to Android | UTF-8 JSON parameters |

## Camera Controls Reference

| Parameter | Android API | Scope | Notes |
|-----------|------------|-------|-------|
| ISO | `SENSOR_SENSITIVITY` | Device range | Controlled by `SENSOR_INFO_SENSITIVITY_RANGE` |
| Shutter | `SENSOR_EXPOSURE_TIME` | Device range | Controlled by `SENSOR_INFO_EXPOSURE_TIME_RANGE` |
| Focus | `LENS_FOCUS_DISTANCE` | 0 to max diopters | 0 corresponds to infinity, max to macro |
| White Balance | `COLOR_CORRECTION_GAINS` | 2000K to 10000K | Kelvin to RGB approximation |

## License

This project is licensed under the GNU General Public License v2.0. Refer to the LICENSE file for details.

### Third-Party Software
* **Android Debug Bridge**: Distributed under the Apache License 2.0.
* **FFmpeg**: Distributed under the GNU GPL v2.0.
* **OBS Virtual Camera**: DirectShow components distributed under the GNU GPL v2.0.
