package com.sticam.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Thin wrapper around MediaPipe Face Landmarker.
 * Replaces ML Kit FaceDetection with 468 3D landmark tracking + blendshapes.
 *
 * Usage:
 * 1. Call [initialize] once from any thread.
 * 2. Feed Bitmap frames via [detectBitmap] on the detection thread.
 * 3. Read the returned [ArFaceMeshData] or null (no face detected).
 * 4. Call [close] when done.
 */
class FaceLandmarkerHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceLandmarkerHelper"
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val NUM_FACES = 1
        private const val MIN_FACE_DETECTION_CONFIDENCE = 0.5f
        private const val MIN_FACE_PRESENCE_CONFIDENCE = 0.5f
        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }

    private var faceLandmarker: FaceLandmarker? = null

    /**
     * Initialize the Face Landmarker. Call once before using [detectBitmap].
     * This loads the model file from assets and configures the pipeline.
     *
     * @param useGpuDelegate true to use GPU acceleration (faster but not supported on all devices)
     */
    fun initialize(useGpuDelegate: Boolean = false) {
        try {
            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)

            if (useGpuDelegate) {
                baseOptionsBuilder.setDelegate(Delegate.GPU)
            } else {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
            }

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(NUM_FACES)
                .setMinFaceDetectionConfidence(MIN_FACE_DETECTION_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_FACE_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "Face Landmarker initialized (GPU=$useGpuDelegate)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Face Landmarker, retrying on CPU", e)
            if (useGpuDelegate) {
                // Fall back to CPU if GPU fails
                initialize(useGpuDelegate = false)
            }
        }
    }

    /**
     * Run face landmark detection on a Bitmap frame.
     * Call this from the detection thread — it blocks until inference completes.
     *
     * @param bitmap The camera frame as an ARGB_8888 Bitmap
     * @return [ArFaceMeshData] if a face was detected, null otherwise
     */
    fun detectBitmap(bitmap: Bitmap): ArFaceMeshData? {
        val landmarker = faceLandmarker ?: return null

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: FaceLandmarkerResult = try {
            landmarker.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            return null
        }

        if (result.faceLandmarks().isEmpty()) return null

        return convertResult(result)
    }

    /**
     * Convert a MediaPipe result into our [ArFaceMeshData] structure.
     * Takes the first (largest) detected face only.
     */
    private fun convertResult(result: FaceLandmarkerResult): ArFaceMeshData? {
        val faceLandmarks = result.faceLandmarks().firstOrNull() ?: return null

        // Convert normalized landmarks to PointF3D list
        val landmarks = faceLandmarks.map { lm ->
            PointF3D(lm.x(), lm.y(), lm.z())
        }

        // Compute bounding box from all landmarks
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (lm in landmarks) {
            if (lm.x < minX) minX = lm.x
            if (lm.y < minY) minY = lm.y
            if (lm.x > maxX) maxX = lm.x
            if (lm.y > maxY) maxY = lm.y
        }
        val bounds = RectF(minX, minY, maxX, maxY)

        // Extract blendshapes (52 expression coefficients)
        val blendshapes: Map<String, Float>? = if (result.faceBlendshapes().isPresent &&
            result.faceBlendshapes().get().isNotEmpty()
        ) {
            val categories = result.faceBlendshapes().get()[0]
            categories.associate { cat -> cat.categoryName() to cat.score() }
        } else {
            null
        }

        // Extract face transformation matrix (4x4 float[])
        val faceMatrix: FloatArray? = if (result.facialTransformationMatrixes().isPresent &&
            result.facialTransformationMatrixes().get().isNotEmpty()
        ) {
            result.facialTransformationMatrixes().get()[0]
        } else {
            null
        }

        return ArFaceMeshData(
            landmarks = landmarks,
            bounds = bounds,
            blendshapes = blendshapes,
            faceMatrix = faceMatrix,
        )
    }

    /**
     * Release all resources. Call when the camera engine stops.
     */
    fun close() {
        try {
            faceLandmarker?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Face Landmarker", e)
        }
        faceLandmarker = null
        Log.i(TAG, "Face Landmarker closed")
    }
}
