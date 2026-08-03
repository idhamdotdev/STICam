# Third-Party Notices

STICam is proprietary freeware (see [EULA.md](EULA.md)). The application code is the
Developer's own work. It is not, and is not claimed to be, original in every byte it
ships: like any real application it uses third-party libraries, a font, a
machine-learning model, and operating-system APIs.

This document identifies every third-party component referenced, bundled, or used to
produce a STICam release, with its version, purpose, and license.

**These components remain under their own licenses.** Nothing in the STICam EULA
limits, reduces, or overrides any right those licenses grant you. Where a third-party
license conflicts with the EULA, the third-party license prevails as to that component.

This notice is informational and does not replace the complete license text supplied by
each upstream project. Release packages include the full license texts required by the
exact binaries being distributed.

## Copyleft status

STICam contains **no copyleft-licensed components**. Every third-party component listed
below is under a permissive license (Apache-2.0, MIT, BSD) or the SIL Open Font License,
all of which permit inclusion in a proprietary application.

Decoding and the virtual camera both use Media Foundation, which is part of Windows. No
third-party media binaries are distributed with STICam.

## Bundled files

| Component | Purpose | Upstream and license |
| --- | --- | --- |
| MediaPipe Face Landmarker model (`face_landmarker.task`) | On-device face landmark detection | [Google MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker), Apache License 2.0 |
| Lalezar font | Branding typeface, Android and Windows | Typeface by Borna Izadpanah, [SIL Open Font License 1.1](https://github.com/google/fonts/tree/main/ofl/lalezar) |

The Lalezar font is redistributed under the SIL OFL 1.1, which expressly permits
bundling in software regardless of that software's own license. The font remains under
the OFL, is distributed with its license text, and is not sold separately.

SHA-256 checksums for bundled binary assets are published with each release.

## Android dependencies

| Component | Version | Purpose | License |
| --- | --- | --- | --- |
| Jetpack Compose BOM | 2024.02.00 | Compose dependency alignment | Apache-2.0 ([AndroidX](https://github.com/androidx/androidx)) |
| AndroidX Lifecycle | 2.7.0 | Lifecycle and ViewModel integration | Apache-2.0 |
| AndroidX Activity Compose | 1.8.2 | Compose activity integration | Apache-2.0 |
| AndroidX AppCompat | 1.6.1 | Android compatibility support | Apache-2.0 |
| AndroidX Core KTX | 1.12.0 | Android Kotlin extensions | Apache-2.0 |
| MediaPipe Tasks Vision | 0.10.35 | On-device face landmark detection | Apache-2.0 ([MediaPipe](https://github.com/google-ai-edge/mediapipe)) |
| Kotlin Coroutines Android | 1.7.3 | Structured concurrency | Apache-2.0 ([kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)) |
| Kotlin stdlib / Android plugin | 1.9.22 | Kotlin runtime and compilation | Apache-2.0 ([Kotlin](https://github.com/JetBrains/kotlin)) |

Transitive Android dependencies retain their own licenses and notices. A dependency and
license report is generated and reviewed whenever versions change.

## Windows dependencies

| Component | Version | Purpose | License |
| --- | --- | --- | --- |
| .NET runtime | 10.0, embedded self-contained | Windows application runtime | MIT ([dotnet/runtime](https://github.com/dotnet/runtime)) plus its included third-party notices |
| Microsoft .NET Runtime installer (x64) | 10.0.10 | Bootstrap prerequisite, run silently at install time only when the machine lacks .NET 10, then deleted | Redistributed unmodified from [Microsoft](https://aka.ms/dotnet/10.0/dotnet-runtime-win-x64.exe). MIT. Authenticode-signed `CN=.NET, O=Microsoft Corporation` |
| Media Foundation | Part of Windows 11 | H.264 decoding and the virtual camera | Operating-system component, used through public APIs, not redistributed |
| Android Debug Bridge (`adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`) | 1.0.41 (Platform Tools 36.0.0-13206524) | USB reverse tunneling | Apache-2.0 ([Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)) |

### Release binary record

The Windows installer includes or invokes these conditionally distributed binaries:

| File | Version | SHA-256 |
| --- | --- | --- |
| `adb.exe` | Android Debug Bridge 1.0.41 (36.0.0-13206524) | `1E1C2280B90B3F01AD84CD8DF4858B1B1995012814F3CA8893BCC3BA3848EDEC` |
| `AdbWinApi.dll` | Platform Tools 36.0.0-13206524 | `120BEF587119C6CB926B86B9BE90FDFBCE38937588EAE28CD91A94CE63C7B965` |
| `AdbWinUsbApi.dll` | Platform Tools 36.0.0-13206524 | `6CA69A2CA0E31309C087D288F058977D421AD03500E4C3E1DBD981241A069C60` |
| `dotnet-runtime-win-x64.exe` | Microsoft .NET Runtime 10.0.10 (x64) | `38CF0578B18F98FEBBB9FE63FC12671AFE951D12BB5F2F3EFF3F801CC0D37993` |

## Build and packaging tools

These produce STICam but are not redistributed as part of it:

- **Gradle** and the Gradle Wrapper — Apache-2.0
- **Inno Setup 6** — [jrsoftware.org](https://jrsoftware.org/isinfo.php); its license permits
  building installers for commercial and proprietary applications
- **Android Studio, Android SDK, JDK, .NET SDK, PowerShell** — development tools, each
  under its own license
- **JUnit 4** (EPL-1.0) and **xUnit** (Apache-2.0) — test frameworks, never packaged into
  a release build

## Trademarks

Windows, Android, Google Play, MediaPipe, Discord, Zoom, Microsoft Teams, and OBS are
trademarks of their respective owners, referenced for identification only. No
affiliation or endorsement is implied.

## Release compliance checklist

Before publishing an APK or Windows installer:

- inventory every bundled DLL, executable, model, font, package, and runtime;
- confirm no copyleft-licensed component has entered the dependency graph;
- verify component versions and SHA-256 checksums;
- include required upstream copyright and license notices in the shipped package;
- preserve notices from NuGet, Gradle, MediaPipe, Android Platform Tools, and the .NET
  runtime;
- record the version and SHA-256 of every conditionally bundled binary in the release
  notes;
- update this document whenever a dependency, binary, model, font, or packaging method
  changes.

## Reporting an attribution problem

If you believe a component is missing from this list, misattributed, or incorrectly
licensed, report it to **hello@idham.dev**. Attribution errors are treated as
bugs and corrected in the next release.
