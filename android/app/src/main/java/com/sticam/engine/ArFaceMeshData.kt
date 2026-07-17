package com.sticam.engine

import android.graphics.PointF
import android.graphics.RectF

/**
 * 3D point from MediaPipe Face Landmarker.
 * x, y are normalized 0..1 screen coordinates; z is relative depth.
 */
data class PointF3D(val x: Float, val y: Float, val z: Float)

/**
 * Full face mesh data from MediaPipe Face Landmarker.
 * Contains 468 3D landmarks, blendshapes, and face transformation matrix.
 *
 * Also provides convenience accessors for backward-compatible landmark positions
 * (leftEye, rightEye, noseBase, mouthBottom) used by the existing billboard AR system.
 */
data class ArFaceMeshData(
    // ── Full 468-point mesh (normalized 0..1 coordinates) ─────────────────────
    val landmarks: List<PointF3D>,

    // ── Bounding box (normalized 0..1) ────────────────────────────────────────
    val bounds: RectF,

    // ── Blendshapes (52 expression coefficients, 0.0-1.0) ─────────────────────
    val blendshapes: Map<String, Float>?,

    // ── Face transformation matrix (for 3D object placement) ──────────────────
    val faceMatrix: FloatArray?,
) {
    // ── Backward-compatible convenience landmarks ─────────────────────────────
    // MediaPipe canonical landmark indices:
    //   Left eye:   33 (right iris center from camera perspective)
    //   Right eye:  263 (left iris center from camera perspective)
    //   Nose tip:   1
    //   Mouth bottom: 17 (lower lip center)
    //   Left cheek:  234
    //   Right cheek: 454

    val leftEye: PointF?
        get() = landmarks.getOrNull(33)?.let { PointF(it.x, it.y) }

    val rightEye: PointF?
        get() = landmarks.getOrNull(263)?.let { PointF(it.x, it.y) }

    val noseBase: PointF?
        get() = landmarks.getOrNull(1)?.let { PointF(it.x, it.y) }

    val mouthBottom: PointF?
        get() = landmarks.getOrNull(17)?.let { PointF(it.x, it.y) }

    val leftCheek: PointF?
        get() = landmarks.getOrNull(234)?.let { PointF(it.x, it.y) }

    val rightCheek: PointF?
        get() = landmarks.getOrNull(454)?.let { PointF(it.x, it.y) }
}
