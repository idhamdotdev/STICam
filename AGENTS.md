# Agent Development Guide

This file defines the working rules for automated coding agents contributing to STICam.

## Project Facts

- STICam is licensed under the GNU GPL v3.0.
- `android/` is the Android transmitter, written in Kotlin and Jetpack Compose.
- `windows/SticamHost/` is the Windows receiver, written in C# and WinForms for .NET 10.
- Android initiates the TCP connection to the Windows listener on port `8765`.
- Wi-Fi traffic is intentionally unauthenticated and unencrypted; it is for trusted private networks only.
- USB mode uses `adb reverse tcp:8765 tcp:8765`.
- Local recording has a backend but no user-facing control.
- A standalone RTSP server is not included.
- DirectShow virtual-camera output is experimental.

Do not describe unfinished or experimental behavior as generally available.

## Repository Structure

```text
android/
  app/src/main/java/com/sticam/
    engine/              Camera, encoder, MediaPipe, recording, and OpenGL pipeline
    server/StreamServer  Outbound Android-to-Windows TCP connection and control protocol
    ui/                  Compose UI and SticamViewModel

windows/
  SticamHost/
    Adb/                 ADB reverse-tunnel management
    Stream/              TCP receiver and FFmpeg decoding
    VirtualCamera/       Experimental DirectShow/shared-memory output and RTSP fallback code
    MainForm.cs          WinForms UI and application coordination
  SticamInstaller.iss    Inno Setup installer definition
```

## Development Commands

Run Android commands from `android/`:

```powershell
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat assembleRelease --no-daemon
```

`assembleRelease` uses the debug key when `keystore.properties` is missing. Never distribute that fallback build as an official release.

Run Windows commands from `windows/SticamHost/`:

```powershell
dotnet restore
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained -o publish
```

The repository currently has no committed automated test suites. Add focused tests when practical, especially for protocol framing, state transitions, and conversion code.

## Editing Rules

- Make minimal, focused changes and preserve unrelated work.
- Do not reformat whole files for a small change.
- Do not commit build output, signing keys, credentials, or machine-specific configuration.
- Do not replace bundled models, fonts, DLLs, ADB, or FFmpeg binaries without recording the exact version, source URL, license, and checksum in `THIRD_PARTY_NOTICES.md`.
- Update README, SECURITY, CONTRIBUTING, and third-party notices when behavior or distribution changes.
- Keep the names **STICam**, **STICam Host**, **Android transmitter**, and **Windows listener** consistent.

## Android Rules

- Keep UI state in immutable data classes exposed through `StateFlow`.
- Collect flows with `collectAsStateWithLifecycle()`.
- Pass values and event callbacks to leaf composables; do not pass the ViewModel deep into the UI tree.
- Never perform blocking camera, codec, network, or file work on the main thread.
- Serialize camera/session restarts and propagate asynchronous Camera2 and MediaCodec failures to UI state.
- Do not introduce new `!!` assertions unless a documented invariant makes null impossible.
- Handle Camera2, MediaCodec, MediaMuxer, network, and OpenGL failures explicitly.
- Respect device-reported camera, encoder, frame-rate, ISO, zoom, focus, and stream-combination capabilities.
- Handle `YUV_420_888` row and pixel strides correctly.
- Release Camera2, MediaCodec, ImageReader, Surface, MediaPipe, thread, and EGL resources idempotently.

## Windows Rules

- Dispose `Bitmap`, socket, process, FFmpeg, registry, memory-mapped, and native resources deterministically.
- Never update WinForms controls from worker threads without `Invoke` or `BeginInvoke`.
- Bound frame and command queues; prefer the newest frame when latency matters.
- Keep FFmpeg packet padding and native buffer-size requirements intact.
- Validate even frame dimensions before NV12 conversion.
- Never report a virtual camera as active until registration and output readiness are verified.
- Do not overwrite another application's COM registration or shared-memory producer without explicit compatibility and ownership checks.

## Streaming Protocol

The protocol is shared by `StreamServer.kt` and `H264Receiver.cs`:

```text
[1-byte type][4-byte big-endian length][payload]

0x00  H.264 frame
0x01  SPS
0x02  PPS
0x10  UTF-8 JSON command
```

Any protocol change must:

- update both Android and Windows in the same pull request;
- preserve strict payload limits and reject malformed packets;
- document compatibility or add protocol version negotiation;
- include tests for partial reads, invalid lengths, reconnects, and configuration changes;
- update README or SECURITY when user-visible behavior or risk changes.

## Verification Before Submission

- Run `git diff --check`.
- Build every changed platform.
- Run Android lint for Android changes.
- Run relevant automated tests when present.
- Manually verify both Wi-Fi and USB when networking changes.
- Manually verify preview, reconnect, orientation, camera switching, and virtual-camera status when those paths change.
- Confirm documentation matches committed behavior and does not promise inaccessible features.
- Confirm the project remains GPL-3.0 and third-party notices remain complete.
