package com.sticam.engine.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object GlArHelper {
    fun createEmojiBitmap(filterName: String): Bitmap {
        if (filterName == "England") return createEnglandFlagBitmap()

        val emoji = when (filterName) {
            "Crown" -> "👑"
            else -> "✨"
        }
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            textSize = 380f
            textAlign = Paint.Align.CENTER
        }
        val x = size / 2f
        val y = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(emoji, x, y, paint)
        return bitmap
    }

    /**
     * Draws the St George's Cross (England flag) programmatically.
     * White background with a red cross — no emoji dependency.
     */
    private fun createEnglandFlagBitmap(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // White background
        val whitePaint = Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), whitePaint)

        // Red cross
        val redPaint = Paint().apply { color = android.graphics.Color.rgb(206, 17, 38) }
        val crossThickness = size / 5f
        val half = size / 2f
        // Horizontal bar
        canvas.drawRect(0f, half - crossThickness / 2f, size.toFloat(), half + crossThickness / 2f, redPaint)
        // Vertical bar
        canvas.drawRect(half - crossThickness / 2f, 0f, half + crossThickness / 2f, size.toFloat(), redPaint)

        return bitmap
    }
}
