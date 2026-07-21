# Contributing to STICam

Thank you for helping improve STICam. Bug reports, device testing, documentation, and focused code changes are welcome.

## Before You Start

- Search existing issues and pull requests before opening a duplicate.
- Discuss large features, protocol changes, new permissions, or new dependencies in an issue first.
- Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
- Read [AGENTS.md](AGENTS.md) when using an automated coding agent.

## Development Setup

### Android

Requirements:

- Android Studio or JDK 17
- Android SDK 34
- A physical Android device is strongly recommended for camera testing

Run from `android/`:

```powershell
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
```

### Windows Host

Requirements:

- Windows
- .NET 10 SDK
- Android Platform Tools for USB-mode testing

Run from `windows/SticamHost/`:

```powershell
dotnet restore
dotnet build -c Release
```

Place `adb.exe`, `AdbWinApi.dll`, and `AdbWinUsbApi.dll` in `windows/SticamHost/tools/` when testing or publishing USB support.

## Making a Change

1. Fork the repository and create a branch from `main`.
2. Keep the branch limited to one logical change.
3. Follow the existing code style and avoid unrelated reformatting.
4. Add or update tests when practical.
5. Update documentation when behavior, setup, security, licensing, or limitations change.
6. Run the verification commands for every affected platform.
7. Open a pull request against `main`.

Use clear commit messages, for example:

```text
fix: release GL resources when streaming stops
docs: correct Wi-Fi connection instructions
test: cover malformed stream packet lengths
```

## Pull Request Checklist

- [ ] The change is focused and does not include unrelated formatting.
- [ ] Android changes build with `assembleDebug`.
- [ ] Android changes pass `lintDebug`.
- [ ] Windows changes build with `dotnet build -c Release`.
- [ ] Relevant tests pass, or the pull request explains why no automated test is practical.
- [ ] Cross-platform protocol changes update Android and Windows together.
- [ ] Camera, codec, networking, and native-resource errors are handled safely.
- [ ] README and SECURITY documentation match the resulting behavior.
- [ ] New third-party components are documented in `THIRD_PARTY_NOTICES.md`.
- [ ] No credentials, signing keys, build output, or machine-specific files are committed.

Describe manual testing in the pull request, including:

- device model and Android version;
- Windows version;
- Wi-Fi, USB, or both;
- camera and resolution tested;
- reconnect, orientation, and virtual-camera results when relevant.

## High-Risk Areas

Changes in these areas require especially careful review:

- streaming protocol or packet framing;
- Wi-Fi security or authentication;
- Android permissions and foreground services;
- Camera2, MediaCodec, MediaMuxer, MediaPipe, or OpenGL lifecycle;
- FFmpeg/native memory handling;
- ADB commands;
- COM registration, shared memory, or virtual-camera behavior;
- release signing, installers, bundled binaries, and dependency updates.

## Third-Party Files

Do not add or replace binary dependencies without documenting:

- component and exact version;
- upstream project and download URL;
- applicable license;
- SHA-256 checksum;
- whether source code or a corresponding-source offer must accompany releases.

Update `THIRD_PARTY_NOTICES.md` in the same pull request.

## Documentation Accuracy

Do not advertise a feature unless it is reachable through the shipped user interface or documented public workflow. Experimental features must be labeled clearly. Known security limitations belong in both README and SECURITY documentation.

## License

STICam is licensed under the GNU General Public License v3.0. By contributing, you agree that your contribution will be distributed under the same project license.
