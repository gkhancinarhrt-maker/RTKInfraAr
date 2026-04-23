package com.tusaga.rtkinfra.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import com.tusaga.rtkinfra.data.model.*
import com.tusaga.rtkinfra.gnss.RtkState
import timber.log.Timber
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val anchorManager: InfrastructureAnchorManager
) : GLSurfaceView.Renderer {

    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var objectRenderer: InfraObjectRenderer

    var arSession: Session? = null
    var features: List<InfraFeature> = emptyList()
    var currentRtkState: RtkState = RtkState()
    var onSessionUpdated: ((Frame) -> Unit)? = null

    private val projMatrix  = FloatArray(16)
    private val viewMatrix  = FloatArray(16)
    private val mvpMatrix   = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread(context)
        objectRenderer = InfraObjectRenderer()
        objectRenderer.createOnGlThread(context)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = arSession ?: return
        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            session.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)
            onSessionUpdated?.invoke(frame)

            if (camera.trackingState != TrackingState.TRACKING) return

            camera.getProjectionMatrix(projMatrix, 0, 0.01f, 100f)
            camera.getViewMatrix(viewMatrix, 0)

            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)

            features.forEach { feature ->
                val pose = anchorManager.computePoseForFeature(feature, frame) ?: return@forEach
                renderFeature(feature, pose)
            }
        } catch (e: Exception) {
            Timber.e("AR draw error: ${e.message}")
        }
    }

    private fun renderFeature(feature: InfraFeature, pose: Pose) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, pose.tx(), pose.ty(), pose.tz())
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        val color = getFeatureColor(feature.type)
        val alpha = computeDepthAlpha(feature.depthM ?: -1.0)

        when (feature.geometryType) {
            GeometryType.POINT -> objectRenderer.drawPoint(mvpMatrix, color, alpha, scale = 0.15f)
            GeometryType.LINE  -> objectRenderer.drawLine(mvpMatrix, color, alpha)
        }
    }

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

    private fun computeDepthAlpha(depthM: Double): Float {
        return (1.0 - (Math.abs(depthM) / 10.0).coerceIn(0.0, 0.5)).toFloat()
    }
}

class BackgroundRenderer {
    var textureId: Int = -1
    private var program: Int = 0
    private val quadCoords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private var quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private var texSamplerUniform = 0
    private var posAttrib = 0
    private var texAttrib = 0

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vs = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }
        """.trimIndent()

        program = compileProgram(vs, fs)
        texSamplerUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        posAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
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

class InfraObjectRenderer {
    private var program: Int = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var posAttrib = 0

    private val cubeVerts = floatArrayOf(
        -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,-0.5f, 0.5f,0.5f,-0.5f, -0.5f,0.5f,-0.5f,
        -0.5f,-0.5f, 0.5f, 0.5f,-0.5f, 0.5f, 0.5f,0.5f, 0.5f, -0.5f,0.5f, 0.5f
    )

    fun createOnGlThread(context: Context) {
        val vs = "uniform mat4 u_MVP; attribute vec4 a_Position; void main() { gl_Position = u_MVP * a_Position; }"
        val fs = "precision mediump float; uniform vec4 u_Color; void main() { gl_FragColor = u_Color; }"
        program = compileProgram(vs, fs)
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
        val lineMvp = FloatArray(16)
        Matrix.scaleM(lineMvp, 0, mvp, 0, 0.05f, 0.05f, 1.0f)
        drawPoint(lineMvp, color, alpha, 1.0f)
    }
}

fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
    fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val prog = GLES20.glCreateProgram()
    GLES20.glAttachShader(prog, vs)
    GLES20.glAttachShader(prog, fs)
    GLES20.glLinkProgram(prog)
    return prog
}
