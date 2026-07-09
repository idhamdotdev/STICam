package com.sticam.engine.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object LutGenerator {
    const val LUT_SIZE = 33

    fun generateLut(filterName: String): FloatBuffer {
        val numElements = LUT_SIZE * LUT_SIZE * LUT_SIZE * 3
        val buffer = ByteBuffer.allocateDirect(numElements * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (b in 0 until LUT_SIZE) {
            val fb = b / (LUT_SIZE - 1).toFloat()
            for (g in 0 until LUT_SIZE) {
                val fg = g / (LUT_SIZE - 1).toFloat()
                for (r in 0 until LUT_SIZE) {
                    val fr = r / (LUT_SIZE - 1).toFloat()

                    var outR = fr
                    var outG = fg
                    var outB = fb

                    when (filterName) {
                        "Warm" -> {
                            outR = min(1.0f, fr * 1.15f)
                            outB = max(0.0f, fb * 0.85f)
                        }
                        "Cool" -> {
                            outR = max(0.0f, fr * 0.85f)
                            outB = min(1.0f, fb * 1.15f)
                        }
                        "Grayscale" -> {
                            val lum = 0.299f * fr + 0.587f * fg + 0.114f * fb
                            outR = lum; outG = lum; outB = lum
                        }
                        "Vivid" -> {
                            // Increase contrast
                            outR = if (fr > 0.5f) min(1.0f, fr + (fr - 0.5f) * 0.5f) else max(0.0f, fr - (0.5f - fr) * 0.5f)
                            outG = if (fg > 0.5f) min(1.0f, fg + (fg - 0.5f) * 0.5f) else max(0.0f, fg - (0.5f - fg) * 0.5f)
                            outB = if (fb > 0.5f) min(1.0f, fb + (fb - 0.5f) * 0.5f) else max(0.0f, fb - (0.5f - fb) * 0.5f)
                            // Increase saturation
                            val lum = 0.299f * outR + 0.587f * outG + 0.114f * outB
                            outR = lum + (outR - lum) * 1.3f
                            outG = lum + (outG - lum) * 1.3f
                            outB = lum + (outB - lum) * 1.3f
                            outR = outR.coerceIn(0f, 1f)
                            outG = outG.coerceIn(0f, 1f)
                            outB = outB.coerceIn(0f, 1f)
                        }
                        "Cinematic" -> {
                            // Orange & Teal
                            val lum = 0.299f * fr + 0.587f * fg + 0.114f * fb
                            // Shadows to Teal (0.1, 0.4, 0.6), Highlights to Orange (0.8, 0.5, 0.2)
                            if (lum < 0.5f) {
                                val intensity = (0.5f - lum) * 2f
                                outR = max(0f, outR - intensity * 0.1f)
                                outG = min(1f, outG + intensity * 0.1f)
                                outB = min(1f, outB + intensity * 0.2f)
                            } else {
                                val intensity = (lum - 0.5f) * 2f
                                outR = min(1f, outR + intensity * 0.2f)
                                outG = min(1f, outG + intensity * 0.05f)
                                outB = max(0f, outB - intensity * 0.15f)
                            }
                            // Boost contrast
                            outR = outR.pow(1.2f).coerceIn(0f, 1f)
                            outG = outG.pow(1.2f).coerceIn(0f, 1f)
                            outB = outB.pow(1.2f).coerceIn(0f, 1f)
                        }
                    }

                    buffer.put(outR)
                    buffer.put(outG)
                    buffer.put(outB)
                }
            }
        }
        buffer.position(0)
        return buffer
    }
}
