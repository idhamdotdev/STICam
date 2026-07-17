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
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader

/**
 * Sticam Camera Engine — zero-copy Camera2→MediaCodec H.264 pipeline.
 * Runs in full-auto mode (AE, AF, AWB) with pinch-to-zoom via SCALER_CROP_REGION.
 */
class CameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "SticamEngine"
        private const val DEFAULT_IDR_SEC = 1.0f

        /** Maps mesh filter names to their PNG asset paths */
        val MESH_FILTER_ASSETS = mapOf(
            "TigerPaint" to "filters/tiger_paint.png",
            "Skull" to "filters/skull_paint.png",
            "Ironman" to "filters/ironman_paint.png"
        )
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
            // Route mesh-type filters to FaceMeshRenderer
            val meshAsset = MESH_FILTER_ASSETS[value]
            if (meshAsset != null) {
                glRenderer?.setMeshFilter(value, meshAsset)
            } else {
                glRenderer?.setMeshFilter("None", null)
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
     * Back camera:  SurfaceTexture transform matrix already corrects sensor orientation;
     *               only user manual offset is needed.
     */
    private val baseRotation: Int
        get() = if (isFrontCamera) {
            (360 - sensorOrientation + 180) % 360
        } else {
            (360 - sensorOrientation) % 360
        }

    private val encoderRotation: Int
        get() = (baseRotation + outputRotation) % 360

    // AI Face Tracking
    private var imageReader: ImageReader? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    private val isDetectorBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val currentCropCenter = PointF()
    private var currentCropZoom = 1.0f
    @Volatile private var activeFaceCropRect: Rect? = null
    @Volatile private var lastAppliedCropRect: Rect? = null
    var onFaceZoomChanged: ((zoom: Float) -> Unit)? = null
    var onArFaceDataUpdated: ((ArFaceMeshData?) -> Unit)? = null
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
        faceLandmarkerHelper?.close(); faceLandmarkerHelper = null
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
        glRenderer?.outputRotation = rotationDegrees
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
            
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create capture session (zero-copy)", e)
            }
        } else {
            // GL LUT/AR PATH
            if (preview != null) {
                val cw = captureSize.width
                val ch = captureSize.height
                // encoderRotation = sensor orientation + user manual offset (for front camera)
                // so the PC always receives an upright stream regardless of camera facing.
                glRenderer = GlRenderer(context, preview, enc, cw, ch, outW, outH, encoderRotation, isFrontCamera, sensorOrientation) { glInputSurface ->
                    glRenderer?.outputRotation = outputRotation
                    glRenderer?.setFilter(activeLutFilter)
                    glRenderer?.setArFilter(activeArFilter)
                    outputs.add(glInputSurface)
                    try {
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create capture session (GL path)", e)
                    }
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
        
        // MediaPipe face detection runs on a tiny frame (e.g. 320x180 or 640x360) for performance.
        // It MUST have the same aspect ratio as the encoder output, otherwise the Camera HAL
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
            
        Log.i(TAG, "ImageReader selected tiny resolution: ${yuvSize.width}x${yuvSize.height} for MediaPipe (Output is ${width}x${height})")

        // Initialize MediaPipe Face Landmarker
        if (faceLandmarkerHelper == null) {
            faceLandmarkerHelper = FaceLandmarkerHelper(context).also { it.initialize(useGpuDelegate = false) }
        }
        
        imageReader = android.media.ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            android.graphics.ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if ((isFaceTrackingEnabled || activeArFilter != "None") && isDetectorBusy.compareAndSet(false, true)) {
                    // Convert YUV Image to Bitmap for MediaPipe
                    val bitmap: Bitmap
                    try {
                        bitmap = yuvImageToBitmap(image)
                    } catch (e: Exception) {
                        Log.w(TAG, "Image conversion failed: ${e.message}")
                        isDetectorBusy.set(false)
                        runCatching { image.close() }
                        return@setOnImageAvailableListener
                    }
                    runCatching { image.close() }

                    // Run MediaPipe detection synchronously on the detect thread
                    val meshData = faceLandmarkerHelper?.detectBitmap(bitmap)
                    bitmap.recycle()

                    camHandler?.post {
                        processFaceResult(meshData)
                    }
                    isDetectorBusy.set(false)
                } else {
                    runCatching { image.close() }
                }
            }, detectHandler)
        }
        Log.i(TAG, "ImageReader configured for Face Tracking: ${yuvSize.width}x${yuvSize.height}")
    }

    /**
     * Convert a YUV_420_888 Image to an ARGB_8888 Bitmap for MediaPipe.
     * This replaces ML Kit's InputImage.fromMediaImage() which handled this internally.
     */
    private fun yuvImageToBitmap(image: android.media.Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * Process a MediaPipe face result — emit AR data and update face tracking crop.
     * Replaces the old updateFaceTracking(faces: List<Face>, ...) overload.
     */
    private fun processFaceResult(meshData: ArFaceMeshData?) {
        if (sensorRect == null) return
        
        if (meshData != null) {
            noFaceDetectedFramesCount = 0

            // Emit AR data immediately so AR overlays are snappy
            onArFaceDataUpdated?.invoke(meshData)
            glRenderer?.setArFaceData(meshData)
            
            consecutiveFaceFrames++
            if (consecutiveFaceFrames < 2) return // wait 2 consecutive frames to filter noise

            updateFaceTrackingFromMesh(meshData)
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
        Log.i("STICAM_TRACK", "processFaceResult: detected=${meshData != null} target=($targetCenterX, $targetCenterY) zoom=$targetZoom")
    }

    /**
     * Update face tracking crop/zoom from MediaPipe face mesh data.
     * Replaces the old updateFaceTracking(face: Face, ...) overload.
     * MediaPipe landmarks are already normalized 0..1 — no rotation compensation needed.
     */
    private fun updateFaceTrackingFromMesh(meshData: ArFaceMeshData) {
        val full = sensorRect ?: return
        val bounds = meshData.bounds

        val faceCenterX = bounds.centerX()
        val faceCenterY = bounds.centerY()

        // MediaPipe landmarks are already in normalized 0..1 screen-space.
        // Map directly to sensor space using the sensor orientation.
        val nsX: Float
        val nsY: Float
        when (sensorOrientation) {
            90 -> {
                nsX = faceCenterY
                nsY = 1.0f - faceCenterX
            }
            180 -> {
                nsX = 1.0f - faceCenterX
                nsY = 1.0f - faceCenterY
            }
            270 -> {
                nsX = 1.0f - faceCenterY
                nsY = faceCenterX
            }
            else -> {
                nsX = faceCenterX
                nsY = faceCenterY
            }
        }

        // Apply a soft noise gate and EMA filter directly to the raw sensor coordinates (nsX, nsY).
        // If the change in face position is very small (less than 0.008 of sensor width/height), 
        // we scale the EMA alpha towards 0. This completely eliminates frame-to-frame noise 
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
        val rawFaceHeight = bounds.height()
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
        Log.i(TAG, "TrackMath: FRONT=$isFrontCamera nsX=$nsX nsY=$nsY smX=$smoothedSensorX smY=$smoothedSensorY tX=$targetCenterX tY=$targetCenterY tZ=$targetZoom rawFH=$rawFaceHeight smoothFH=$smoothedFaceHeight rawTZ=$rawTargetZoom initEngage=$isInitialEngage")
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
