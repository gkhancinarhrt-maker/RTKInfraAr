package com.tusaga.rtkinfra.ar

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import com.tusaga.rtkinfra.data.model.*
import com.tusaga.rtkinfra.gnss.RtkState
import timber.log.Timber
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for ARCore + infrastructure visualization.
 *
 * Rendering pipeline:
 *  1. Draw ARCore camera background (CpuImageAcquisitionMode)
 *  2. Compute projection matrix from ARCore camera intrinsics
 *  3. For each feature anchor: render 3D geometry in world space
 *
 * Object rendering:
 *  - Points (valves, manholes): colored sphere/box
 *  - Lines (pipes, cables): 3D tube segments
 *  - Color-coded by infrastructure type
 *  - Depth-faded based on underground depth
 */
class ArRenderer(
    private val context: Context,
    private val anchorManager: InfrastructureAnchorManager
) : GLSurfaceView.Renderer {

    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var objectRenderer: InfraObjectRenderer

    var arSession: Session? = null
    var features: List<InfraFeature> = emptyList()
    var currentRtkState: RtkState = RtkState()

    // View/projection matrices
    private val projMatrix  = FloatArray(16)
    private val viewMatrix  = FloatArray(16)
    private val mvpMatrix   = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    var onSessionUpdated: ((Frame) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread(context)
        objectRenderer     = InfraObjectRenderer()
        objectRenderer.createOnGlThread(context)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arSession?.setDisplayGeometry(0, width, height)  // Assume portrait
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = arSession ?: return

        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Update ARCore session texture and get frame
            session.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session.update()
            val camera = frame.camera

            // 1. Draw camera background
            backgroundRenderer.draw(frame)

            // Notify ViewModel for anchor updates
            onSessionUpdated?.invoke(frame)

            if (camera.trackingState != TrackingState.TRACKING) return

            // 2. Get camera matrices
            camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f)
            camera.getViewMatrix(viewMatrix, 0)

            // 3. Enable depth testing
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)

            // 4. Render each infrastructure feature
            features.forEach { feature ->
                val pose = anchorManager.computePoseForFeature(feature, frame) ?: return@forEach
                renderFeature(feature, pose)
            }

        } catch (e: CameraNotAvailableException) {
            Timber.e(e, "AR: Camera not available")
        } catch (e: Exception) {
            Timber.e(e, "AR: Draw frame error")
        }
    }

    private fun renderFeature(feature: InfraFeature, pose: Pose) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0,
            pose.tx(), pose.ty(), pose.tz())

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        val color = getFeatureColor(feature.type)
        val alpha = computeDepthAlpha(feature.depthM ?: -1.0)

        when (feature.geometryType) {
            GeometryType.POINT -> {
                objectRenderer.drawPoint(mvpMatrix, color, alpha, scale = 0.15f)
            }
            GeometryType.LINE -> {
                // Line segments rendered as thick cylinders
                objectRenderer.drawLine(mvpMatrix, color, alpha)
            }
        }
    }

    /**
     * Infrastructure type color coding:
     *  Water     → Blue
     *  Sewer     → Brown
     *  Gas       → Yellow
     *  Telecom   → Green
     *  Electric  → Red/Orange
     */
    private fun getFeatureColor(type: String?): FloatArray {
        return when (type?.lowercase()) {
            "water", "su"          -> floatArrayOf(0.2f, 0.4f, 0.9f, 1f)
            "sewer", "atiksu"      -> floatArrayOf(0.6f, 0.3f, 0.1f, 1f)
            "gas", "dogalgaz"      -> floatArrayOf(0.9f, 0.8f, 0.1f, 1f)
            "telecom", "telekom"   -> floatArrayOf(0.1f, 0.8f, 0.3f, 1f)
            "electric", "elektrik" -> floatArrayOf(0.9f, 0.3f, 0.1f, 1f)
            else                   -> floatArrayOf(0.6f, 0.6f, 0.6f, 1f)
        }
    }

    /** Deeper objects appear more transparent for visual clarity */
    private fun computeDepthAlpha(depthM: Double): Float {
        val d = Math.abs(depthM)
        return (1.0 - (d / 10.0).coerceIn(0.0, 0.5)).toFloat()
    }
}

/**
 * Renders the ARCore camera feed as a background texture.
 */
class BackgroundRenderer {
    var textureId: Int = -1
    private var program: Int = 0
    private val quadCoords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var quadVerts = 0
    private var quadTexVerts = 0
    private var texSamplerUniform = 0
    private var posAttrib = 0
    private var texAttrib = 0

    fun createOnGlThread(context: Context) {
        // Create OES external texture for camera feed
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(0x8D65 /*GL_TEXTURE_EXTERNAL_OES*/, textureId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()

        program = compileProgram(vertexShader, fragmentShader)
        texSamplerUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        posAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
                Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords
            )
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glUniform1i(texSamplerUniform, 0)

        val vertBuf = java.nio.ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        vertBuf.put(quadCoords); vertBuf.position(0)
        val texBuf = java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        texBuf.put(quadTexCoords); texBuf.position(0)

        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glVertexAttribPointer(texAttrib, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glEnableVertexAttribArray(texAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(texAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

/**
 * Renders infrastructure objects as colored geometry.
 */
class InfraObjectRenderer {
    private var program: Int = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var posAttrib = 0

    // Unit cube vertices for point objects
    private val cubeVerts = floatArrayOf(
        -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,-0.5f, 0.5f,0.5f,-0.5f, -0.5f,0.5f,-0.5f,
        -0.5f,-0.5f, 0.5f, 0.5f,-0.5f, 0.5f, 0.5f,0.5f, 0.5f, -0.5f,0.5f, 0.5f
    )

    fun createOnGlThread(context: Context) {
        val vertexShader = """
            uniform mat4 u_MVP;
            attribute vec4 a_Position;
            void main() { gl_Position = u_MVP * a_Position; }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """.trimIndent()

        program = compileProgram(vertexShader, fragmentShader)
        mvpUniform   = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        posAttrib    = GLES20.glGetAttribLocation(program, "a_Position")
    }

    fun drawPoint(mvp: FloatArray, color: FloatArray, alpha: Float, scale: Float = 0.2f) {
        val scaledMvp = FloatArray(16)
        Matrix.scaleM(scaledMvp, 0, mvp, 0, scale, scale, scale)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, scaledMvp, 0)
        GLES20.glUniform4f(colorUniform, color[0], color[1], color[2], alpha)

        val buf = java.nio.ByteBuffer.allocateDirect(cubeVerts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(cubeVerts); buf.position(0)
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 4, 4)
        GLES20.glDisableVertexAttribArray(posAttrib)
    }

    fun drawLine(mvp: FloatArray, color: FloatArray, alpha: Float) {
        // Simplified: draw line as a thin scaled box
        val lineMvp = FloatArray(16)
        Matrix.scaleM(lineMvp, 0, mvp, 0, 0.05f, 0.05f, 1.0f)
        drawPoint(lineMvp, color, alpha, 1.0f)
    }
}

// ── GLSL shader compilation helper ────────────────────────────────────

fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
    fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Timber.e("Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
        }
        return shader
    }
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    return program
}
