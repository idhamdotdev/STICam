# Third-Party Notices

STICam is distributed under the GNU General Public License v3.0. This document identifies third-party components referenced, bundled, or used to produce STICam releases.

This notice is informational and does not replace the complete license text supplied by each upstream project. Release packages must include all license texts, notices, and corresponding-source information required by the exact binaries being distributed.

## Bundled Files

| Component | Committed file | SHA-256 | Upstream and license |
| --- | --- | --- | --- |
| OBS Virtual Camera module 32.0.4 | `windows/SticamHost/tools/obs-virtualcam-module64.dll` | `0a86773a416869843827da111c9ba530f26a2d8d3121d09915b91f1bc4ca4cdd` | [OBS Studio 32.0.4](https://github.com/obsproject/obs-studio/tree/32.0.4), GNU GPL upstream terms compatible with STICam's GPL-3.0 distribution |
| MediaPipe Face Landmarker model | `android/app/src/main/assets/face_landmarker.task` | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | [Google MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker), Apache License 2.0 |
| Lalezar font | `android/app/src/main/res/font/lalezar.ttf` and `windows/SticamHost/fonts/Lalezar-Regular.ttf` | `7b80807bc831f141de0f7dcf6816412bf0c548968d548c5156ca388733390ea9` | Typeface by Borna Izadpanah, [SIL Open Font License 1.1](https://github.com/google/fonts/tree/main/ofl/lalezar) |
| Gradle Wrapper | `android/gradle/wrapper/gradle-wrapper.jar` | `0336f591bc0ec9aa0c9988929b93ecc916b3c1d52aed202c7381db144aa0ef15` | [Gradle 8.13](https://github.com/gradle/gradle/tree/v8.13.0), Apache License 2.0 |

## Android Dependencies

Versions are taken from the committed Gradle configuration.

| Component | Version | Purpose | License/source |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | Android build tooling | [Android source](https://android.googlesource.com/platform/tools/base/), Apache License 2.0 |
| Kotlin Android plugin | 1.9.22 | Kotlin compilation | [Kotlin](https://github.com/JetBrains/kotlin), Apache License 2.0 |
| Jetpack Compose BOM | 2024.02.00 | Compose dependency alignment | [AndroidX](https://github.com/androidx/androidx), Apache License 2.0 |
| AndroidX Lifecycle | 2.7.0 | Lifecycle and ViewModel integration | [AndroidX](https://github.com/androidx/androidx), Apache License 2.0 |
| AndroidX Activity Compose | 1.8.2 | Compose activity integration | [AndroidX](https://github.com/androidx/androidx), Apache License 2.0 |
| AndroidX AppCompat | 1.6.1 | Android compatibility support | [AndroidX](https://github.com/androidx/androidx), Apache License 2.0 |
| AndroidX Core KTX | 1.12.0 | Android Kotlin extensions | [AndroidX](https://github.com/androidx/androidx), Apache License 2.0 |
| MediaPipe Tasks Vision | 0.10.21 | On-device face landmark detection | [MediaPipe](https://github.com/google-ai-edge/mediapipe), Apache License 2.0 |
| Kotlin Coroutines Android | 1.7.3 | Structured concurrency | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), Apache License 2.0 |

Transitive Android dependencies retain their own licenses and notices. Generate and review a release dependency/license report whenever versions change.

## Windows Dependencies

| Component | Version | Purpose | License/source |
| --- | --- | --- | --- |
| Sdcb.FFmpeg | 6.0.26 | .NET FFmpeg bindings | [Sdcb.FFmpeg](https://github.com/sdcb/Sdcb.FFmpeg), MIT License |
| Sdcb.FFmpeg runtime for Windows x64 | 6.0.26 | Native FFmpeg runtime | Package notices plus the license of the included FFmpeg build |
| .NET runtime | 10.0 when self-contained | Windows application runtime | [dotnet/runtime](https://github.com/dotnet/runtime), MIT License and included third-party notices |

## Conditionally Distributed Tools

These files are not all committed to the repository, but release or local publish workflows may place them in `windows/SticamHost/tools/`.

| Component | Purpose | Upstream and license |
| --- | --- | --- |
| Android Debug Bridge: `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` | USB reverse tunneling | [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools), Apache License 2.0 |
| FFmpeg executable | Experimental FFmpeg output path | [FFmpeg source](https://ffmpeg.org/download.html), GNU GPL v3.0 for the GPL-enabled distributed build; binary provider: [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) |

Before publishing, record the exact version and SHA-256 checksum of every conditionally distributed binary in the release notes or release manifest.

## Packaging Tools

- [Inno Setup 6](https://jrsoftware.org/isinfo.php) is used to build the Windows installer. Its own license applies to the tool and generated installer components.
- Android Studio, the Android SDK, JDK, .NET SDK, and PowerShell are development tools and are not automatically covered by the STICam project license.

## Project Assets

Original STICam source code and project-owned artwork are distributed under the repository's GNU GPL v3.0 license unless a file states otherwise. Third-party trademarks remain the property of their respective owners.

## Release Compliance Checklist

Before publishing an APK or Windows installer:

- confirm the project license remains GNU GPL v3.0;
- inventory every bundled DLL, executable, model, font, package, and runtime;
- verify component versions and SHA-256 checksums;
- include required upstream copyright and license notices;
- provide corresponding source or a valid source offer where required;
- preserve notices from NuGet, Gradle, FFmpeg, OBS Studio, MediaPipe, Android Platform Tools, and the .NET runtime;
- update this document whenever a dependency, binary, model, font, or packaging method changes.
