package com.sticam.ui.components

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.sticam.ui.SticamUiState

@OptIn(ExperimentalTextApi::class)
@Composable
fun ArOverlay(state: SticamUiState) {
    val arData = state.arFaceData ?: return
    val filter = state.activeArFilter
    if (filter == "None") return
    
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Helper to map normalized coordinates to Canvas pixels
        fun map(pt: PointF?): Offset? {
            if (pt == null) return null
            return Offset(pt.x * w, pt.y * h)
        }
        
        val bounds = RectF(
            arData.bounds.left * w, arData.bounds.top * h,
            arData.bounds.right * w, arData.bounds.bottom * h
        )
        
        val faceWidth = bounds.width()
        
        val leftEye = map(arData.leftEye)
        val rightEye = map(arData.rightEye)
        val nose = map(arData.noseBase)
        val mouth = map(arData.mouthBottom)
        val leftCheek = map(arData.leftCheek)
        val rightCheek = map(arData.rightCheek)

        fun drawEmoji(text: String, center: Offset, sizeFactor: Float = 1f) {
            val fontSize = with(density) { (faceWidth * sizeFactor).toSp() }
            val textLayoutResult = textMeasurer.measure(
                text = text,
                style = TextStyle(fontSize = fontSize)
            )
            val textSize = textLayoutResult.size
            val topLeftOffset = Offset(center.x - textSize.width / 2f, center.y - textSize.height / 2f)
            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = topLeftOffset
            )
        }

        when (filter) {
            "Crown" -> {
                val center = Offset(bounds.centerX(), bounds.top - faceWidth * 0.2f)
                drawEmoji("👑", center, 0.8f)
            }
            "England" -> {
                val center = Offset(bounds.centerX(), bounds.top - faceWidth * 0.45f)
                drawEmoji("🏴󠁧󠁢󠁥󠁮󠁧󠁿", center, 0.6f)
            }
        }
    }
}
