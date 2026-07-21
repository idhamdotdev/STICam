# STICam: Android Webcam System

[![CI](https://github.com/idhamdotdev/STICam/actions/workflows/ci.yml/badge.svg)](https://github.com/idhamdotdev/STICam/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/idhamdotdev/STICam?style=flat-square)](LICENSE)

STICam sends low-latency H.264 video from an Android phone to a Windows PC over Wi-Fi or USB. The Windows host listens for the phone, decodes the stream, displays a preview, and can forward frames to a supported virtual-camera or RTSP setup.

The maintained product targets are Android and Windows. There is no supported or buildable iOS target in this repository.

## How the connection works

The Android app is the TCP client and the Windows host is the TCP listener. Both modes use TCP port 8765:

- **Wi-Fi:** Start the Windows host first. It displays the PC's LAN IP address. Enter that **PC IP address in the Android app**; the phone then opens an outbound connection to the PC.
- **USB:** The Windows host configures an ADB reverse tunnel from the phone's 127.0.0.1:8765 to the listener on the PC. USB debugging and an authorized ADB device are required.

Video travels Android to Windows. Control messages travel in both directions over the transcript-authenticated, AES-256-GCM encrypted v2 connection. Windows protects its key with current-user DPAPI; Android protects its saved copy with Android Keystore. See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire specification and its threat-model limits.

## Features

- Hardware H.264 capture and encoding on Android.
- Wi-Fi and ADB-reverse USB connection modes.
- Windows-host controls for zoom, face tracking, ISO/auto ISO, exposure compensation, focus, torch, camera selection, resolution, and the currently exposed AR/LUT filters. Availability still depends on the phone camera.
- On-device ML Kit face tracking.
- Android-side MP4 recording in Movies/Sticam using the encoded stream without a second video encode.
- Windows preview and OBS Virtual Camera integration when a compatible OBS virtual camera is installed and registered.

Shutter time and white-balance Kelvin are not currently implemented as Windows-host controls.

### Virtual-camera and RTSP limitations

- The DirectShow webcam path requires an installed and registered OBS Virtual Camera. The application does not install a new camera driver by itself.
- The FFmpeg RTSP path publishes to rtsp://127.0.0.1:8554/sticam. A separate RTSP server must already be listening there; bundled FFmpeg alone does not provide that listener.
- The encrypted transport still is not an internet-facing service. Do not port-forward the STICam listener; use it on a local network or through a separately secured tunnel.

## Install a release

Download the Android APK and Windows installer from the [GitHub Releases page](https://github.com/idhamdotdev/STICam/releases). Verify the published SHA-256 checksum before installation.

### Wi-Fi

1. Put the phone and PC on the same trusted LAN.
2. Start the Windows host, select **Wi-Fi**, and click **Connect**. Note the PC IP and pairing key shown by the host.
3. Start the Android app, select **Wi-Fi**, and enter that PC IP and pairing key.
4. Start the Android stream. The phone connects outbound to the listener on the PC.

Allow inbound TCP 8765 through Windows Firewall only for the appropriate private network profile.

### USB

1. Install Android Platform Tools or use a release that includes the required ADB files.
2. Enable USB debugging, connect the phone, and authorize the PC on the phone.
3. Select **USB** in both applications.
4. Copy the pairing key displayed by Windows into Android.
5. Click **Connect** in the Windows host so it can establish the ADB reverse tunnel, then start the Android stream.

## Build from source

Prerequisites:

- JDK 17 and an Android SDK for the Android app.
- .NET 8 SDK on Windows for the Windows host.
- Android Platform Tools at runtime for USB mode.
- FFmpeg and OBS Virtual Camera only for their respective output/runtime paths; they are not required for the basic Windows compile.

### Android

~~~powershell
cd android
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
~~~

The Android tests cover packet parsing and deterministic cross-platform secure-channel vectors.

### Windows

~~~powershell
cd windows/SticamHost
dotnet restore
dotnet build --configuration Release --no-restore
dotnet run --project ..\SticamHost.Tests\SticamHost.Tests.csproj --configuration Release
~~~

External ADB and FFmpeg files are conditionally embedded when present under windows/SticamHost/tools/. A production release must pin and verify those inputs instead of downloading an unspecified latest build.

CI performs the Android debug build/unit-test task, the Windows Release build, and the Windows protocol/parser test runner.

## Release engineering

Do not distribute a locally built APK or EXE as a production release until production signing, tool provenance, checksums, and an SBOM are complete. The authoritative checklist and reproducible commands are in [docs/RELEASING.md](docs/RELEASING.md).

## License

STICam is licensed under the GNU General Public License v2.0; see [LICENSE](LICENSE). Bundled dependencies and tools retain their own licenses. Release packages must include the applicable third-party notices and license/source-offer materials described in the release checklist.
