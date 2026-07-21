package com.sticam.ui

import android.app.Activity
import android.app.Application
import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.view.Surface
import java.lang.ref.WeakReference
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sticam.ConnectionMode
import com.sticam.engine.CameraEngine
import com.sticam.engine.RecordingSession
import com.sticam.security.PairingKeyStore
import com.sticam.server.StreamServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── UI State ──────────────────────────────────────────────────────────────────

/** Describes one physical camera on the device. */
data class CameraInfo(
    val id: String,
    val label: String,      // e.g. "Back (Wide)", "Front"
    val isFront: Boolean,
)

/** Dynamically discovered stream quality resolution preset. */
data class StreamResolution(
    val label: String,
    val width: Int,
    val height: Int,
    val bitrateMbps: Int,
    val fps: Int = 30,
)

data class SticamUiState(
    // Connection
    val mode: ConnectionMode      = ConnectionMode.Usb,
    val wifiIp: String            = "",
    val pairingKey: String        = "",
    val isStreaming: Boolean       = false,
    val clientConnected: Boolean   = false,
    val statusMessage: String      = "STANDBY",

    // Camera on/off toggle (preview + encode)
    val isCameraOn: Boolean        = true,

    // Zoom
    val zoom: Float                = 1f,
    val maxZoom: Float             = 8f,

    // UI panels
    val controlPanelExpanded: Boolean = true,
    val showIpDialog: Boolean         = false,

    // Filter state
    val activeArFilter: String        = "None",
    val activeLutFilter: String       = "None",
    val arFaceData: com.sticam.engine.ArFaceData? = null,
    
    // Rotated dimensions
    val outW: Int                     = 1920,
    val outH: Int                     = 1080,
    val isRotated: Boolean            = false,
    val outputRotation: Int           = 0,
    val sensorOrientation: Int        = 90,
    val isFrontCamera: Boolean        = false,
    val debugInfo: String             = "",



    // ── Recording ─────────────────────────────────────────────────────────────────────────
    val isRecording: Boolean      = false,
    val recordingMs: Long         = 0L,
    val lastRecordingPath: String = "",

    // ── Camera selector ───────────────────────────────────────────────────────────────────────
    val cameras: List<CameraInfo>     = emptyList(),
    val selectedCameraId: String      = "0",
    val showCameraSelector: Boolean   = false,

    // ── Stream preset ─────────────────────────────────────────────────────────────────────────
    val preset: StreamResolution      = StreamResolution("1080p (FHD)", 1920, 1080, 10),
    val availableResolutions: List<StreamResolution> = emptyList(),
    val showPresetSelector: Boolean   = false,
    val focusLocked: Boolean          = false,
    val isFaceTrackingEnabled: Boolean = false,
    val isMirrored: Boolean           = false,
    val isFlashOn: Boolean            = false,
    val tapFocusX: Float?             = null,
    val tapFocusY: Float?             = null,
    val brightnessValue: Float        = 0f,
    val isoValue: Int                 = -1,
    val focusValue: Float             = -1f,
    val isScreenOffMode: Boolean      = false,
    val wifiSignalBars: Int           = 4,
    val needsLegacyStoragePermission: Boolean = false,
)

