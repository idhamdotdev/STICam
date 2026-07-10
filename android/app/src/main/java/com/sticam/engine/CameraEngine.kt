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
import com.sticam.engine.gl.GlRenderer
import androidx.annotation.RequiresPermission
import android.graphics.PointF
import android.graphics.Rect
import android.media.ImageReader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import android.graphics.RectF

data class ArFaceData(
    val bounds: RectF, // Normalized 0.0 - 1.0 relative to visible screen
    val leftEye: PointF?,
    val rightEye: PointF?,
    val noseBase: PointF?,
    val mouthBottom: PointF?,
    val leftCheek: PointF?,
    val rightCheek: PointF?
)

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
    
    private var detectThread: HandlerThread? = null
    private var detectHandler: Handler? = null
    
    // OpenGL LUT Pipeline
    private var glRenderer: GlRenderer? = null
    private var currentPreviewSurface: Surface? = null
    private fun isZeroCopyPath(lut: String = activeLutFilter, ar: String = activeArFilter): Boolean {
        return !isFrontCamera && lut == "None" && ar == "None"
    }

    var activeLutFilter: String = "None"
        set(value) {
            val wasZeroCopy = isZeroCopyPath(field, activeArFilter)
            field = value
            val isZeroCopy = isZeroCopyPath(field, activeArFilter)
            if (wasZeroCopy != isZeroCopy) {
                // We need to re-create the session!
                camHandler?.post {
                    session?.close()
                    session = null
                    camDevice?.let { createSession(it, currentPreviewSurface) }
                }
            } else if (!isZeroCopy) {
                // Just update the LUT!
                glRenderer?.setFilter(value)
            }
        }

    var activeArFilter: String = "None"
        set(value) {
            val wasZeroCopy = isZeroCopyPath(activeLutFilter, field)
            field = value
            val isZeroCopy = isZeroCopyPath(activeLutFilter, field)
            if (wasZeroCopy != isZeroCopy) {
                camHandler?.post {
                    session?.close()
                    session = null
                    camDevice?.let { createSession(it, currentPreviewSurface) }
                }
            } else if (!isZeroCopy) {
                glRenderer?.setArFilter(value)
            }
        }

    // Orientation & Dimensions
    @Volatile var isFrontCamera: Boolean = false
    @Volatile var outputRotation: Int = 0
    @Volatile var gravityRotation: Int = 0
    @Volatile var outW: Int = 1920
    @Volatile var outH: Int = 1080
    var onOrientationChanged: (() -> Unit)? = null
    private var orientationListener: android.view.OrientationEventListener? = null

    /**
     * Total rotation the GL encoder must apply to produce an upright stream on the PC.
     * Front camera: sensor physically mounted rotated → combine sensor + user manual offset.
     * Back camera:  zero-copy path handles rotation via metadata; GL path only needs user offset.
     */
    private val encoderRotation: Int
        get() = if (isFrontCamera) {
            (sensorOrientation + outputRotation) % 360
        } else {
            outputRotation
        }

    // AI Face Tracking
    private var imageReader: ImageReader? = null
    private var faceDetector: FaceDetector? = null
    private val isDetectorBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val currentCropCenter = PointF()
    private var currentCropZoom = 1.0f
    @Volatile private var activeFaceCropRect: Rect? = null
    @Volatile private var lastAppliedCropRect: Rect? = null
    var onFaceZoomChanged: ((zoom: Float) -> Unit)? = null
    var onArFaceDataUpdated: ((ArFaceData?) -> Unit)? = null
    private var lastUpdateTime = 0L

    private var targetCenterX = 0f
    private var targetCenterY = 0f
    private var targetZoom = 1.0f
    private var noFaceDetectedFramesCount = 0
    private var consecutiveFaceFrames = 0
    private var isInitialEngage = false

    // Velocity accumulators for momentum-based smoothing (talking head dampening)
    private var velX = 0f
    private var velY = 0f
    private var velZoom = 0f

    // Smoothed face height signal to prevent noisy bounding boxes from causing random zoom
    private var smoothedFaceHeight = 0f

    // Smoothed sensor coordinates to filter out rapid movement/jitter for organic panning
    private var smoothedSensorX = 0f
    private var smoothedSensorY = 0f

    var isFaceTrackingEnabled: Boolean = false
        set(value) {
            field = value
            camHandler?.post {
                val full = sensorRect ?: return@post
                if (value) {
                    if (currentCropCenter.x == 0f && currentCropCenter.y == 0f) {
                        currentCropCenter.set(full.centerX().toFloat(), full.centerY().toFloat())
                    }
                    targetCenterX = currentCropCenter.x
                    targetCenterY = currentCropCenter.y
                    targetZoom = currentCropZoom
                    noFaceDetectedFramesCount = 0
                    consecutiveFaceFrames = 0
                    isInitialEngage = true
                    velX = 0f; velY = 0f; velZoom = 0f  // clear any residual momentum
                    smoothedFaceHeight = 0f              // reset EMA so zoom re-anchors cleanly
                    smoothedSensorX = 0f
                    smoothedSensorY = 0f
                    lastAppliedCropRect = null
                    camHandler?.removeCallbacks(faceTrackingUpdateRunnable)
                    camHandler?.post(faceTrackingUpdateRunnable)
                } else {
                    targetCenterX = full.centerX().toFloat()
                    targetCenterY = full.centerY().toFloat()
                    targetZoom = 1.0f
                    velX = 0f; velY = 0f; velZoom = 0f  // clear momentum on disable
                    // Do not remove callbacks — let tickFaceTracking lerp us back smoothly.
                }
            }
        }

    private val faceTrackingUpdateRunnable = object : Runnable {
        override fun run() {
            if (session != null && reqBuilder != null) {
                val full = sensorRect
                if (full != null) {
                    val isLerpingBack = !isFaceTrackingEnabled && 
                        (Math.abs(currentCropZoom - 1.0f) > 0.01f || 
                         Math.abs(currentCropCenter.x - full.centerX()) > 5f || 
                         Math.abs(currentCropCenter.y - full.centerY()) > 5f)
                         
                    if (isFaceTrackingEnabled || isLerpingBack) {
                        tickFaceTracking()
                        camHandler?.postDelayed(this, 33)
                        return
                    }
                }
            } else if (isFaceTrackingEnabled) {
                camHandler?.postDelayed(this, 100)
            }
        }
    }

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

    var maxFocusDistance: Float = 10f; private set
    @Volatile private var manualFocusDistance: Float = 0f

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

        activeFaceCropRect = null
        currentCropCenter.set(0f, 0f)
        currentCropZoom = 1f
        targetCenterX = 0f
        targetCenterY = 0f
        targetZoom = 1f
        noFaceDetectedFramesCount = 0
        isFaceTrackingEnabled = false

        val chars = camMgr.getCameraCharacteristics(cameraId)
        isFrontCamera = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        readChars(chars, width, height, fps)

        outW = captureSize.width
        outH = captureSize.height

        // Automated OrientationEventListener disabled to support manual button-driven orientation lock
        /*
        if (orientationListener == null) {
            orientationListener = object : android.view.OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val rotation = when (orientation) {
                        in 45 until 135 -> 270
                        in 135 until 225 -> 180
                        in 225 until 315 -> 90
                        else -> 0
                    }
                    if (rotation != gravityRotation) {
                        gravityRotation = rotation
                        onOrientationChanged?.invoke()
                    }
                }
            }
            orientationListener?.enable()
        }
        */

        if (camThread == null) {
            camThread = HandlerThread("SticamCam").also { it.start() }
            camHandler = Handler(camThread!!.looper)
        }
        if (encThread == null) {
            encThread = HandlerThread("SticamEnc").also { it.start() }
            encHandler = Handler(encThread!!.looper)
        }
        if (detectThread == null) {
            detectThread = HandlerThread("SticamDetect").also { it.start() }
            detectHandler = Handler(detectThread!!.looper)
        }

        setupImageReader(chars, captureSize.width, captureSize.height)
        setupEncoder(captureSize.width, captureSize.height, fps, bitrateMbps * 1_000_000)
        openCamera(cameraId, previewSurface)
    }

    fun stop(keepThreads: Boolean = false) {
        activePreviewSurface = null
        orientationListener?.disable()
        orientationListener = null

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
        
        imageReader?.close(); imageReader = null
        faceDetector?.close(); faceDetector = null
        isDetectorBusy.set(false)
        
        if (!keepThreads) {
            camThread?.quitSafely();     camThread     = null
            camHandler = null
            encThread?.quitSafely();     encThread     = null
            encHandler = null
            detectThread?.quitSafely();  detectThread  = null
            detectHandler = null
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

    fun setManualRotation(rotationDegrees: Int) {
        outputRotation = rotationDegrees
        gravityRotation = rotationDegrees
        // Push the corrected total rotation (sensor + manual) to the live GL renderer
        glRenderer?.rotationDegrees = encoderRotation
        onOrientationChanged?.invoke()
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
        if (isFaceTrackingEnabled) {
            val faceRect = activeFaceCropRect
            if (faceRect != null) {
                b.set(SCALER_CROP_REGION, faceRect)
                return
            }
        }
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
        c.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { maxFocusDistance = it }

        Log.i(TAG, "Caps: ReqRes=${maxW}x${maxH} ActualRes=$captureSize SensorOrientation=$sensorOrientation MaxZoom=$maxZoom SensorRect=$sensorRect BestFpsRange=$bestFpsRange AeRange=$aeRange MaxFocusDistance=$maxFocusDistance")
        onCharacteristicsReady?.invoke()
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera(id: String, preview: Surface?) {
        currentPreviewSurface = preview
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
        
        // Clean up old GL renderer
        glRenderer?.release()
        glRenderer = null

        val enc = encSurface ?: return
        imageReader?.surface?.let { outputs.add(it) }

        if (isZeroCopyPath()) {
            // ZERO-COPY PATH
            preview?.let { outputs.add(it) }
            outputs.add(enc)
            
            cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        startRepeating(s, preview, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request (Camera already closed?)", e)
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Session config failed")
                }
            }, camHandler)
        } else {
            // GL LUT/AR PATH
            if (preview != null) {
                val cw = captureSize.width
                val ch = captureSize.height
                // encoderRotation = sensor orientation + user manual offset (for front camera)
                // so the PC always receives an upright stream regardless of camera facing.
                glRenderer = GlRenderer(preview, enc, cw, ch, outW, outH, encoderRotation, isFrontCamera) { glInputSurface ->
                    glRenderer?.setFilter(activeLutFilter)
                    glRenderer?.setArFilter(activeArFilter)
                    outputs.add(glInputSurface)
                    cam.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            glRenderer?.attachWindowSurfaces()
                            try {
                                startRepeating(s, glInputSurface, false)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start repeating request", e)
                            }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(TAG, "Session config failed")
                        }
                    }, camHandler)
                }
            }
        }
    }

    @Volatile var isFocusLocked = false
    @Volatile private var isFlashActive = false

    private fun startRepeating(s: CameraCaptureSession, previewOrGlSurface: Surface?, isZeroCopy: Boolean) {
        val b = camDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            previewOrGlSurface?.let { addTarget(it) }
            if (isZeroCopy) {
                encSurface?.let { addTarget(it) }
            }
            imageReader?.surface?.let { addTarget(it) }

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

            if (this@CameraEngine.manualFocusDistance > 0f) {
                set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(LENS_FOCUS_DISTANCE, this@CameraEngine.manualFocusDistance)
            } else if (isFocusLocked) {
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
        if (isFaceTrackingEnabled) {
            camHandler?.post {
                val full = sensorRect ?: return@post
                if (currentCropCenter.x == 0f && currentCropCenter.y == 0f) {
                    currentCropCenter.set(full.centerX().toFloat(), full.centerY().toFloat())
                }
                targetCenterX = currentCropCenter.x
                targetCenterY = currentCropCenter.y
                targetZoom = currentCropZoom
                noFaceDetectedFramesCount = 0
                consecutiveFaceFrames = 0
                isInitialEngage = true
                velX = 0f; velY = 0f; velZoom = 0f
                smoothedFaceHeight = 0f
                lastAppliedCropRect = null
                camHandler?.removeCallbacks(faceTrackingUpdateRunnable)
                camHandler?.post(faceTrackingUpdateRunnable)
            }
        }
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

     fun setFocusDistance(value: Float) {
        val s = session ?: return
        val b = reqBuilder ?: return
        if (value > 0f) {
            val dist = value * maxFocusDistance
            manualFocusDistance = dist
            b.set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(LENS_FOCUS_DISTANCE, dist)
        } else {
            manualFocusDistance = 0f
            b.set(CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        Log.i(TAG, "Manual focus distance updated: $manualFocusDistance (value: $value)")
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
                runCatching {
                    if (info.size <= 0) { c.releaseOutputBuffer(i, false); return }
                    val buf = c.getOutputBuffer(i) ?: run { c.releaseOutputBuffer(i, false); return }
                    val data = ByteArray(info.size)
                    buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(data)
                    c.releaseOutputBuffer(i, false)
                    onEncodedData?.invoke(data, info)
                }
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

    private fun setupImageReader(chars: CameraCharacteristics, width: Int, height: Int) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        
        val targetRatio = width.toFloat() / height.toFloat()
        
        // ML Kit face detection must run on a tiny frame (e.g. 320x180 or 640x360) for performance.
        // However, it MUST have the same aspect ratio as the encoder output, otherwise the Camera HAL
        // will crop the FOV differently for the two streams, causing tracker mapping math to break.
        val matchingAspectSizes = yuvSizes?.filter { 
            Math.abs(it.width.toFloat() / it.height.toFloat() - targetRatio) < 0.1 
        }
        
        // Pick the absolute smallest matching aspect ratio size (usually 640x360 or 320x180)
        val yuvSize = matchingAspectSizes?.minByOrNull { it.width * it.height }
            // If no matching aspect ratio exists (very rare), fallback to the smallest size overall
            ?: yuvSizes?.minByOrNull { it.width * it.height }
            // Fallback if yuvSizes is null
            ?: Size(640, 360)
            
        Log.i(TAG, "ImageReader selected tiny resolution: ${yuvSize.width}x${yuvSize.height} for ML Kit (Output is ${width}x${height})")
        
        imageReader = android.media.ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            android.graphics.ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (isFaceTrackingEnabled && isDetectorBusy.compareAndSet(false, true)) {
                    val rotation = getRotationCompensation()
                    // Capture width/height inside runCatching — image could be invalid if
                    // the ImageReader was closed while this callback was queued
                    val imgW: Int
                    val imgH: Int
                    val inputImage: InputImage
                    try {
                        imgW = image.width
                        imgH = image.height
                        inputImage = InputImage.fromMediaImage(image, rotation)
                    } catch (e: Exception) {
                        Log.w(TAG, "Image invalid before processing: ${e.message}")
                        isDetectorBusy.set(false)
                        runCatching { image.close() }
                        return@setOnImageAvailableListener
                    }
                    getFaceDetector().process(inputImage)
                        .addOnSuccessListener { faces ->
                            camHandler?.post {
                                updateFaceTracking(faces, imgW, imgH, rotation)
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Face detection failed", e)
                        }
                        .addOnCompleteListener {
                            isDetectorBusy.set(false)
                            runCatching { image.close() }
                        }
                } else {
                    runCatching { image.close() }
                }
            }, detectHandler)
        }
        Log.i(TAG, "ImageReader configured for Face Tracking: ${yuvSize.width}x${yuvSize.height}")
    }

    private fun getFaceDetector(): FaceDetector {
        var detector = faceDetector
        if (detector == null) {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.04f) // Allow detecting smaller faces when the user goes far back
                .build()
            detector = FaceDetection.getClient(options)
            faceDetector = detector
        }
        return detector
    }

    private fun getRotationCompensation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
        }.getOrNull()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val deviceRotationDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val chars = runCatching { camMgr.getCameraCharacteristics(camDevice?.id ?: "0") }.getOrNull()
        val facing = chars?.get(CameraCharacteristics.LENS_FACING)
        val isFrontLens = facing == CameraCharacteristics.LENS_FACING_FRONT

        // ML Kit requires the rotation compensation that maps the image to upright.
        // For back camera:  (sensorOrientation - deviceRotation + 360) % 360
        // For front camera: (sensorOrientation + deviceRotation) % 360
        // Both formulas are the standard ML Kit recommendation.
        val rotationCompensation = if (isFrontLens) {
            (sensorOrientation + deviceRotationDegrees) % 360
        } else {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        }
        Log.d(TAG, "getRotationCompensation: isFront=$isFrontLens sensorOri=$sensorOrientation deviceRot=$deviceRotationDegrees compensation=$rotationCompensation")
        return rotationCompensation
    }

    private fun updateFaceTracking(faces: List<Face>, imageWidth: Int, imageHeight: Int, rotation: Int) {
        if (sensorRect == null) return
        
        if (faces.isNotEmpty()) {
            noFaceDetectedFramesCount = 0
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return
            
            // Calculate logical dimensions based on ML Kit image rotation
            val logicalWidth = if (rotation == 90 || rotation == 270) imageHeight else imageWidth
            val logicalHeight = if (rotation == 90 || rotation == 270) imageWidth else imageHeight

            // Extract AR landmarks before 5-frame wait so AR is snappy
            val lw = logicalWidth.toFloat()
            val lh = logicalHeight.toFloat()
            val arData = ArFaceData(
                bounds = RectF(
                    face.boundingBox.left / lw, face.boundingBox.top / lh,
                    face.boundingBox.right / lw, face.boundingBox.bottom / lh
                ),
                leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let { PointF(it.x / lw, it.y / lh) },
                rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let { PointF(it.x / lw, it.y / lh) },
                noseBase = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.let { PointF(it.x / lw, it.y / lh) },
                mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position?.let { PointF(it.x / lw, it.y / lh) },
                leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position?.let { PointF(it.x / lw, it.y / lh) },
                rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position?.let { PointF(it.x / lw, it.y / lh) }
            )
            onArFaceDataUpdated?.invoke(arData)
            glRenderer?.setArFaceData(arData)
            
            consecutiveFaceFrames++
            if (consecutiveFaceFrames < 2) return // wait 2 consecutive frames to filter noise

            updateFaceTracking(face, logicalWidth, logicalHeight, rotation)
        } else {
            onArFaceDataUpdated?.invoke(null)
            glRenderer?.setArFaceData(null)
            
            // Freeze X/Y at last tracked coordinates — never reset to center
            targetCenterX = currentCropCenter.x
            targetCenterY = currentCropCenter.y
            
            noFaceDetectedFramesCount++
            
            // After ~1.5 seconds (45 frames) of no detection, gently lerp zoom toward 1.0x
            // so the wider FOV helps the detector re-acquire the face.
            if (noFaceDetectedFramesCount > 45 && currentCropZoom > 1.05f) {
                targetZoom = targetZoom + (1.0f - targetZoom) * 0.02f
            }
        }
        Log.i("STICAM_TRACK", "updateFaceTracking: faces=${faces.size} target=($targetCenterX, $targetCenterY) zoom=$targetZoom")
    }

    private fun updateFaceTracking(face: Face, logicalWidth: Int, logicalHeight: Int, rotation: Int) {
        val full = sensorRect ?: return
        val box = face.boundingBox

        val faceCenterX = box.centerX().toFloat()
        val faceCenterY = box.centerY().toFloat()

        // Normalize face center to 0.0..1.0 in the rotated (upright) image space.
        // Do NOT apply any mirror flip here — the rotation transform below maps
        // correctly to sensor space regardless of camera facing direction.
        val normX = (faceCenterX / logicalWidth).coerceIn(0f, 1f)
        val normY = (faceCenterY / logicalHeight).coerceIn(0f, 1f)

        // Rotate normalized image-space coordinates back to raw sensor space.
        // These rotation cases undo what ML Kit's rotation compensation did to the image.
        val nsX: Float
        val nsY: Float
        when (rotation) {
            90 -> {
                // Image was rotated 90° CW relative to sensor → undo it
                nsX = normY
                nsY = 1.0f - normX
            }
            180 -> {
                nsX = 1.0f - normX
                nsY = 1.0f - normY
            }
            270 -> {
                // Image was rotated 270° CW (90° CCW) relative to sensor → undo it
                nsX = 1.0f - normY
                nsY = normX
            }
            else -> {
                nsX = normX
                nsY = normY
            }
        }

        // Apply a soft noise gate and EMA filter directly to the raw sensor coordinates (nsX, nsY).
        // If the change in face position is very small (less than 0.008 of sensor width/height), 
        // we scale the EMA alpha towards 0. This completely eliminates ML Kit frame-to-frame noise 
        // and micro-movements, stopping the camera from bouncing/jittering left and right when stationary.
        val posAlpha = if (isInitialEngage) 0.35f else 0.065f
        if (smoothedSensorX < 0.001f && smoothedSensorY < 0.001f) {
            smoothedSensorX = nsX
            smoothedSensorY = nsY
        } else {
            val diffInputX = nsX - smoothedSensorX
            val diffInputY = nsY - smoothedSensorY
            
            val gateX = (Math.abs(diffInputX) / 0.008f).coerceIn(0f, 1f)
            val gateY = (Math.abs(diffInputY) / 0.008f).coerceIn(0f, 1f)
            
            smoothedSensorX = smoothedSensorX + posAlpha * gateX * diffInputX
            smoothedSensorY = smoothedSensorY + posAlpha * gateY * diffInputY
        }

        val cropW = full.width() / currentCropZoom
        val cropH = full.height() / currentCropZoom
        
        // Sensor coordinates matching our current crop boundaries
        val currentLeft = currentCropCenter.x - cropW / 2f
        val currentTop = currentCropCenter.y - cropH / 2f

        // Use the smoothed coordinates for the target calculation.
        // We can entirely eliminate the discrete step-based dead zone because the smoothed input 
        // is 100% continuous, resulting in a beautifully organic panning movement.
        val targetXBeforeCoerce = currentLeft + smoothedSensorX * cropW
        val targetYBeforeCoerce = currentTop + smoothedSensorY * cropH

        // Auto-zoom: Frame the face to maintain the close-up size requested by the user (~58% of frame height)
        // even when moving closer/further or going far behind.
        val targetProportion = 0.58f
        val minZoom = 1.5f
        val rawFaceHeight = box.height().toFloat() / logicalHeight
        val emaAlpha = if (isInitialEngage) 0.4f else 0.1f
        smoothedFaceHeight = if (smoothedFaceHeight < 0.001f) rawFaceHeight
                             else smoothedFaceHeight + emaAlpha * (rawFaceHeight - smoothedFaceHeight)

        val rawTargetZoom = if (smoothedFaceHeight > 0.005f) {
            (targetProportion / smoothedFaceHeight).coerceIn(minZoom, maxZoom)
        } else {
            currentCropZoom.coerceAtLeast(minZoom)
        }
        // Direct assignment to allow responsive zoom target updates. 
        // The smoothing is handled beautifully at 30 FPS by alphaZoom in tickFaceTracking().
        targetZoom = rawTargetZoom

        // Compute crop boundaries and clamp crop center using the calculated targetZoom
        val newCropW = full.width() / targetZoom
        val newCropH = full.height() / targetZoom
        val halfCropW = newCropW / 2f
        val halfCropH = newCropH / 2f

        targetCenterX = targetXBeforeCoerce.coerceIn(full.left + halfCropW, full.right - halfCropW)
        targetCenterY = targetYBeforeCoerce.coerceIn(full.top + halfCropH, full.bottom - halfCropH)
        Log.i(TAG, "TrackMath: FRONT=$isFrontCamera rot=$rotation nsX=$nsX nsY=$nsY smX=$smoothedSensorX smY=$smoothedSensorY tX=$targetCenterX tY=$targetCenterY tZ=$targetZoom rawFH=$rawFaceHeight smoothFH=$smoothedFaceHeight rawTZ=$rawTargetZoom initEngage=$isInitialEngage")
    }

    private fun tickFaceTracking() {
        if (targetZoom.isNaN() || targetZoom.isInfinite() ||
            targetCenterX.isNaN() || targetCenterX.isInfinite() ||
            targetCenterY.isNaN() || targetCenterY.isInfinite()) {
            return
        }

        val full = sensorRect ?: return

        // ── Direct, Sway-Free Smooth interpolation ────────────────────────────
        // We completely remove the physical velocity/friction calculations to eliminate
        // any momentum-based sway, overshoot, or oscillation.
        // Instead, the camera crop coordinates move towards the target center using a direct
        // exponential moving average (EMA) filter. This creates an extremely smooth ease-out 
        // that comes to a complete, instant stop once the target face stops moving.
        //
        val alphaPan = if (isInitialEngage) 0.25f else 0.05f
        val alphaZoom = if (isInitialEngage) 0.20f else 0.04f

        val distToTargetX = Math.abs(currentCropCenter.x - targetCenterX)
        val distToTargetY = Math.abs(currentCropCenter.y - targetCenterY)
        val distToTargetZoom = Math.abs(currentCropZoom - targetZoom)

        if (isInitialEngage && distToTargetZoom < 0.1f && distToTargetX < 100f && distToTargetY < 100f) {
            isInitialEngage = false
        }

        currentCropCenter.x += alphaPan * (targetCenterX - currentCropCenter.x)
        currentCropCenter.y += alphaPan * (targetCenterY - currentCropCenter.y)
        currentCropZoom     += alphaZoom * (targetZoom - currentCropZoom)

        // Clear velocity variables
        velX = 0f
        velY = 0f
        velZoom = 0f

        currentCropZoom = currentCropZoom.coerceIn(1.0f, maxZoom)

        val cropW = (full.width() / currentCropZoom).toInt().coerceIn(1, full.width())
        val cropH = (full.height() / currentCropZoom).toInt().coerceIn(1, full.height())

        val imgRatio = captureSize.width.toFloat() / captureSize.height.toFloat()
        val cropRatio = cropW.toFloat() / cropH.toFloat()

        val visibleW: Float
        val visibleH: Float
        if (imgRatio > cropRatio) {
            visibleW = cropW.toFloat()
            visibleH = cropW.toFloat() / imgRatio
        } else {
            visibleW = cropH.toFloat() * imgRatio
            visibleH = cropH.toFloat()
        }

        // Clamp crop center to respect physical sensor bounds and visible area limits
        val cx = currentCropCenter.x.coerceIn(full.left + visibleW / 2f, full.right - visibleW / 2f)
        val cy = currentCropCenter.y.coerceIn(full.top + visibleH / 2f, full.bottom - visibleH / 2f)

        if (cx != currentCropCenter.x) {
            currentCropCenter.x = cx
        }
        if (cy != currentCropCenter.y) {
            currentCropCenter.y = cy
        }

        val left = (cx - cropW / 2f).toInt().coerceIn(full.left, full.right - cropW)
        val top = (cy - cropH / 2f).toInt().coerceIn(full.top, full.bottom - cropH)
        val right = left + cropW
        val bottom = top + cropH

        val newRect = Rect(left, top, right, bottom)
        if (lastAppliedCropRect == null || lastAppliedCropRect != newRect) {
            Log.d("STICAM_TRACK", "CROP_UPDATE: Res=${captureSize.width}x${captureSize.height}, Sensor=${full.width()}x${full.height()}, Crop=[$left, $top, $right, $bottom] (${cropW}x${cropH}), zoom=$currentCropZoom, maxZ=$maxZoom")
            activeFaceCropRect = newRect
            lastAppliedCropRect = newRect
            
            onFaceZoomChanged?.let { callback ->
                val zoomVal = currentCropZoom
                callback.invoke(zoomVal)
            }

            val s = session ?: return
            val b = reqBuilder ?: return
            applyZoomRect(b)
            runCatching { s.setRepeatingRequest(b.build(), captureCallback, camHandler) }
        }
    }

}
