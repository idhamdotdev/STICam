package com.sticam.engine.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object GlArHelper {
    fun createEmojiBitmap(filterName: String): Bitmap {
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
}
