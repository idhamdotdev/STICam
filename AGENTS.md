# Agent Developer Guide & Boundaries (AGENTS.md)

This document establishes boundaries, guidelines, development commands, and coding rules for Claude Code and other AI agents when working within this repository and future Jetpack Compose projects.

---

## 1. Development Commands

### Android Project (`android/`)
Always run Android commands from the `android/` directory using the Gradle Wrapper.
- **Sync Dependencies**: `./gradlew.bat --no-daemon` or open `android/` in Android Studio.
- **Build Debug APK**: `cd android && ./gradlew.bat assembleDebug`
- **Build Release APK**: `cd android && ./gradlew.bat assembleRelease`
- **Install Debug on Connected Device**: `cd android && ./gradlew.bat installDebug`
- **Run Android Tests**: `cd android && ./gradlew.bat test` (Unit tests) or `./gradlew.bat connectedAndroidTest` (Instrumented tests)
- **Clean Project**: `cd android && ./gradlew.bat clean`

Android JVM tests cover packet parsing, congestion recovery, and cross-platform secure-channel vectors. Keep the test tasks in CI and add tests with behavior changes. An `assembleRelease` result is not publishable until production signing is configured; never distribute an APK signed by the debug configuration as an official release.

### Windows Host Project (`windows/SticamHost/`)
Always run .NET commands from the `windows/SticamHost/` directory.
- **Restore Packages**: `cd windows/SticamHost && dotnet restore`
- **Build Debug**: `cd windows/SticamHost && dotnet build`
- **Build Release**: `cd windows/SticamHost && dotnet build -c Release`
- **Run Application**: `cd windows/SticamHost && dotnet run`
- **Clean Build**: `cd windows/SticamHost && dotnet clean`

The Windows compile does not require locally downloaded ADB or FFmpeg files because those resources are conditional. USB, RTSP, and packaging validation do require the applicable pinned runtime inputs. The tracked Windows test runner validates protocol vectors and H.264 IDR parsing.

---

## 2. Project Architecture

### Runtime roles

The class names are historical and can be misleading:

- The **Windows host is the TCP listener** on port 8765.
- The **Android node is the TCP client** and opens the outbound connection.
- Wi-Fi uses the PC LAN address entered in the Android UI.
- USB uses `adb reverse tcp:8765 tcp:8765`; Android then connects to `127.0.0.1:8765`.
- Video travels Android to Windows. Typed JSON commands travel in both directions on the same socket.

Keep [`docs/PROTOCOL.md`](docs/PROTOCOL.md) synchronized with both implementations whenever framing, packet types, commands, or fields change.

### Directory structure

- **`android/`** - Kotlin and Jetpack Compose Android transmitter
  - `app/src/main/java/com/sticam/engine/` - Camera2 capture, H.264 encoding, GL processing, face tracking, and MP4 recording
  - `app/src/main/java/com/sticam/server/StreamServer.kt` - despite its name, the outbound TCP connector, packet queue, framing, and Android command parser
  - `app/src/main/java/com/sticam/ui/` - Compose UI and state/orchestration
- **`windows/SticamHost/`** - C# .NET 8 WinForms receiver
  - `Adb/` - ADB reverse-tunnel lifecycle
  - `Stream/H264Receiver.cs` - TCP listener and typed-packet receiver/control sender
  - `Stream/VideoDecoder.cs` - H.264 decoding
  - `VirtualCamera/` - OBS Virtual Camera output and FFmpeg RTSP publishing
  - `MainForm.cs` - main UI, preview, controls, and application orchestration
- **`sticam_media/`** - tracked product icons.
- **`docs/`** - protocol and release-engineering documentation.

There is no supported or buildable iOS target. Do not describe local Swift experiments as a product platform; a future iOS target must include a reviewed Xcode project, build instructions, tests, CI, and protocol compatibility before it is added to the supported architecture.

### Output limitations

- OBS virtual-camera output requires a compatible installed and registered OBS Virtual Camera.
- The RTSP path publishes to `rtsp://127.0.0.1:8554/sticam` and therefore requires a separate RTSP server already listening there.
- Local recording is Android-side MP4 muxing of the encoded video stream.

---

## 3. Jetpack Compose UI Architecture & Boundaries

To keep Compose codebases clean, maintainable, and high-performance, strictly adhere to the following design boundaries:

### State Management & Unidirectional Data Flow (UDF)
- **State flows DOWN, Events flow UP**:
  - Keep state read operations as close as possible to where they are used.
  - Composables must not directly mutate state variables. Mutate state exclusively through event callbacks passed up to the ViewModel.