// Derived display string for recording timer
val SticamUiState.recLabel: String get() {
    val s = recordingMs / 1000L
    return "%02d:%02d".format(s / 60, s % 60)
}
val SticamUiState.zoomLabel: String get() = "%.1f×".format(zoom)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SticamViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(
        SticamUiState(),
    )
    val ui: StateFlow<SticamUiState> = _ui.asStateFlow()

    /** Weak reference to the host Activity for programmatic orientation changes. */
    private var activityRef: WeakReference<Activity>? = null


    /** Called from MainActivity.onCreate to provide a reference for orientation toggling. */
    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    // ── Engines ───────────────────────────────────────────────────────────────

    internal val engine        = CameraEngine(app)
    private var server:         StreamServer?    = null
    private var recording:      RecordingSession? = null
    private var previewSurface: Surface? = null
    private var restartJob: Job? = null
    private var recordingStartJob: Job? = null
    private var pairingSaveJob: Job? = null
    private val pairingEdited = java.util.concurrent.atomic.AtomicBoolean(false)

    // SPS/PPS cache for lazy recording setup
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val storedPairingKey = runCatching { PairingKeyStore.load(app) }.getOrDefault("")
            if (storedPairingKey.isNotEmpty() && !pairingEdited.get()) {
                _ui.update { state ->
                    if (!pairingEdited.get() && state.pairingKey.isEmpty()) {
                        state.copy(pairingKey = storedPairingKey)
                    } else {
                        state
                    }
                }
            }
        }

        // Enumerate cameras immediately so the UI can show the selector
        enumCameras(app)

        // Hardware capabilities → UI
        engine.onCharacteristicsReady = {
            _ui.update { s -> 
                val isFront = engine.isFrontCamera
                val faceStr = if (isFront) "FRONT" else "BACK"
                val debugText = "Face: $faceStr | Mirror: ${s.isMirrored} | OutRot: ${engine.outputRotation} | DevOri: ${engine.gravityRotation} | SenOri: ${engine.sensorOrientation} | Res: ${engine.outW}x${engine.outH}"
                s.copy(
                    maxZoom = engine.maxZoom,
                    zoom = engine.curZoom,
                    outW = engine.outW,
                    outH = engine.outH,
                    isRotated = true,
                    outputRotation = engine.outputRotation,
                    sensorOrientation = engine.sensorOrientation,
                    isFrontCamera = isFront,
                    debugInfo = debugText
                )
            }
        }

        engine.onOrientationChanged = {
            _ui.update { s -> 
                val isFront = engine.isFrontCamera
                val faceStr = if (isFront) "FRONT" else "BACK"
                val debugText = "Face: $faceStr | Mirror: ${s.isMirrored} | OutRot: ${engine.outputRotation} | DevOri: ${engine.gravityRotation} | SenOri: ${engine.sensorOrientation} | Res: ${engine.outW}x${engine.outH}"
                s.copy(
                    outW = engine.outW,
                    outH = engine.outH,
                    isRotated = true,
                    outputRotation = engine.outputRotation,
                    sensorOrientation = engine.sensorOrientation,
                    isFrontCamera = isFront,
                    debugInfo = debugText
                )
            }
        }

    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera enumeration
    // ─────────────────────────────────────────────────────────────────────────

    private fun enumCameras(context: Context) {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val list = mgr.cameraIdList.mapNotNull { id ->
            runCatching {
                val c = mgr.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT    -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK     -> "Back (Wide)"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else                                       -> "Camera $id"
                }
                CameraInfo(id = id, label = label, isFront = isFront)
            }.getOrNull()
        }
        val defaultId = list.firstOrNull { !it.isFront }?.id ?: list.firstOrNull()?.id ?: "0"
        _ui.update { it.copy(cameras = list, selectedCameraId = defaultId) }
        loadSupportedResolutions(defaultId)
    }

    fun selectCamera(id: String) {
        if (_ui.value.selectedCameraId == id) return
        _ui.update { it.copy(selectedCameraId = id, showCameraSelector = false) }
        loadSupportedResolutions(id)
        
        // Restart the camera engine with the new camera ID if currently streaming
        if (_ui.value.isStreaming) {
            previewSurface?.let { restartAfterRecording(it) }
        }
    }

    fun flipCamera() {
        val list = _ui.value.cameras
        if (list.isEmpty()) return
        val currentId = _ui.value.selectedCameraId
        val currentIndex = list.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex == -1 || currentIndex == list.size - 1) 0 else currentIndex + 1
        val nextCam = list[nextIndex]
        selectCamera(nextCam.id)
    }



    fun toggleFocusLock() {
        val nextState = !_ui.value.focusLocked
        _ui.update {
            if (!nextState) {
                it.copy(focusLocked = false, tapFocusX = null, tapFocusY = null)
            } else {
                it.copy(focusLocked = true)
            }
        }
        engine.setFocusLock(nextState)
    }

    fun toggleFlash() {
        val nextState = !_ui.value.isFlashOn
        _ui.update { it.copy(isFlashOn = nextState) }
        engine.setFlashOn(nextState)
    }

    fun tapToFocus(xNormalized: Float, yNormalized: Float, width: Int, height: Int) {
        val currentX = _ui.value.tapFocusX
        val currentY = _ui.value.tapFocusY
        if (currentX != null && currentY != null) {
            val dx = xNormalized - currentX
            val dy = yNormalized - currentY
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
            if (dist < 0.1) {
                // Tapped same area again: remove focus lock, return to continuous auto-focus, clear yellow circle
                _ui.update { it.copy(
                    focusLocked = false,
                    tapFocusX = null,
                    tapFocusY = null
                ) }
                engine.setFocusLock(false)
                return
            }
        }

        // Tapped new area: focus and lock there, keeping yellow circle visible
        _ui.update { it.copy(
            focusLocked = true,
            tapFocusX = xNormalized,
            tapFocusY = yNormalized
        ) }
        engine.triggerTapToFocus(xNormalized, yNormalized, width, height)
    }

    fun toggleCameraSelector() = _ui.update { it.copy(showCameraSelector = !it.showCameraSelector) }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stream preset
    // ─────────────────────────────────────────────────────────────────────────

    fun selectPreset(p: StreamResolution) {
        val oldPreset = _ui.value.preset
        _ui.update { it.copy(preset = p, showPresetSelector = false) }
        if (oldPreset != p) {
            previewSurface?.let { restartAfterRecording(it) }
            sendSyncParamsToClient() // Sync new resolution state back to PC
        }
    }

    private fun loadSupportedResolutions(cameraId: String) {
        val mgr = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val c = mgr.getCameraCharacteristics(cameraId)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE) ?: return
            
            val allowed = setOf(
                "3840x2160", "2560x1440", "1920x1080", "1280x720", 
                "960x540", "854x480", "640x360"
            )
            
            val list = sizes.toList()
            .distinctBy { "${it.width}x${it.height}" }
            .filter { "${it.width}x${it.height}" in allowed }
            .sortedByDescending { it.width * it.height }

            val finalResList = mutableListOf<StreamResolution>()
            for (size in list) {
                val label = when {
                    size.width == 7680 && size.height == 4320 -> "8K (UHD)"
                    size.width == 3840 && size.height == 2160 -> "4K (UHD)"
                    size.width == 2560 && size.height == 1440 -> "2K (QHD)"
                    size.width == 1920 && size.height == 1080 -> "1080p (FHD)"
                    size.width == 1280 && size.height == 720  -> "720p (HD)"
                    else -> "${size.height}p"
                }

                // Query max FPS supported by the camera sensor for this size
                val minDuration = map.getOutputMinFrameDuration(android.graphics.ImageFormat.PRIVATE, size)
                val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 30

                val baseBitrate = when {
                    size.width >= 7680 -> 40
                    size.width >= 3840 -> 24
                    size.width >= 2560 -> 16
                    size.width >= 1920 -> 10
                    size.width >= 1280 -> 6
                    else -> 4
                }

                // If sensor supports 60 FPS, add a high-speed preset with boosted bitrate
                if (maxFps >= 60) {
                    finalResList.add(
                        StreamResolution(
                            label = label,
                            width = size.width,
                            height = size.height,
                            bitrateMbps = (baseBitrate * 1.5).toInt(), // Boost bitrate by 50% for 60fps
                            fps = 60
                        )
                    )
                }

                // Always add the standard 30 FPS preset
                finalResList.add(
                    StreamResolution(
                        label = label,
                        width = size.width,
                        height = size.height,
                        bitrateMbps = baseBitrate,
                        fps = 30
                    )
                )
            }

            val defaultRes = finalResList.firstOrNull { it.width == 1920 && it.height == 1080 && it.fps == 30 }
                ?: finalResList.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: finalResList.firstOrNull { it.width == 1280 && it.height == 720 && it.fps == 30 }
                ?: finalResList.firstOrNull()
                ?: StreamResolution("1080p (FHD)", 1920, 1080, 10)

            _ui.update { it.copy(
                availableResolutions = finalResList,
                preset = defaultRes
            ) }
        } catch (e: Exception) {
            val fallback = listOf(
                StreamResolution("1080p (FHD)", 1920, 1080, 10, 30),
                StreamResolution("720p (HD)", 1280, 720, 6, 30)
            )
            _ui.update { it.copy(
                availableResolutions = fallback,
                preset = fallback[0]
            ) }
        }
    }

    fun togglePresetSelector() = _ui.update { it.copy(showPresetSelector = !it.showPresetSelector) }

    fun onPreviewSurfaceReady(surface: Surface) {
        previewSurface = surface
        if (!_ui.value.isStreaming) {
            startStreaming(surface)
        } else {
            restartCameraEngine(surface)
        }
    }

    fun onPreviewSurfaceDestroyed() {
        previewSurface = null
    }

    private fun restartCameraEngine(surface: Surface) {
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            try {
                engine.stop(keepThreads = true)
                cachedSps = null
                cachedPps = null
                // Wait briefly for camera hardware state cleanup
                kotlinx.coroutines.delay(350)
                if (!_ui.value.isStreaming || previewSurface !== surface) return@launch
                val state = _ui.value
                @Suppress("MissingPermission")
                engine.start(
                    previewSurface = surface,
                    cameraId       = state.selectedCameraId,
                    width          = state.preset.width,
                    height         = state.preset.height,
                    fps            = state.preset.fps,
                    bitrateMbps    = state.preset.bitrateMbps,
                )
                reapplyCameraStates()
                // Instantly request keyframe to force SPS/PPS headers
                engine.requestKeyFrame()
            } catch (e: Exception) {
                _ui.update { it.copy(statusMessage = "ERR: ${e.message?.take(40)}") }
            } finally {
                if (restartJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    restartJob = null
                }
            }
        }
    }

    private fun restartAfterRecording(surface: Surface) {
        recordingStartJob?.cancel()
        if (recording != null) {
            stopRecording { restartCameraEngine(surface) }
        } else {
            restartCameraEngine(surface)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Streaming
    // ─────────────────────────────────────────────────────────────────────────

    private fun reapplyCameraStates() {
        val state = _ui.value
        engine.isFaceTrackingEnabled = state.isFaceTrackingEnabled
        engine.setExposureCompensation(state.brightnessValue)
        engine.setManualIso(state.isoValue)
        if (state.focusValue > 0f) {
            engine.setFocusDistance(state.focusValue)
        }
        if (state.focusLocked) {
            engine.setFocusLock(true)
        }
        engine.setFlashOn(state.isFlashOn)
        engine.setZoom(state.zoom)
    }

    /**
     * Called by SticamScreen's SurfaceTextureListener once the TextureView
     * surface is available and camera permission has been granted.
     */
    fun startStreaming(previewSurface: Surface?) {
        if (_ui.value.isStreaming) return
        this.previewSurface = previewSurface
        val state = _ui.value
        if (state.pairingKey.length != 32 || !state.pairingKey.all { it in '0'..'9' || it in 'A'..'F' }) {
            _ui.update { it.copy(statusMessage = "ERR: Enter the 32-character PC pairing key") }
            return
        }
        if (state.mode is ConnectionMode.WiFi && state.wifiIp.isBlank()) {
            _ui.update { it.copy(statusMessage = "ERR: Enter the PC IP address") }
            return
        }
        viewModelScope.launch {
            val host = if (state.mode is ConnectionMode.Usb) "127.0.0.1" else state.wifiIp.trim()
            val srv = StreamServer(
                host = host,
                port = 8765,
                engine = engine,
                pairingKey = state.pairingKey,
            )
            val hasEverConnected = java.util.concurrent.atomic.AtomicBoolean(false)
            srv.onClientConnected    = {
                hasEverConnected.set(true)
                _ui.update { it.copy(clientConnected = true,  statusMessage = "CLIENT_CONNECTED") }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(300)
                    sendMirrorStateToClient()
                    sendSyncParamsToClient()
                }
            }
            srv.onClientDisconnected = {
                _ui.update {
                    if (server === srv && it.isStreaming) {
                        it.copy(clientConnected = false, statusMessage = "WAITING_CLIENT")
                    } else {
                        it.copy(clientConnected = false)
                    }
                }
            }
            // Cache SPS/PPS when StreamServer extracts them
            srv.onConfigData = { sps, pps -> cachedSps = sps; cachedPps = pps }
            srv.onSignalStrengthChanged = { bars -> _ui.update { it.copy(wifiSignalBars = bars) } }
            
            // Wire up incoming UI control events from Windows Client
            srv.onParamsChangedFromHost = { zoom, faceTracking, iso, brightness, focus, flash, cameraId, resolution, arFilter, lutFilter ->
                if (zoom != null) setZoom(zoom)
                if (faceTracking != null) setFaceTracking(faceTracking)
                if (iso != null) setIso(iso)
                if (brightness != null) setBrightness(brightness)
                if (focus != null) setFocusDistance(focus)
                if (flash != null && flash != _ui.value.isFlashOn) toggleFlash()
                
                if (cameraId != null && cameraId != _ui.value.selectedCameraId) {
                    _ui.value.cameras.find { it.id == cameraId }?.let { cam ->
                        selectCamera(cam.id)
                    }
                } else if (resolution != null) {
                    val currentResString = "${_ui.value.preset.width}x${_ui.value.preset.height}"
                    if (resolution != currentResString) {
                        _ui.value.availableResolutions.find { "${it.width}x${it.height}" == resolution }?.let { res ->
                            selectPreset(res)
                        }
                    }
                }
                
                if (arFilter != null) setArFilter(arFilter)
                if (lutFilter != null) setLutFilter(lutFilter)
            }
            
            srv.start()
            server = srv

            // Auto-stop if no client connects within 30 seconds
            viewModelScope.launch {
                kotlinx.coroutines.delay(30000)
                if (server === srv && !hasEverConnected.get()) {
                    Log.i("SticamViewModel", "Connection timeout (30s) reached. Stopping stream.")
                    stopStreaming()
                    _ui.update { it.copy(statusMessage = "ERR: Timeout (No PC)") }
                }
            }

            try {
                @Suppress("MissingPermission")
                engine.start(
                    previewSurface = previewSurface,
                    cameraId       = state.selectedCameraId,
                    width          = state.preset.width,
                    height         = state.preset.height,
                    fps            = state.preset.fps,
                    bitrateMbps    = state.preset.bitrateMbps,
                )
                reapplyCameraStates()
                _ui.update { it.copy(isStreaming = true, statusMessage = "WAITING_CLIENT") }
            } catch (e: Exception) {
                _ui.update { it.copy(statusMessage = "ERR: ${e.message?.take(40)}") }
                engine.stop()
                srv.stop(); server = null
            }
        }
    }

    fun stopStreaming() {
        restartJob?.cancel(); restartJob = null
        recordingStartJob?.cancel()
        stopRecording()
        engine.stop()
        server?.stop(); server = null
        _ui.update { it.copy(isStreaming = false, clientConnected = false, statusMessage = "STANDBY") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Recording
    // ─────────────────────────────────────────────────────────────────────────

    fun startRecording() {
        if (_ui.value.isRecording || !_ui.value.isStreaming || recordingStartJob?.isActive == true) return

        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _ui.update {
                it.copy(
                    needsLegacyStoragePermission = true,
                    statusMessage = "Storage permission needed for recording",
                )
            }
            return
        }

        val sps = cachedSps ?: run { _ui.update { it.copy(statusMessage = "ERR: No SPS/PPS yet") }; return }
        val pps = cachedPps ?: run { _ui.update { it.copy(statusMessage = "ERR: No SPS/PPS yet") }; return }
        val state = _ui.value
        val width = engine.captureSize.width
        val height = engine.captureSize.height

        recordingStartJob = viewModelScope.launch(Dispatchers.IO) {
            val rec = RecordingSession(getApplication())
            var attached = false
            try {
                rec.configure(
                    sps = sps,
                    pps = pps,
                    width = width,
                    height = height,
                    fps = state.preset.fps,
                    bitrateMbps = state.preset.bitrateMbps,
                )
                rec.onDurationMs = { ms -> _ui.update { it.copy(recordingMs = ms) } }
                rec.start()

                attached = withContext(Dispatchers.Main) {
                    if (!_ui.value.isStreaming || recording != null) {
                        false
                    } else {
                        val streamCallback = engine.onEncodedData
                        engine.onEncodedData = { data, info ->
                            streamCallback?.invoke(data, info)
                            rec.offerFrame(data, info)
                        }
                        recording = rec
                        _ui.update {
                            it.copy(
                                isRecording = true,
                                recordingMs = 0L,
                                statusMessage = if (it.clientConnected) "CLIENT_CONNECTED" else "WAITING_CLIENT",
                            )
                        }
                        engine.requestKeyFrame()
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update { it.copy(statusMessage = "ERR: Recording ${e.message?.take(32)}") }
                }
            } finally {
                val finishingJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                if (!attached) rec.stop()
                withContext(NonCancellable + Dispatchers.Main) {
                    if (recordingStartJob === finishingJob) {
                        recordingStartJob = null
                    }
                }
            }
        }
    }

    fun onLegacyStoragePermissionResult(granted: Boolean) {
        _ui.update { it.copy(needsLegacyStoragePermission = false) }
        if (granted) {
            startRecording()
        } else {
            _ui.update { it.copy(statusMessage = "ERR: Storage permission denied") }
        }
    }

    fun stopRecording() = stopRecording(afterStop = null)

    private fun stopRecording(afterStop: (() -> Unit)?) {
        val rec = recording
        if (rec == null) {
            afterStop?.invoke()
            return
        }
        recording = null
        // Restore streaming-only encoder callback
        val srv = server
        engine.onEncodedData = if (srv != null) { data, info -> srv.offerEncodedData(data, info) } else null
        _ui.update { it.copy(isRecording = false) }

        viewModelScope.launch(Dispatchers.IO) {
            val location = rec.stop()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(lastRecordingPath = location ?: "") }
                afterStop?.invoke()
            }
        }
    }





    // ─────────────────────────────────────────────────────────────────────────
    //  Mirror
    // ─────────────────────────────────────────────────────────────────────────

    fun toggleMirror() {
        val next = !_ui.value.isMirrored
        _ui.update { s -> 
            val isFront = engine.isFrontCamera
            val faceStr = if (isFront) "FRONT" else "BACK"
            val debugText = "Face: $faceStr | Mirror: ${next} | OutRot: ${engine.outputRotation} | DevOri: ${engine.gravityRotation} | Res: ${engine.outW}x${engine.outH}"
            s.copy(isMirrored = next, debugInfo = debugText) 
        }
        sendMirrorStateToClient()
    }

    private fun sendMirrorStateToClient() {
        val srv = server ?: return
        val mirror = _ui.value.isMirrored
        val json = "{\"cmd\":\"flip\",\"mirrorX\":$mirror,\"mirrorY\":false}"
        srv.sendCommand(json)
    }

    private fun sendSyncParamsToClient() {
        val srv = server ?: return
        val state = _ui.value
        
        val camerasJson = state.cameras.joinToString(",") { 
            "{\"id\":\"${it.id}\",\"label\":\"${it.label}\"}" 
        }
        val resJson = state.availableResolutions.map { "${it.width}x${it.height}" }
            .distinct().joinToString(",") { "\"$it\"" }
            
        val json = "{\"cmd\":\"sync_params\",\"max_focus\":${engine.maxFocusDistance},\"face_tracking\":${state.isFaceTrackingEnabled},\"zoom\":${state.zoom},\"brightness\":${state.brightnessValue},\"iso\":${state.isoValue},\"focus\":${state.focusValue},\"flash\":${state.isFlashOn},\"cameras\":[$camerasJson],\"selected_camera\":\"${state.selectedCameraId}\",\"resolutions\":[$resJson],\"selected_resolution\":\"${state.preset.width}x${state.preset.height}\",\"ar_filter\":\"${state.activeArFilter}\",\"lut_filter\":\"${state.activeLutFilter}\",\"orientation\":${state.outputRotation}}"
        
        srv.sendCommand(json)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Zoom
    // ─────────────────────────────────────────────────────────────────────────

    fun setZoom(ratio: Float) {
        engine.setZoom(ratio)
        _ui.update { it.copy(zoom = engine.curZoom) }
    }

    fun setFaceTracking(enabled: Boolean) {
        _ui.update { it.copy(isFaceTrackingEnabled = enabled) }
        engine.isFaceTrackingEnabled = enabled
    }

    fun setArFilter(filterName: String) {
        val wasZeroCopy = _ui.value.activeArFilter == "None" && _ui.value.activeLutFilter == "None"
        _ui.update { it.copy(activeArFilter = filterName) }
        engine.activeArFilter = filterName
        val isZeroCopy = filterName == "None" && _ui.value.activeLutFilter == "None"
        if (wasZeroCopy != isZeroCopy) {
            previewSurface?.let { restartAfterRecording(it) }
        }
    }

    fun setLutFilter(filterName: String) {
        val wasZeroCopy = _ui.value.activeArFilter == "None" && _ui.value.activeLutFilter == "None"
        _ui.update { it.copy(activeLutFilter = filterName) }
        engine.activeLutFilter = filterName
        val isZeroCopy = _ui.value.activeArFilter == "None" && filterName == "None"
        if (wasZeroCopy != isZeroCopy) {
            previewSurface?.let { restartAfterRecording(it) }
        }
    }

    fun setBrightness(value: Float) {
        _ui.update { it.copy(brightnessValue = value) }
        engine.setExposureCompensation(value)
    }

    fun setIso(value: Int) {
        _ui.update { it.copy(isoValue = value) }
        engine.setManualIso(value)
    }

    fun setFocusDistance(value: Float) {
        engine.setFocusDistance(value)
        _ui.update { it.copy(focusValue = value) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Connection / UI
    // ─────────────────────────────────────────────────────────────────────────

    fun selectUsb()               = _ui.update { it.copy(mode = ConnectionMode.Usb) }
    fun selectWifi(ip: String)    = _ui.update { it.copy(mode = ConnectionMode.WiFi(ip), wifiIp = ip) }
    fun setPairingKey(value: String) {
        pairingEdited.set(true)
        val normalized = value.uppercase()
            .filter { it in '0'..'9' || it in 'A'..'F' }
            .take(32)
        _ui.update { it.copy(pairingKey = normalized) }
        pairingSaveJob?.cancel()
        if (normalized.length == 32) {
            pairingSaveJob = viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(250)
                runCatching { PairingKeyStore.save(getApplication(), normalized) }
                    .onFailure { error ->
                        _ui.update { it.copy(statusMessage = "ERR: Pairing key storage ${error.message?.take(24)}") }
                    }
            }
        }
    }

    fun clearPairingKey() {
        pairingEdited.set(true)
        pairingSaveJob?.cancel()
        pairingSaveJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { PairingKeyStore.clear(getApplication()) }
                .onFailure { error ->
                    _ui.update { it.copy(statusMessage = "ERR: Pairing key storage ${error.message?.take(24)}") }
                }
        }
        _ui.update { it.copy(pairingKey = "") }
    }
    fun setShowIpDialog(v: Boolean) = _ui.update { it.copy(showIpDialog = v) }
    fun toggleControlPanel()      = _ui.update { it.copy(controlPanelExpanded = !it.controlPanelExpanded) }
    fun onPermissionDenied()      = _ui.update { it.copy(statusMessage = "CAMERA PERMISSION DENIED") }

    // Dismiss any open overlay selector on back press / outside tap
    fun dismissSelectors()        = _ui.update { it.copy(showCameraSelector = false, showPresetSelector = false) }

    // ─────────────────────────────────────────────────────────────────────────
    //  Camera On/Off toggle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Toggles the camera preview + encode output on/off.
     * When turned OFF, the encoder surface continues to exist but the camera
     * capture session is paused (stopRepeating), blanking the stream.
     * When turned ON, repeating is resumed.
     */
    fun toggleCamera() {
        val next = !_ui.value.isCameraOn
        _ui.update { it.copy(isCameraOn = next) }
        if (next) {
            engine.resumeCapture()
        } else {
            engine.pauseCapture()
        }
    }

    fun setScreenOffMode(enabled: Boolean) {
        _ui.update { it.copy(isScreenOffMode = enabled) }
    }

    override fun onCleared() {
        restartJob?.cancel()
        recordingStartJob?.cancel()
        pairingSaveJob?.cancel()
        val activeRecording = recording
        recording = null
        engine.onEncodedData = null
        server?.stop()
        server = null
        engine.stop()
        runCatching { activeRecording?.stop() }
        super.onCleared()
    }
}
