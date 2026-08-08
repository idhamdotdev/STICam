# Changelog

User-visible changes in official STICam releases.

Release assets and SHA-256 checksums are published on
[GitHub Releases](https://github.com/idhamdotdev/STICam/releases).

## v1.0.1 — 2026-08-08

Privacy, reliability, and release-compliance update:

- disables MediaPipe's remote statistics logger using its bundled no-op implementation;
- removes Google Data Transport and Firebase from the Android runtime graph;
- removes unused Android storage-permission declarations;
- bundles full common third-party license texts and an exact Android runtime dependency
  inventory;
- adds native portrait streaming and responsive portrait connection/live-control layouts;
- uses STICam navy letterboxing for portrait video in the fixed 16:9 virtual-camera output;
- documents that the Windows installer is not yet Authenticode-signed.

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
- Features work offline, with no account, advertising, or cloud processing. The
  MediaPipe metrics behavior in this version is disclosed below.

### Known limitation

Deliberate, very rapid camera switching can occasionally fail. Normal camera switching
is reliable. Camera, resolution, and filter controls are temporarily disabled during a
switch so commands cannot stack up.

### Privacy disclosure

The v1.0.0 Android APK uses the unmodified MediaPipe Tasks 0.10.35 runtime. Google's
MediaPipe Tasks privacy notice says this runtime sends API performance and utilization
metrics to Google, but does not send the image/video input. STICam's developer does not
receive those metrics. This is removed in v1.0.1; see [PRIVACY.md](PRIVACY.md).