- **ViewModel Injection Limits**:
  - **Do NOT** pass ViewModels into low-level/leaf composables. 
  - ViewModels should only be injected at the **screen root** (e.g., `SticamScreen(vm)`).
  - Child composables must receive primitive parameters (or simple UI state data classes) and lambda callback events (e.g., `onValueChange: (Float) -> Unit`).
- **Lifecycle-Aware Collection**:
  - Collect StateFlows using `collectAsStateWithLifecycle()` from `androidx.lifecycle:lifecycle-runtime-compose` instead of plain `collectAsState()` to save device resources when the application is backgrounded.

### UI State Immutability
- Define UI state in a single, read-only data class (e.g., `SticamUiState`).
- Use `copy()` to emit new states.
- Avoid exposing mutable collections (like `MutableList`) directly. Use Kotlin read-only `List` types, and update them using state emission.

### Styling & Theme Consistency
- **No Hardcoded Style Values**: Do not use ad-hoc hex values (like `Color(0xFF00FF41)`) directly inside layout components. Always reference them via `MaterialTheme.colorScheme` or custom theme files (e.g. `com.sticam.ui.theme`).
- **Responsive Sizing**: Use `Modifier.weight()` and layout boxes with constraints for dynamic aspect ratios rather than hardcoded dimensions wherever possible.
- **TextureView/SurfaceTransforms**: When interfacing with legacy native components (like `TextureView` for Camera2), use `AndroidView` wrapper and apply mathematical matrix transforms (`Matrix.ScaleToFit.FILL`) to maintain unstretched aspect ratios.

### Compose Performance & Recomposition Boundaries
- **Use `remember`**: Cache expensive calculations, state, and matrices inside composables using `remember` block.
- **Key Lists**: When using `LazyColumn` or custom iterations, always supply a `key` parameter to allow Compose to optimize item updates and avoid redrawing the entire list.
- **derivedStateOf**: Use `derivedStateOf` when calculating helper state variables based on other states to avoid redundant recompositions during high-frequency changes (e.g., scroll positions, high-frequency sliders).

---

## 4. Coding & Quality Rules

### Kotlin Rules
- **No Null Assertions**: NEVER use `!!` (double exclamation marks). If a value can be null, handle it gracefully using safe calls (`?.`), the elvis operator (`?:`), or `let`.
- **Exception Safety**: Use `runCatching { ... }` or explicit `try-catch` blocks around system service APIs (like `CameraManager`, `MediaCodec`, and network operations).
- **Concurrency & Threads**:
  - Never run blocking I/O operations (file writes, network packets transmission) on the Android main thread.
  - Dispatch blocking work using Kotlin Coroutines bound to `Dispatchers.IO`. Use `viewModelScope` to automatically cancel tasks when the UI is destroyed.
- **Logging**: Use descriptive tags with `Log.d`, `Log.w`, or `Log.e`. Clean up diagnostic debug logs before submitting code edits.

### C# / .NET Rules
- **Resource Cleanup**: Always implement `IDisposable` on native resource wrappers (FFmpeg context, process streams, TCP sockets). Use `using` statements for auto-cleanup.
- **UI Thread Safety**: Never invoke UI controls directly from network or decoder threads. Use `Invoke` or `BeginInvoke` on the Form control when updating UI state from background tasks.
- **Native Interops**: Wrap Win32 and system P/Invoke signatures in safe wrapper classes (e.g. `MfNative`).

---

## 5. Release and Supply-Chain Boundaries

- Follow [`docs/RELEASING.md`](docs/RELEASING.md) for production artifacts.
- Never commit keystores, passwords, PFX files, exported private keys, or secret environment files.
- Production Android releases must use a protected production signing identity, never `signingConfigs.debug`.
- Windows release binaries and installers must be Authenticode-signed and timestamped, then independently verified.
- Record the version, authoritative URL, SHA-256 digest, and license for every downloaded ADB, FFmpeg, SDK, and packaging input. OBS Virtual Camera is an external compatibility prerequisite, not a bundled binary.
- Do not silently download a latest tool during a release build.
- Generate and publish SHA-256 checksums and an SPDX or CycloneDX SBOM for final signed artifacts.
- Build installer and source archives only from version-controlled scripts and a clean tagged revision.
- Source-bearing directories such as `android/app/src/main/assets/`, `ios/`, and installer definitions must never be hidden by broad ignore rules.

---

## 6. Editing Hygiene

- **Minimal Diffs**: Restrict code edits only to the semantic changes required to implement a feature or resolve a bug.
- **Formatting**: Do not reformat entire files. Maintain the formatting, brackets, spacing, and styling conventions of the existing file you are editing.
- **Retain Comments**: Do not remove existing comments, docstrings, or warnings unless they are directly invalidated by the changes you are introducing.
