# STICam: Android Webcam System

[![GitHub Stars](https://img.shields.io/github/stars/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev/STICam/stargazers)
[![GitHub Followers](https://img.shields.io/github/followers/idhamdotdev?style=for-the-badge&color=2A7AE2&logo=github)](https://github.com/idhamdotdev)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=for-the-badge&color=2A7AE2)](LICENSE)

STICam streams low-latency H.264 video from an Android device to a Windows PC over Wi-Fi or USB. The Windows application decodes the stream and registers a virtual webcam, allowing you to use your phone as a high-quality camera in Zoom, Microsoft Teams, Google Meet, and OBS Studio.

## Features

* **Low-Latency Streaming**: Fast, real-time H.264 encoding and streaming using the device's native hardware.
* **Manual Camera Controls**: Full adjustment of ISO, shutter speed, focus distance, exposure compensation, white balance Kelvin temperature, and flashlight torch from the Windows application.
* **Face Tracking**: On-device real-time face detection using Google ML Kit (completely free and runs locally offline with no cloud server costs). It automatically pans and zooms to keep you centered and framed cleanly without jitter or sudden jumps.
* **Dual Connection Modes**: Wi-Fi (wireless connection) and USB (automated ADB port forwarding configuration).
* **Local Recording**: Save the stream directly as an MP4 file on the phone's storage with zero quality loss.
* **Virtual Webcam & RTSP**: Registers a DirectShow camera on Windows or exposes an RTSP stream for OBS Studio and VLC.

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

### Windows Host
1. Download `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll` (from Android Platform Tools) and `ffmpeg.exe` (from FFmpeg Essentials).
2. Place these binaries in `windows/SticamHost/tools/`.
3. Open the project folder and build:
   ```powershell
   cd windows/SticamHost
   dotnet restore
   dotnet build -c Release
   ```
4. The executable will be generated at `windows/SticamHost/bin/Release/net8.0-windows/STICamHost.exe`.

## License

This project is licensed under the GNU General Public License v2.0.

### Third-Party Software
* **Android Debug Bridge**: Distributed under the Apache License 2.0.
* **FFmpeg**: Distributed under the GNU GPL v2.0.
* **OBS Virtual Camera**: DirectShow components distributed under the GNU GPL v2.0.
* **Google ML Kit**: Face detection SDK distributed under Google APIs Terms of Service.
