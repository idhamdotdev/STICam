# Changelog

User-visible changes in official STICam releases.

Release assets and SHA-256 checksums are published on
[GitHub Releases](https://github.com/idhamdotdev/STICam/releases).

## v1.0.0

First public release.

### Included

- H.264 streaming from Android to Windows over Wi-Fi or USB.
- Selection of one authorized Android device when several are connected over ADB.
- On-device MediaPipe face tracking and automatic framing.
- Manual zoom, exposure, ISO, focus, flashlight, camera, resolution, and mirror controls.
- On-device AR filters.
- Native Windows Media Foundation virtual camera with automatic startup.
- The Windows executable title and Task Manager identity are consistent.
- Automatic reconnection and clear stream failure messages.
- Offline operation with no account, analytics, advertising, or cloud processing.

### Known limitation

Deliberate, very rapid camera switching can occasionally fail. Normal camera switching
is reliable. Camera, resolution, and filter controls are temporarily disabled during a
switch so commands cannot stack up.
