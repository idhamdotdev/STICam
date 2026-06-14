package com.sticam.engine

import android.Manifest
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.CaptureRequest.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission

/**
 * Sticam Camera Engine — zero-copy Camera2→MediaCodec H.264 pipeline.
 * Runs in full-auto mode (AE, AF, AWB) with pinch-to-zoom via SCALER_CROP_REGION.
 */
class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "SticamEngine"
        private const val DEFAULT_IDR_SEC = 1.0f
    }

    private val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var camDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reqBuilder: CaptureRequest.Builder? = null

    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private var encThread: HandlerThread? = null
    private var encHandler: Handler? = null

    // Hardware capabilities (populated when camera opens)
    var captureSize: Size = Size(1920, 1080); private set
    /** Sensor physical rotation in degrees (0, 90, 180, 270). Used to correct TextureView. */
    var sensorOrientation: Int = 90; private set
    /** Maximum digital zoom multiplier reported by the HAL. */
    var maxZoom: Float = 8f; private set
    /** Current zoom multiplier (1.0 = no zoom). */
    @Volatile var curZoom: Float = 1f
    /** Full sensor active-array rectangle, needed for crop-based zoom. */
    private var sensorRect: android.graphics.Rect? = null
    private var bestFpsRange: Range<Int>? = null
    var aeRange: Range<Int> = Range(0, 0); private set
    @Volatile var curExposure: Int = 0

    @Volatile private var manualIso: Int = 0
    @Volatile private var lastExposureTimeNs: Long = 33_333_333L // ~1/30s baseline
    @Volatile private var lastSensitivity: Int = 400

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
            if (aeMode != CaptureResult.CONTROL_AE_MODE_OFF) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                    lastExposureTimeNs = it
                }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                    lastSensitivity = it
                }
            }
        }
    }

    /** Receives encoded NAL data + buffer info from the encoder thread. */
    var onEncodedData: ((data: ByteArray, info: MediaCodec.BufferInfo) -> Unit)? = null
    var onCharacteristicsReady: (() -> Unit)? = null



    // ═════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═════════════════════════════════════════════════════════════════════

    var activePreviewSurface: Surface? = null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(
        previewSurface: Surface? = null,
        cameraId: String = "0",
        width: Int = 1920, height: Int = 1080,
        fps: Int = 30, bitrateMbps: Int = 8
    ) {
        Log.i(TAG, "Start: cam=$cameraId ${width}x${height}@${fps}fps")
        activePreviewSurface = previewSurface

        val chars = camMgr.getCameraCharacteristics(cameraId)
        readChars(chars, width, height, fps)

        if (camThread == null) {
            camThread = HandlerThread("SticamCam").also { it.start() }
            camHandler = Handler(camThread!!.looper)
        }
        if (encThread == null) {
            encThread = HandlerThread("SticamEnc").also { it.start() }
            encHandler = Handler(encThread!!.looper)
        }

        setupEncoder(captureSize.width, captureSize.height, fps, bitrateMbps * 1_000_000)
        openCamera(cameraId, previewSurface)
    }

    fun stop(keepThreads: Boolean = false) {
        activePreviewSurface = null
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        camDevice?.close(); camDevice = null
        encoder?.let { e ->
            runCatching { e.signalEndOfInputStream() }
            runCatching { e.stop() }
            runCatching { e.release() }
        }
        encoder = null
        encSurface?.release(); encSurface = null
        
        if (!keepThreads) {
            camThread?.quitSafely();     camThread     = null
            camHandler = null
            encThread?.quitSafely();     encThread     = null
            encHandler = null
        }
    }

    /** Pause camera output (stop repeating request) without tearing down the session. */
    fun pauseCapture() {
        runCatching { session?.stopRepeating() }
        Log.i(TAG, "Camera paused")
    }

    /** Resume camera output after a pauseCapture() call. */
    fun resumeCapture() {
        val s = session ?: return
        val b = reqBuilder ?: return
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        Log.i(TAG, "Camera resumed")
    }

    /** Set digital zoom ratio (1.0 = wide, maxZoom = full tele). Thread-safe. */
    fun setZoom(ratio: Float) {
        curZoom = ratio.coerceIn(1f, maxZoom)
        val b = reqBuilder ?: return
        applyZoomRect(b)
        val s = session ?: return
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
    }

    /** Compute and apply the SCALER_CROP_REGION for the current zoom level. */
    private fun applyZoomRect(b: CaptureRequest.Builder) {
        val full = sensorRect ?: return
        if (curZoom <= 1f) { b.set(SCALER_CROP_REGION, full); return }
        val cropW = (full.width()  / curZoom).toInt()
        val cropH = (full.height() / curZoom).toInt()
        val cropX = full.left + (full.width()  - cropW) / 2
        val cropY = full.top  + (full.height() - cropH) / 2
        b.set(SCALER_CROP_REGION, android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH))
    }

    fun requestKeyFrame() {
        runCatching {
            val p = android.os.Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(p)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Camera Setup
    // ═════════════════════════════════════════════════════════════════════

    private fun readChars(c: CameraCharacteristics, maxW: Int, maxH: Int, targetFps: Int) {
        c.get(SENSOR_ORIENTATION)?.let { sensorOrientation = it }
        c.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { sensorRect = it }
        c.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let { maxZoom = it }

        val map = c.get(SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE)
        captureSize = sizes.filter { it.width <= maxW && it.height <= maxH }
            .maxByOrNull { it.width * it.height } ?: Size(1280, 720)

        // Find the best FPS range that targets the requested targetFps
        val ranges = c.get(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        bestFpsRange = ranges?.filter { it.upper == targetFps }
            ?.maxByOrNull { it.lower }
            ?: ranges?.filter { it.upper == 30 }?.maxByOrNull { it.lower }
            ?: ranges?.firstOrNull()

        c.get(CONTROL_AE_COMPENSATION_RANGE)?.let { aeRange = it }

        Log.i(TAG, "Caps: Size=$captureSize SensorOrientation=$sensorOrientation MaxZoom=$maxZoom SensorRect=$sensorRect BestFpsRange=$bestFpsRange AeRange=$aeRange")
        onCharacteristicsReady?.invoke()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera(id: String, preview: Surface?) {
        camMgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                camDevice = cam
                createSession(cam, preview)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); camDevice = null }
            override fun onError(cam: CameraDevice, err: Int) { cam.close(); camDevice = null }
        }, camHandler)
    }

    private fun createSession(cam: CameraDevice, preview: Surface?) {
        val outputs = mutableListOf<Surface>()
        preview?.let { outputs.add(it) }
        encSurface?.let { outputs.add(it) } ?: return

        cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                startRepeating(s, preview)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "Session config failed")
            }
        }, camHandler)
    }

    @Volatile var isFocusLocked = false
    @Volatile private var isFlashActive = false

    private fun startRepeating(s: CameraCaptureSession, preview: Surface?) {
        val b = camDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            preview?.let { addTarget(it) }
            encSurface?.let { addTarget(it) }

            // ── Auto/Manual Exposure modes ──────────────────
            set(CONTROL_MODE,         CaptureRequest.CONTROL_MODE_AUTO)
            set(CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            set(CONTROL_AWB_MODE,     CaptureRequest.CONTROL_AWB_MODE_AUTO)

            if (this@CameraEngine.manualIso > 0) {
                set(CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(SENSOR_SENSITIVITY, this@CameraEngine.manualIso)
                set(SENSOR_EXPOSURE_TIME, this@CameraEngine.lastExposureTimeNs)
            } else {
                set(CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // Force 30 FPS lock if possible to eliminate laggy slideshows
            bestFpsRange?.let { set(CONTROL_AE_TARGET_FPS_RANGE, it) }

            if (isFocusLocked) {
                set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (this@CameraEngine.manualIso <= 0) {
                    set(CONTROL_AE_LOCK, true)
                }
                set(CONTROL_AWB_LOCK, true)
            } else {
                set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                if (this@CameraEngine.manualIso <= 0) {
                    set(CONTROL_AE_LOCK, false)
                }
                set(CONTROL_AWB_LOCK, false)
            }

            // Apply flash state
            if (this@CameraEngine.isFlashActive) {
                set(FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                set(FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            // Apply exposure compensation
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, this@CameraEngine.curExposure)

            // Apply any pending zoom
            applyZoomRect(this)
        }
        reqBuilder = b
        s.setRepeatingRequest(b.build(), captureCallback, camHandler)
        Log.i(TAG, "Repeating request started (lock state: $isFocusLocked, flash: ${this@CameraEngine.isFlashActive})")
    }

    fun setFocusLock(locked: Boolean) {
        isFocusLocked = locked
        val s = session ?: return
        val b = reqBuilder ?: return
        
        if (locked) {
            b.set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            if (this@CameraEngine.manualIso <= 0) {
                b.set(CONTROL_AE_LOCK, true)
            }
            b.set(CONTROL_AWB_LOCK, true)
            
            runCatching { s.capture(b.build(), null, camHandler) }
            
            b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        } else {
            // Reset metering regions back to full image
            b.set(CONTROL_AF_REGIONS, null)
            b.set(CONTROL_AE_REGIONS, null)

            b.set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            if (this@CameraEngine.manualIso <= 0) {
                b.set(CONTROL_AE_LOCK, false)
            }
            b.set(CONTROL_AWB_LOCK, false)
            
            runCatching { s.capture(b.build(), null, camHandler) }
            
            b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        }
        Log.i(TAG, "Focus/Exposure lock updated: $locked")
    }

    fun setFlashOn(on: Boolean) {
        isFlashActive = on
        val s = session ?: return
        val b = reqBuilder ?: return
        if (on) {
            b.set(FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else {
            b.set(FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        Log.i(TAG, "Flashlight updated: $on")
     }

     fun setExposureCompensation(value: Float) {
        val minComp = aeRange.lower
        val maxComp = aeRange.upper
        if (minComp == 0 && maxComp == 0) return

        // Map -4..4 to minComp..maxComp
        val step = (maxComp - minComp) / 8f
        val ev = (value * step).toInt().coerceIn(minComp, maxComp)
        curExposure = ev

        val s = session ?: return
        val b = reqBuilder ?: return
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        Log.i(TAG, "Exposure compensation updated: $ev (mapped from $value)")
     }

     fun setManualIso(iso: Int) {
        manualIso = iso
        val s = session ?: return
        val b = reqBuilder ?: return
        if (iso > 0) {
            b.set(CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(SENSOR_SENSITIVITY, iso)
            b.set(SENSOR_EXPOSURE_TIME, lastExposureTimeNs)
        } else {
            b.set(CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (isFocusLocked) {
                b.set(CONTROL_AE_LOCK, true)
            } else {
                b.set(CONTROL_AE_LOCK, false)
            }
        }
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        Log.i(TAG, "Manual ISO set: $iso (AE OFF: ${iso > 0})")
     }

    fun triggerTapToFocus(xNormalized: Float, yNormalized: Float, viewWidth: Int, viewHeight: Int) {
        val s = session ?: return
        val b = reqBuilder ?: return
        val rect = sensorRect ?: return

        // Map normalized tap coordinates (0..1) to sensor coordinates
        val zoom = curZoom
        val cropW = rect.width() / zoom
        val cropH = rect.height() / zoom
        val cropX = rect.left + (rect.width() - cropW) / 2
        val cropY = rect.top + (rect.height() - cropH) / 2

        var tapX = xNormalized
        var tapY = yNormalized

        // Correct sensor orientation rotation
        when (sensorOrientation) {
            90 -> {
                tapX = yNormalized
                tapY = 1f - xNormalized
            }
            270 -> {
                tapX = 1f - yNormalized
                tapY = xNormalized
            }
            180 -> {
                tapX = 1f - xNormalized
                tapY = 1f - yNormalized
            }
        }

        val focusX = (cropX + tapX * cropW).toInt().coerceIn(rect.left, rect.right)
        val focusY = (cropY + tapY * cropH).toInt().coerceIn(rect.top, rect.bottom)

        // Create 5% metering rectangle centered on tap focus coordinate
        val meterW = (rect.width() * 0.05f).toInt()
        val meterH = (rect.height() * 0.05f).toInt()
        val left = (focusX - meterW / 2).coerceIn(rect.left, rect.right - meterW)
        val top = (focusY - meterH / 2).coerceIn(rect.top, rect.bottom - meterH)

        val meteringRect = android.hardware.camera2.params.MeteringRectangle(
            android.graphics.Rect(left, top, left + meterW, top + meterH),
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX
        )

        isFocusLocked = true

        // Cancel any ongoing auto-focus cycle first
        b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        runCatching { s.capture(b.build(), null, camHandler) }

        // Configure targeted AF & AE regions and start focus trigger
        b.set(CONTROL_AF_REGIONS, arrayOf(meteringRect))
        b.set(CONTROL_AE_REGIONS, arrayOf(meteringRect))
        b.set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        b.set(CONTROL_AE_LOCK, false)
        b.set(CONTROL_AWB_LOCK, false)

        runCatching { s.capture(b.build(), null, camHandler) }

        // Set back to IDLE trigger state and lock AE/AWB for static target capture
        b.set(CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        b.set(CONTROL_AE_LOCK, true)
        b.set(CONTROL_AWB_LOCK, true)
        runCatching { s.setRepeatingRequest(b.build(), null, camHandler) }

        Log.i(TAG, "Tap-to-focus triggered at sensor: ($focusX, $focusY)")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  MediaCodec H.264 Encoder (Surface input, async output)
    // ═════════════════════════════════════════════════════════════════════

    private fun setupEncoder(w: Int, h: Int, fps: Int, bitrate: Int) {
        val targetProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        val targetLevel = when {
            w >= 3840 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51 // 4K support
            w >= 2560 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5  // 2K support
            w >= 1920 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42 // 1080p @ 60fps support
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel41      // 720p @ 60fps support
        }

        // Variable Bitrate (VBR) is highly recommended for high-overhead resolutions (2K and 4K)
        // to prevent mobile hardware encoder throttling and massive network packet drops.
        // Constant Bitrate (CBR) is kept for 1080p/720p low-latency streaming stability.
        val targetBitrateMode = if (w >= 2560) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        }

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_IDR_SEC)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE, targetProfile)
            setInteger(MediaFormat.KEY_LEVEL, targetLevel)
            setInteger(MediaFormat.KEY_BITRATE_MODE, targetBitrateMode)
            runCatching { setInteger(MediaFormat.KEY_LATENCY, 0) }
            runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) } // Real-time priority
        }

        var enc: MediaCodec
        try {
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to configure with High Profile Level $targetLevel, falling back to Baseline/VBR: ${e.message}")
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        
        encSurface = enc.createInputSurface()

        enc.setCallback(object : MediaCodec.Callback() {
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                if (info.size <= 0) { c.releaseOutputBuffer(i, false); return }
                val buf = c.getOutputBuffer(i) ?: run { c.releaseOutputBuffer(i, false); return }
                val data = ByteArray(info.size)
                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(data)
                c.releaseOutputBuffer(i, false)
                onEncodedData?.invoke(data, info)
            }
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {} // Surface mode
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                Log.i(TAG, "Encoder format: $f")
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error: ${e.diagnosticInfo}", e)
            }
        }, encHandler)

        enc.start()
        encoder = enc
        Log.i(TAG, "Encoder: ${enc.name} ${w}x${h}@${fps}fps ${bitrate/1_000_000}Mbps")
    }
}
