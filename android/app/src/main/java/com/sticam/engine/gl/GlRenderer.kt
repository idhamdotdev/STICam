package com.sticam.engine.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.util.Log
import android.graphics.PointF
import com.sticam.engine.ArFaceMeshData

class GlRenderer(
    private val context: Context,
    private val previewSurface: Surface,
    private val encoderSurface: Surface,
    private val cameraWidth: Int,
    private val cameraHeight: Int,
    private val outW: Int,
    private val outH: Int,
    initialRotationDegrees: Int = 0,
    private val isFrontCamera: Boolean = false,
    private val sensorOrientation: Int = 90,
    private val onInputSurfaceReady: (Surface) -> Unit
) {
    @Volatile var rotationDegrees = initialRotationDegrees
    @Volatile var outputRotation: Int = 0
    private val baseRotation: Int
        get() = if (isFrontCamera) {
            (360 - sensorOrientation + 180) % 360
        } else {
            (360 - sensorOrientation) % 360
        }
    private var handlerThread = HandlerThread("GlRendererThread")
    private var handler: Handler
    
    private var eglManager: EglManager? = null
    private var eglPreviewSurface: android.opengl.EGLSurface? = null
    private var eglEncoderSurface: android.opengl.EGLSurface? = null
    
    private var oesTextureId = -1
    private var lutTextureId = -1
    private var arTextureId = -1
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    
    private var program = -1
    private var uSTMatrixHandle = -1
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1
    private var sTextureHandle = -1
    private var sLutHandle = -1

    private var arProgram = -1
    private var arPositionHandle = -1
    private var arTextureCoordHandle = -1
    private var sArTextureHandle = -1

    // Morph and beauty shader uniform locations
    private var uMorphTypeHandle = -1
    private var uLeftEyeHandle = -1
    private var uRightEyeHandle = -1
    private var uFaceCenterHandle = -1
    private var uBeautyLevelHandle = -1
    private var uTexelWidthHandle = -1
    private var uTexelHeightHandle = -1
    
    private val transformMatrix = FloatArray(16)
    
    private var activeArFilter: String = "None"
    @Volatile private var currentArFaceData: ArFaceMeshData? = null
    
    // Face mesh renderer for advanced AR (face paint, masks)
    private var faceMeshRenderer: FaceMeshRenderer? = null
    private var activeMeshFilter: String = "None"
    
    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        
        // Morph controls
        uniform int uMorphType; // 0 = None, 1 = Big Eyes, 2 = Slim Face
        uniform vec2 uLeftEye;   // normalized left eye center
        uniform vec2 uRightEye;  // normalized right eye center
        uniform vec2 uFaceCenter;// normalized face center
        
        // Beauty controls
        uniform float uBeautyLevel; // 0.0 to 1.0
        uniform float uTexelWidth;  // 1.0 / outW
        uniform float uTexelHeight; // 1.0 / outH

        // Helper for Big Eyes (bulge)
        vec2 bulge(vec2 uv, vec2 center, float radius, float strength) {
            vec2 d = uv - center;
            float dist = length(d);
            if (dist < radius) {
                float percent = dist / radius;
                float scale = 1.0 - strength * (1.0 - percent * percent);
                return center + d * scale;
            }
            return uv;
        }

        // Helper for Slim Face (squeeze)
        vec2 slim(vec2 uv, vec2 center, float radius, float strength) {
            vec2 d = uv - center;
            float dist = length(d);
            if (dist < radius) {
                float percent = dist / radius;
                if (d.y > -0.05) {
                    float factor = strength * (1.0 - percent * percent);
                    vec2 res = uv;
                    res.x = center.x + d.x * (1.0 + factor);
                    return res;
                }
            }
            return uv;
        }

        void main() {
            vec2 uv = vTextureCoord;
            
            // 1. Apply Morphs
            if (uMorphType == 1) { // Big Eyes
                uv = bulge(uv, uLeftEye, 0.08, 0.25);
                uv = bulge(uv, uRightEye, 0.08, 0.25);
            } else if (uMorphType == 2) { // Slim Face
                uv = slim(uv, uFaceCenter, 0.25, 0.18);
            }
            
            // 2. Apply Beauty (Skin Smoothing)
            if (uBeautyLevel > 0.0) {
                vec4 color = texture2D(sTexture, uv);
                vec3 sum = color.rgb;
                float totalWeight = 1.0;
                
                // 8-sample bilateral approximation
                float stepX = uTexelWidth * (1.0 + uBeautyLevel * 1.5);
                float stepY = uTexelHeight * (1.0 + uBeautyLevel * 1.5);
                
                vec2 offsets[8];
                offsets[0] = vec2(-stepX, 0.0);
                offsets[1] = vec2(stepX, 0.0);
                offsets[2] = vec2(0.0, -stepY);
                offsets[3] = vec2(0.0, stepY);
                offsets[4] = vec2(-stepX * 0.7, -stepY * 0.7);
                offsets[5] = vec2(stepX * 0.7, -stepY * 0.7);
                offsets[6] = vec2(-stepX * 0.7, stepY * 0.7);
                offsets[7] = vec2(stepX * 0.7, stepY * 0.7);
                
                float th = 0.12 + (1.0 - uBeautyLevel) * 0.08;
                
                for (int i = 0; i < 8; i++) {
                    vec2 sampleUv = uv + offsets[i];
                    vec3 c = texture2D(sTexture, sampleUv).rgb;
                    float diff = distance(c, color.rgb);
                    if (diff < th) {
                        float w = 1.0 - (diff / th);
                        sum += c * w;
                        totalWeight += w;
                    }
                }
                gl_FragColor = vec4(sum / totalWeight, color.a);
            } else {
                gl_FragColor = texture2D(sTexture, uv);
            }
        }
    """.trimIndent()

    private val VERTEX_SHADER_300 = """
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            // We use our own rotation matrix (uSTMatrix) to bypass Android's squashing
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val AR_VERTEX_SHADER = """
        #version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        out vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord.xy;
        }
    """.trimIndent()

    private val AR_FRAGMENT_SHADER = """
        #version 300 es
        precision mediump float;
        in vec2 vTextureCoord;
        uniform sampler2D sTexture;
        out vec4 outColor;
        void main() {
            outColor = texture(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val vertexData = floatArrayOf(
        -1.0f, -1.0f, 0f, 0f, 0f,
         1.0f, -1.0f, 0f, 1f, 0f,
        -1.0f,  1.0f, 0f, 0f, 1f,
         1.0f,  1.0f, 0f, 1f, 1f
    )
    private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(vertexData)
            position(0)
        }

    // AR Quad will be generated dynamically
    private val arVertexData = FloatArray(20)
    private val arVertexBuffer = java.nio.ByteBuffer.allocateDirect(arVertexData.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var pbufferSurface: android.opengl.EGLSurface? = null
    
    init {
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        handler.post { setupGl() }
    }
    
    fun attachWindowSurfaces() {
        handler.post {
            try {
                eglPreviewSurface = eglManager!!.createWindowSurface(previewSurface)
                eglEncoderSurface = eglManager!!.createWindowSurface(encoderSurface)
            } catch (e: Exception) {
                Log.e("GlRenderer", "Failed to attach window surfaces", e)
            }
        }
    }
    
    private fun setupGl() {
        eglManager = EglManager()
        // Initialize with a dummy Pbuffer so we don't lock the camera surfaces prematurely
        pbufferSurface = eglManager!!.createPbufferSurface(1, 1)
        eglManager!!.makeCurrent(pbufferSurface!!)
        
        program = createProgram(VERTEX_SHADER_300, FRAGMENT_SHADER)
        if (program == 0) throw RuntimeException("Failed to create program")
        
        aPositionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES30.glGetAttribLocation(program, "aTextureCoord")
        uSTMatrixHandle = GLES30.glGetUniformLocation(program, "uSTMatrix")
        sTextureHandle = GLES30.glGetUniformLocation(program, "sTexture")
        sLutHandle = GLES30.glGetUniformLocation(program, "sLut")

        uMorphTypeHandle = GLES30.glGetUniformLocation(program, "uMorphType")
        uLeftEyeHandle = GLES30.glGetUniformLocation(program, "uLeftEye")
        uRightEyeHandle = GLES30.glGetUniformLocation(program, "uRightEye")
        uFaceCenterHandle = GLES30.glGetUniformLocation(program, "uFaceCenter")
        uBeautyLevelHandle = GLES30.glGetUniformLocation(program, "uBeautyLevel")
        uTexelWidthHandle = GLES30.glGetUniformLocation(program, "uTexelWidth")
        uTexelHeightHandle = GLES30.glGetUniformLocation(program, "uTexelHeight")

        arProgram = createProgram(AR_VERTEX_SHADER, AR_FRAGMENT_SHADER)
        arPositionHandle = GLES30.glGetAttribLocation(arProgram, "aPosition")
        arTextureCoordHandle = GLES30.glGetAttribLocation(arProgram, "aTextureCoord")
        sArTextureHandle = GLES30.glGetUniformLocation(arProgram, "sTexture")

        // Initialize face mesh renderer for advanced AR
        faceMeshRenderer = FaceMeshRenderer().also { it.init() }
        
        val textures = IntArray(3)
        GLES30.glGenTextures(3, textures, 0)
        oesTextureId = textures[0]
        lutTextureId = textures[1]
        arTextureId = textures[2]
        
        // Setup OES Texture
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR.toFloat())
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        
        // Initial LUT
        setFilter("None")
        
        surfaceTexture = SurfaceTexture(oesTextureId)
        // Set the buffer size to match the raw camera capture size precisely.
        // We bypass stMatrix in the shader, so SurfaceTexture's scaling won't distort the image.
        surfaceTexture!!.setDefaultBufferSize(cameraWidth, cameraHeight)
        surfaceTexture!!.setOnFrameAvailableListener({
            handler.post { drawFrame() }
        }, handler)
        
        inputSurface = Surface(surfaceTexture)
        onInputSurfaceReady(inputSurface!!)
    }
    
    fun setFilter(filterName: String) {
        handler.post {
            val lutBuffer = LutGenerator.generateLut(filterName)
            
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
            
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                LutGenerator.LUT_SIZE, LutGenerator.LUT_SIZE, LutGenerator.LUT_SIZE,
                0, GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuffer
            )
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        }
    }

    fun setArFilter(filterName: String) {
        activeArFilter = filterName
        if (filterName == "None") return
        
        handler.post {
            try {
                val bitmap = GlArHelper.createEmojiBitmap(filterName)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arTextureId)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                bitmap.recycle()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            } catch (e: Exception) {
                Log.e("GlRenderer", "Failed to load AR asset for filter $filterName", e)
                activeArFilter = "None"
            }
        }
    }

    fun setArFaceData(data: ArFaceMeshData?) {
        currentArFaceData = data
    }
    
    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val egl = eglManager ?: return
        
        st.updateTexImage()
        st.getTransformMatrix(transformMatrix)
        val timestamp = st.timestamp
        
        // Draw to preview — apply baseRotation to render upright on phone screen preview surface
        eglPreviewSurface?.let {
            egl.makeCurrent(it)
            drawTexture(baseRotation)
            drawArTexture()
            drawFaceMesh()
            egl.swapBuffers(it)
        }
        
        // Draw to encoder — apply rotation so the PC receives correctly oriented frames
        eglEncoderSurface?.let {
            egl.makeCurrent(it)
            drawTexture(rotationDegrees)
            drawArTexture()
            drawFaceMesh()
            egl.setPresentationTime(it, timestamp)
            egl.swapBuffers(it)
        }
    }
    
    private fun drawTexture(rotation: Int) {
        // For front camera the rotation corrects the sensor orientation — the output
        // is still landscape, so always use the full viewport (no pillarboxing).
        // For back camera a 90/270 rotation means the user chose a portrait stream,
        // so pillarbox it inside the landscape encoder buffer.
        if (!isFrontCamera && (outputRotation == 90 || outputRotation == 270)) {
            val w = (outH * outH) / outW
            val x = (outW - w) / 2
            GLES30.glViewport(x, 0, w, outH)
        } else {
            GLES30.glViewport(0, 0, outW, outH)
        }
        
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        
        GLES30.glUseProgram(program)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(sTextureHandle, 0)
        
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPositionHandle, 3, GLES30.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPositionHandle)
        
        vertexBuffer.position(3)
        GLES30.glVertexAttribPointer(aTextureCoordHandle, 2, GLES30.GL_FLOAT, false, 5 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTextureCoordHandle)
        
        // Rotate the texture coordinates around the center to keep it upright and non-stretched
        val texMatrix = FloatArray(16)
        System.arraycopy(transformMatrix, 0, texMatrix, 0, 16)
        if (rotation != 0) {
            android.opengl.Matrix.translateM(texMatrix, 0, 0.5f, 0.5f, 0f)
            android.opengl.Matrix.rotateM(texMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
            android.opengl.Matrix.translateM(texMatrix, 0, -0.5f, -0.5f, 0f)
        }
        GLES30.glUniformMatrix4fv(uSTMatrixHandle, 1, false, texMatrix, 0)
        
        // Set morph and beauty uniforms
        val face = currentArFaceData
        var morphType = 0
        var leftEyeX = 0f; var leftEyeY = 0f
        var rightEyeX = 0f; var rightEyeY = 0f
        var faceCenterX = 0f; var faceCenterY = 0f
        var beautyLevel = 0f

        if (activeArFilter == "Big Eyes") {
            morphType = 1
        } else if (activeArFilter == "Slim Face") {
            morphType = 2
        } else if (activeArFilter == "Smooth") {
            beautyLevel = 0.85f
        }

        if (face != null) {
            val le = face.leftEye
            val re = face.rightEye
            val nose = face.noseBase
            if (le != null) {
                val tLe = transformPoint(le, texMatrix)
                leftEyeX = tLe.x
                leftEyeY = tLe.y
            }
            if (re != null) {
                val tRe = transformPoint(re, texMatrix)
                rightEyeX = tRe.x
                rightEyeY = tRe.y
            }
            if (nose != null) {
                val tNose = transformPoint(nose, texMatrix)
                faceCenterX = tNose.x
                faceCenterY = tNose.y
            } else {
                faceCenterX = (leftEyeX + rightEyeX) / 2f
                faceCenterY = (leftEyeY + rightEyeY) / 2f
            }
        }

        GLES30.glUniform1i(uMorphTypeHandle, morphType)
        GLES30.glUniform2f(uLeftEyeHandle, leftEyeX, leftEyeY)
        GLES30.glUniform2f(uRightEyeHandle, rightEyeX, rightEyeY)
        GLES30.glUniform2f(uFaceCenterHandle, faceCenterX, faceCenterY)
        GLES30.glUniform1f(uBeautyLevelHandle, beautyLevel)
        GLES30.glUniform1f(uTexelWidthHandle, 1.0f / outW.toFloat())
        GLES30.glUniform1f(uTexelHeightHandle, 1.0f / outH.toFloat())
        
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES30.glDisableVertexAttribArray(aPositionHandle)
        GLES30.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glUseProgram(0)
    }

    private fun transformPoint(p: android.graphics.PointF, matrix: FloatArray): android.graphics.PointF {
        val x = p.x
        val y = p.y
        // standard homogeneous 2D matrix transformation on texture coords
        val tx = matrix[0] * x + matrix[4] * y + matrix[12]
        val ty = matrix[1] * x + matrix[5] * y + matrix[13]
        return android.graphics.PointF(tx, ty)
    }

    private fun drawArTexture() {
        val faceData = currentArFaceData ?: return
        if (activeArFilter != "Crown" && activeArFilter != "England") return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(arProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arTextureId)
        GLES30.glUniform1i(sArTextureHandle, 2)

        val bounds = faceData.bounds
        val faceW = bounds.width()
        var cx = bounds.centerX()
        var cy = bounds.centerY()

        val aspect = outW.toFloat() / outH.toFloat()
        var hw = faceW * 0.5f
        
        var angle = 0.0
        val leftEye = faceData.leftEye
        val rightEye = faceData.rightEye
        if (leftEye != null && rightEye != null) {
            // Sort eyes by screen X coordinate to ensure screen-left and screen-right eyes are consistent.
            // This prevents 180-degree flips in the tilt angle when the image is mirrored.
            val (screenLeftEye, screenRightEye) = if (leftEye.x < rightEye.x) {
                leftEye to rightEye
            } else {
                rightEye to leftEye
            }

            // Scale X to match Y for accurate physical angle and distance math
            val eyeDistX = (screenRightEye.x - screenLeftEye.x) * aspect
            val eyeDistY = screenRightEye.y - screenLeftEye.y
            Log.i("SticamAR", "leftEye: $leftEye, rightEye: $rightEye, screenLeft: $screenLeftEye, screenRight: $screenRightEye, aspect: $aspect, eyeDistX: $eyeDistX, eyeDistY: $eyeDistY")
            val eyeDist = Math.sqrt((eyeDistX * eyeDistX + eyeDistY * eyeDistY).toDouble()).toFloat()
            
            val midX = (leftEye.x + rightEye.x) / 2f
            val midY = (leftEye.y + rightEye.y) / 2f

            angle = Math.atan2(eyeDistY.toDouble(), eyeDistX.toDouble())
            val cosA = Math.cos(angle).toFloat()
            val sinA = Math.sin(angle).toFloat()

            // Base width scaler. eyeDist is in Y-space, so divide by aspect to get X-space width
            val baseHw = eyeDist / aspect
            Log.i("SticamAR", "angle: $angle, cosA: $cosA, sinA: $sinA, baseHw: $baseHw")

            if (activeArFilter == "Crown") {
                hw = baseHw * 1.3f
                val offset = eyeDist * 1.6f // Physically accurate forehead height
                // Move UP (negative Y) along the rotated axis
                // X movement needs to be converted back to X-scale (/ aspect)
                cx = midX + sinA * offset / aspect
                cy = midY - cosA * offset
            } else if (activeArFilter == "England") {
                hw = baseHw * 1.0f
                val offset = eyeDist * 2.1f
                cx = midX + sinA * offset / aspect
                cy = midY - cosA * offset
            } else if (activeArFilter == "Dog" || activeArFilter == "Cat") {
                hw = baseHw * 1.6f
                val offset = eyeDist * 0.4f
                cx = midX + sinA * offset / aspect
                cy = midY - cosA * offset
            } else if (activeArFilter == "Sunglasses") {
                hw = baseHw * 1.35f
                cx = midX
                cy = midY
            } else if (activeArFilter == "Beard") {
                hw = baseHw * 1.2f
                val mouth = faceData.mouthBottom
                if (mouth != null) {
                    val offset = eyeDist * 0.3f
                    cx = mouth.x + sinA * offset / aspect
                    cy = mouth.y + cosA * offset
                } else {
                    val offset = eyeDist * 1.5f
                    cx = midX - sinA * offset / aspect
                    cy = midY + cosA * offset
                }
            }
            Log.i("SticamAR", "cx: $cx, cy: $cy, hw: $hw, filter: $activeArFilter")
        } else {
            // Fallback to bounding box logic
            if (activeArFilter == "Crown") {
                hw = faceW * 0.4f
                cy = bounds.top - (hw * aspect * 0.5f)
            } else if (activeArFilter == "England") {
                hw = faceW * 0.35f
                cy = bounds.top - (hw * aspect * 0.8f)
            } else if (activeArFilter == "Dog" || activeArFilter == "Cat") {
                hw = faceW * 0.6f
                cy = bounds.top + (faceW * 0.2f)
            } else if (activeArFilter == "Sunglasses") {
                hw = faceW * 0.45f
                cy = bounds.top + (faceW * 0.35f)
            } else if (activeArFilter == "Beard") {
                hw = faceW * 0.4f
                cy = bounds.bottom - (faceW * 0.15f)
            }
        }

        var ch = hw * aspect
        val cosA = Math.cos(angle).toFloat()
        val sinA = Math.sin(angle).toFloat()

        fun rotX(x: Float, y: Float): Float {
            val dx = x - cx
            val dy = (y - cy) / aspect
            return cx + (dx * cosA - dy * sinA)
        }
        fun rotY(x: Float, y: Float): Float {
            val dx = x - cx
            val dy = (y - cy) / aspect
            return cy + (dx * sinA + dy * cosA) * aspect
        }

        val left = cx - hw
        val right = cx + hw
        val top = cy - ch
        val bottom = cy + ch

        // Rotate corners in normalized space
        val blX = rotX(left, bottom); val blY = rotY(left, bottom)
        val brX = rotX(right, bottom); val brY = rotY(right, bottom)
        val tlX = rotX(left, top); val tlY = rotY(left, top)
        val trX = rotX(right, top); val trY = rotY(right, top)

        // Convert normalized to OpenGL coordinates
        fun glX(x: Float) = x * 2.0f - 1.0f
        fun glY(y: Float) = 1.0f - (y * 2.0f)

        // UV coords for texture: 0,0 is top-left, 1,1 is bottom-right.
        arVertexData[0] = glX(blX); arVertexData[1] = glY(blY); arVertexData[2] = 0f; arVertexData[3] = 0f; arVertexData[4] = 1f; // Bottom Left
        arVertexData[5] = glX(brX); arVertexData[6] = glY(brY); arVertexData[7] = 0f; arVertexData[8] = 1f; arVertexData[9] = 1f; // Bottom Right
        arVertexData[10]= glX(tlX); arVertexData[11]= glY(tlY); arVertexData[12]= 0f; arVertexData[13]= 0f; arVertexData[14]= 0f; // Top Left
        arVertexData[15]= glX(trX); arVertexData[16]= glY(trY); arVertexData[17]= 0f; arVertexData[18]= 1f; arVertexData[19]= 0f; // Top Right

        arVertexBuffer.clear()
        arVertexBuffer.put(arVertexData).position(0)
        GLES30.glVertexAttribPointer(arPositionHandle, 3, GLES30.GL_FLOAT, false, 5 * 4, arVertexBuffer)
        GLES30.glEnableVertexAttribArray(arPositionHandle)

        arVertexBuffer.position(3)
        GLES30.glVertexAttribPointer(arTextureCoordHandle, 2, GLES30.GL_FLOAT, false, 5 * 4, arVertexBuffer)
        GLES30.glEnableVertexAttribArray(arTextureCoordHandle)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(arPositionHandle)
        GLES30.glDisableVertexAttribArray(arTextureCoordHandle)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }
    
    /**
     * Load a face mesh texture filter from assets.
     * @param filterName Name of the mesh filter (e.g. "TigerPaint", "SkullMask")
     * @param assetPath Path to the PNG texture in assets (e.g. "filters/tiger_paint.png")
     */
    fun setMeshFilter(filterName: String, assetPath: String?) {
        handler.post {
            activeMeshFilter = filterName
            if (assetPath != null) {
                try {
                    val bitmap = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
                    if (bitmap != null) {
                        faceMeshRenderer?.loadTexture(bitmap)
                        bitmap.recycle()
                        Log.i("GlRenderer", "Loaded mesh filter: $filterName from $assetPath")
                    }
                } catch (e: Exception) {
                    Log.e("GlRenderer", "Failed to load mesh filter: $filterName", e)
                    activeMeshFilter = "None"
                }
            } else {
                activeMeshFilter = "None"
            }
        }
    }

    /**
     * Load a face mesh texture from a Bitmap directly.
     */
    fun setMeshFilterBitmap(filterName: String, bitmap: android.graphics.Bitmap) {
        handler.post {
            activeMeshFilter = filterName
            faceMeshRenderer?.loadTexture(bitmap)
            Log.i("GlRenderer", "Loaded mesh filter bitmap: $filterName")
        }
    }

    private fun drawFaceMesh() {
        if (activeMeshFilter == "None") return
        val faceData = currentArFaceData ?: return
        faceMeshRenderer?.draw(faceData, opacity = 0.85f, isFrontCamera = isFrontCamera)
    }

    fun release() {
        handler.post {
            faceMeshRenderer?.release()
            inputSurface?.release()
            surfaceTexture?.release()
            eglPreviewSurface?.let { eglManager?.releaseSurface(it) }
            eglEncoderSurface?.let { eglManager?.releaseSurface(it) }
            eglManager?.release()
            handlerThread.quitSafely()
        }
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        var shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("GlRenderer", "Could not compile shader $type: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }
    
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val pixelShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (pixelShader == 0) return 0
        
        var program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, pixelShader)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e("GlRenderer", "Could not link program: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            program = 0
        }
        return program
    }
}
