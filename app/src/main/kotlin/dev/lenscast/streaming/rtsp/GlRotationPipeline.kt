package dev.lenscast.streaming.rtsp

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Rotates camera frames between the Camera2 output and the H.264 encoder input.
 *
 * Why this exists: the H.264 encoder's input is a Surface, and we used to give the camera
 * the encoder Surface directly. The encoder writes whatever pixels it gets — and the
 * camera sensor is mounted in landscape on phones, so frames arrived (and got encoded)
 * landscape regardless of how the user held the phone. `MediaFormat.KEY_ROTATION` only
 * sets container-level metadata, which most live-stream decoders ignore.
 *
 * The fix is a small GL pipeline:
 *
 * ```
 *   Camera2 ─→ SurfaceTexture (input) ─→ glDraw(rotation matrix) ─→ encoder Surface
 * ```
 *
 * We create an EGL context on a dedicated handler thread, bind the encoder Surface as
 * the EGLSurface to render *to*, and bind a [SurfaceTexture] as the source to render
 * *from*. Each time the camera produces a frame the [SurfaceTexture.OnFrameAvailableListener]
 * fires, we updateTexImage and draw a single quad transformed by the rotation matrix.
 */
class GlRotationPipeline(
    private val encoderSurface: Surface,
    private val rotationDegrees: Int,
) {
    /** The Surface to hand to Camera2 — produces frames that we transform. */
    lateinit var cameraInputSurface: Surface
        private set

    private val thread = HandlerThread("LenscastGlPipe").also { it.start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTexMatrixLoc = -1
    private var uMvpMatrixLoc = -1
    private var oesTextureId = 0
    private var inputSurfaceTexture: SurfaceTexture? = null

    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    /**
     * Initialise the EGL context and create the input [Surface] / [SurfaceTexture] pair
     * the camera will write into. Must be called before [start].
     *
     * @param textureWidth/textureHeight the rotated dimensions the SurfaceTexture should
     *        request the camera produce (typically same as encoder's). For 90/270 rotation
     *        the camera will produce these dimensions; we map them onto the encoder's
     *        (possibly swapped) surface size.
     */
    fun prepare(textureWidth: Int, textureHeight: Int) {
        runOnGlThread {
            initEgl()
            initProgram()
            initTexture(textureWidth, textureHeight)
            buildMvp()
        }
    }

    fun start() {
        runOnGlThread {
            // Frame pump: SurfaceTexture's listener fires on the GL thread; we draw to
            // the encoder surface and swap.
            inputSurfaceTexture!!.setOnFrameAvailableListener({ drawFrame() }, handler)
        }
    }

    fun release() {
        runOnGlThread { tearDown() }
        thread.quitSafely()
    }

    // ─── EGL / GL setup ───────────────────────────────────────────────────────

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,        // hint: this surface goes into MediaCodec
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, surfaceAttribs, 0)

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun initProgram() {
        val vsh = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fsh = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsh)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsh)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun initTexture(width: Int, height: Int) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
        }
        inputSurfaceTexture = st
        cameraInputSurface = Surface(st)
    }

    /**
     * Build the model-view-projection matrix. Identity for the encoder Surface (no
     * projection change), but apply the rotation we want to imprint on every frame.
     */
    private fun buildMvp() {
        Matrix.setIdentityM(mvpMatrix, 0)
        // Rotate around Z by the requested angle. Sense matches the convention used by
        // [RtspManager] when it computes encoderRotation: positive = clockwise from
        // the device's natural orientation toward sensor's mount.
        Matrix.rotateM(mvpMatrix, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
    }

    // ─── Frame pump ───────────────────────────────────────────────────────────

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v — full-screen quad, normalized device coords.
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    private fun drawFrame() {
        val st = inputSurfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Forward the camera's frame timestamp to the encoder surface so the encoded
        // bitstream has accurate PTS (otherwise PTS comes from System.nanoTime at swap).
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // ─── Teardown ─────────────────────────────────────────────────────────────

    private fun tearDown() {
        try { inputSurfaceTexture?.release() } catch (_: Throwable) {}
        inputSurfaceTexture = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            try { EGL14.eglDestroySurface(eglDisplay, eglSurface) } catch (_: Throwable) {}
            try { EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (_: Throwable) {}
            try { EGL14.eglReleaseThread() } catch (_: Throwable) {}
            try { EGL14.eglTerminate(eglDisplay) } catch (_: Throwable) {}
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun runOnGlThread(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try { block() } catch (t: Throwable) { Log.e(TAG, "GL op failed", t) }
                finally { latch.countDown() }
            }
            try { latch.await() } catch (_: InterruptedException) {}
        }
    }

    companion object {
        private const val TAG = "GlRotationPipeline"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
