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

### Windows Host Project (`windows/SticamHost/`)
Always run .NET commands from the `windows/SticamHost/` directory.
- **Restore Packages**: `cd windows/SticamHost && dotnet restore`
- **Build Debug**: `cd windows/SticamHost && dotnet build`
- **Build Release**: `cd windows/SticamHost && dotnet build -c Release`
- **Run Application**: `cd windows/SticamHost && dotnet run`
- **Clean Build**: `cd windows/SticamHost && dotnet clean`

---

## 2. Project Architecture

### Directory Structure
- **`android/`** - Kotlin & Jetpack Compose codebase (Sticam Node / Android Transmitter)
  - `app/src/main/java/com/sticam/` - Core source code
    - `engine/` - Audio/video capture pipeline (`CameraEngine.kt`, `RecordingSession.kt`)
    - `server/` - Network streaming (`StreamServer.kt`)
    - `ui/` - Compose-based layout and views
      - `components/` - Custom reusable HUD items (`HudComponents.kt`, `HudSlider.kt`)
      - `theme/` - Color palettes, typography, and styling configurations (`Theme.kt`)
      - `SticamScreen.kt` - Main interactive HUD Compose view
      - `SticamViewModel.kt` - State holder, lifecycle engine, and user actions handler
- **`windows/`** - C# .NET 8 WinForms codebase (Sticam Host / Receiver)
  - `SticamHost/` - Receiver application
    - `Adb/` - ADB port-forwarding management
    - `Stream/` - TCP video receiver and FFmpeg H.264 frame decoder
    - `VirtualCamera/` - Virtual webcam driver output configuration (OBS VirtualCam/RTSP)
    - `MainForm.cs` - Main UI, preview box, and log controls
- **`ios/`** - SticamIOS Swift-based project (future placeholder or early iOS client)
- **`sticam_media/`** - Artifacts/design assets and icon resources

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

## 5. Editing Hygiene

- **Minimal Diffs**: Restrict code edits only to the semantic changes required to implement a feature or resolve a bug.
- **Formatting**: Do not reformat entire files. Maintain the formatting, brackets, spacing, and styling conventions of the existing file you are editing.
- **Retain Comments**: Do not remove existing comments, docstrings, or warnings unless they are directly invalidated by the changes you are introducing.
