# STICam: Android Webcam System

[![GitHub Stars](https://img.shields.io/github/stars/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev/STICam/stargazers)
[![GitHub Followers](https://img.shields.io/github/followers/idhamdotdev?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/idhamdotdev/STICam/build.yml?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/idhamdotdev/STICam/actions/workflows/build.yml)

STICam streams low-latency H.264 video from an Android device to a Windows PC over Wi-Fi or USB. The Windows application decodes the stream and registers a virtual webcam, allowing you to use your phone as a high-quality camera in Zoom, Microsoft Teams, Google Meet, and OBS Studio.

## Features

* **Low-Latency Streaming**: Fast, real-time H.264 encoding and streaming using the device's native hardware.
* **Manual Camera Controls**: Full adjustment of ISO, shutter speed, focus distance, exposure compensation, white balance Kelvin temperature, and flashlight torch from the Windows application.
* **Face Tracking**: On-device real-time face tracking using MediaPipe Face Landmarker — 468 3D landmarks, completely free and fully offline. It automatically pans and zooms to keep you centered and framed cleanly without jitter or sudden jumps.
* **Dual Connection Modes**: Wi-Fi (wireless connection) and USB (automated ADB port forwarding configuration).
* **Local Recording**: Save the stream directly as an MP4 file on the phone's storage with zero quality loss.
* **Virtual Webcam & RTSP**: Registers a DirectShow camera on Windows or exposes an RTSP stream for OBS Studio and VLC.

## Installation

### 1. Download the Files
Go to the [Releases](https://github.com/idhamdotdev/STICam/releases) page and download:
* The Android client package (`Sticam.apk`).
* The Windows Host installer (`STICamHost_Installer.exe`).

### 2. Install the Android Client
* Transfer `Sticam.apk` to your Android device.
* Open the file to install it (enable installation from unknown sources if prompted).

### 3. Install the Windows Host
* Run `STICamHost_Installer.exe` on your Windows PC and follow the setup wizard.

## How to Use

1. **Start the Android App**: Open the STICam app on your phone and select USB or Wi-Fi mode.
2. **Wi-Fi Connection**: Enter your phone's IP address in the Windows Host application.
3. **USB Connection**: Connect your phone to your PC via USB with USB Debugging enabled.
4. **Connect**: Click Connect in the Windows Host application.
5. **Start Webcam**: Click Start Virtual Cam in the Host application, then select STICam Camera in your meeting app (e.g., Zoom).

## Build Instructions

### Android Client
* Open the `android/` directory in Android Studio.
* Enable USB Debugging on your phone, then click Run or compile a debug APK using:
  ```powershell
  cd android
  ./gradlew.bat assembleDebug
  ```
* **Release signing**: copy `android/keystore.properties.example` to `android/keystore.properties` and generate a keystore with the `keytool` command shown inside it. `./gradlew.bat assembleRelease` then signs with your key (falls back to the debug key if the file is missing).

### Windows Host
1. Download `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll` (from Android Platform Tools) and `ffmpeg.exe` (from FFmpeg Essentials).
2. Place these binaries in `windows/SticamHost/tools/`.
3. Open the project folder and build:
   ```powershell
   cd windows/SticamHost
   dotnet restore
   dotnet build -c Release
   ```
4. The executable will be generated at `windows/SticamHost/bin/Release/net10.0-windows/STICamHost.exe`.
5. **Installer** (optional): publish a self-contained build with `dotnet publish -c Release -r win-x64 --self-contained -o publish`, then compile `windows/SticamInstaller.iss` with [Inno Setup 6](https://jrsoftware.org/isinfo.php).

## License

This project is licensed under the GNU General Public License v2.0.

### Third-Party Software
* **Android Debug Bridge**: Distributed under the Apache License 2.0.
* **FFmpeg**: Distributed under the GNU GPL v2.0. Bundled binaries are the [gyan.dev builds](https://www.gyan.dev/ffmpeg/builds/); corresponding source at [ffmpeg.org](https://ffmpeg.org/download.html).
* **Sdcb.FFmpeg**: .NET FFmpeg bindings distributed under the MIT License.
* **OBS Virtual Camera**: DirectShow module from [OBS Studio](https://github.com/obsproject/obs-studio) 32.0.4, distributed under the GNU GPL v2.0.
* **MediaPipe**: Face Landmarker task and runtime distributed under the Apache License 2.0 (the bundled `face_landmarker.task` model is from [Google MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker)).
* **Jetpack Compose, AndroidX & Kotlin Coroutines**: Distributed under the Apache License 2.0.
* **Lalezar Font**: Typeface by Borna Izadpanah distributed under the SIL Open Font License 1.1.
