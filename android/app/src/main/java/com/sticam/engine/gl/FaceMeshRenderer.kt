package com.sticam.engine.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.sticam.engine.ArFaceMeshData
import com.sticam.engine.PointF3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GPU-accelerated face mesh renderer.
 * Takes 468 MediaPipe landmarks + a texture and renders the textured mesh
 * over the camera feed using alpha blending.
 *
 * Call [init] once on the GL thread, then [draw] every frame with fresh landmark data.
 */
class FaceMeshRenderer {

    companion object {
        private const val TAG = "FaceMeshRenderer"
        private const val COORDS_PER_VERTEX = 3  // x, y, z
        private const val UV_PER_VERTEX = 2       // u, v
        private const val VERTEX_STRIDE = (COORDS_PER_VERTEX + UV_PER_VERTEX) * 4 // 20 bytes
        private const val MAX_LANDMARKS = 468
    }

    private var program = -1
    private var aPositionHandle = -1
    private var aTexCoordHandle = -1
    private var uTextureHandle = -1
    private var uOpacityHandle = -1

    private var textureId = -1
    private var isInitialized = false

    // Pre-allocated buffers (reused every frame to avoid GC)
    private val vertexData = FloatArray(MAX_LANDMARKS * (COORDS_PER_VERTEX + UV_PER_VERTEX))
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(FaceMeshData.TRIANGLE_INDICES.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(FaceMeshData.TRIANGLE_INDICES)
            position(0)
        }

    private val VERTEX_SHADER = """
        #version 300 es
        in vec3 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            // Convert normalized 0..1 coordinates to OpenGL clip space -1..1
            // MediaPipe: (0,0) = top-left, (1,1) = bottom-right
            // OpenGL:    (-1,1) = top-left, (1,-1) = bottom-right
            float glX = aPosition.x * 2.0 - 1.0;
            float glY = 1.0 - aPosition.y * 2.0;
            gl_Position = vec4(glX, glY, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uOpacity;
        out vec4 outColor;
        void main() {
            vec4 texColor = texture(uTexture, vTexCoord);
            outColor = vec4(texColor.rgb, texColor.a * uOpacity);
        }
    """.trimIndent()

    /**
     * Initialize GL resources. Must be called on the GL thread.
     */
    fun init() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program <= 0) {
            Log.e(TAG, "Failed to create face mesh shader program")
            return
        }

        aPositionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTextureHandle = GLES30.glGetUniformLocation(program, "uTexture")
        uOpacityHandle = GLES30.glGetUniformLocation(program, "uOpacity")

        // Create texture
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        textureId = texIds[0]

        isInitialized = true
        Log.i(TAG, "Face mesh renderer initialized")
    }

    /**
     * Load a face texture (face paint, mask, etc.) from a Bitmap.
     * The bitmap should be UV-mapped to the canonical face mesh coordinates.
     */
    fun loadTexture(bitmap: Bitmap) {
        if (!isInitialized) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        Log.i(TAG, "Face mesh texture loaded: ${bitmap.width}x${bitmap.height}")
    }

    /**
     * Draw the face mesh with the loaded texture over the current framebuffer.
     *
     * @param meshData MediaPipe face landmarks (468 points, normalized 0..1)
     * @param opacity  Blend opacity (0.0 = invisible, 1.0 = fully opaque)
     * @param isFrontCamera true if front camera (needs horizontal flip)
     */
    fun draw(meshData: ArFaceMeshData, opacity: Float = 0.85f, isFrontCamera: Boolean = true) {
        if (!isInitialized || textureId <= 0) return
        val landmarks = meshData.landmarks
        if (landmarks.size < MAX_LANDMARKS) return

        // Fill interleaved vertex buffer: [x, y, z, u, v] per vertex
        // MediaPipe landmarks are already normalized 0..1
        // UV coordinates = landmark positions (canonical face mesh maps texture directly)
        for (i in 0 until MAX_LANDMARKS) {
            val lm = landmarks[i]
            val offset = i * 5
            // Position — flip X for front camera mirror
            vertexData[offset] = if (isFrontCamera) 1.0f - lm.x else lm.x
            vertexData[offset + 1] = lm.y
            vertexData[offset + 2] = lm.z
            // UV coordinates — use canonical static UVs for stable texture mapping
            vertexData[offset + 3] = FaceMeshData.CANONICAL_UV[i * 2]
            vertexData[offset + 4] = FaceMeshData.CANONICAL_UV[i * 2 + 1]
        }

        vertexBuffer.position(0)
        vertexBuffer.put(vertexData)
        vertexBuffer.position(0)

        // Draw
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Bind texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uTextureHandle, 0)
        GLES30.glUniform1f(uOpacityHandle, opacity)

        // Position attribute (offset 0)
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPositionHandle, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPositionHandle)

        // UV attribute (offset 3 floats)
        vertexBuffer.position(COORDS_PER_VERTEX)
        GLES30.glVertexAttribPointer(aTexCoordHandle, UV_PER_VERTEX, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexCoordHandle)

        // Draw triangles
        indexBuffer.position(0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, FaceMeshData.TRIANGLE_INDICES.size, GLES30.GL_UNSIGNED_SHORT, indexBuffer)

        // Cleanup
        GLES30.glDisableVertexAttribArray(aPositionHandle)
        GLES30.glDisableVertexAttribArray(aTexCoordHandle)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * Release GL resources.
     */
    fun release() {
        if (textureId > 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = -1
        }
        if (program > 0) {
            GLES30.glDeleteProgram(program)
            program = -1
        }
        isInitialized = false
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vs == 0) return 0
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fs == 0) return 0

        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)

        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }

        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
