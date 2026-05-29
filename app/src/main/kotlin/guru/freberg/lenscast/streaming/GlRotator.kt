package guru.freberg.lenscast.streaming

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EGL/GL rotation pipeline sitting between Camera2 and the H.264 encoder for protocols
 * that ship raw H.264 on the wire (RTSP, SRT). The MediaCodec encoder's
 * `KEY_ROTATION` only embeds metadata for MP4 muxers; raw H.264 in MPEG-TS / RTP has
 * no rotation hint, so without this stage all receivers see sensor-native landscape
 * regardless of how the phone is held.
 *
 *   Camera2 → SurfaceTexture (OES external texture)
 *          → this renderer rotates by [rotationDegrees]
 *          → EGL window surface (which IS the H.264 encoder's input surface)
 *
 * Threading: a dedicated HandlerThread owns the EGL context and runs `updateTexImage` +
 * draw + `eglSwapBuffers`. SurfaceTexture's `onFrameAvailable` callback posts a render
 * task back to that thread. Construction blocks the caller until EGL is up; callers
 * then hand [cameraSurface] to the camera driver and [release] when the stream stops.
 *
 * The encoder output dimensions are caller-supplied (typically swapped from camera
 * dimensions when [rotationDegrees] is 90 / 270) and the GL viewport matches them; the
 * texture coordinates are pre-rotated so the rendered quad fills the viewport correctly.
 */
class GlRotator(
    encoderSurface: Surface,
    private val encoderWidth: Int,
    private val encoderHeight: Int,
    cameraBufferWidth: Int,
    cameraBufferHeight: Int,
    private val rotationDegrees: Int,
    mirror: Boolean = false,
) {

    /** Surface to hand the Camera2 driver. The camera writes frames here; we rotate them. */
    lateinit var cameraSurface: Surface
        private set

    private val thread = HandlerThread("LenscastGlRotator").also { it.start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program: Int = 0
    private var aPosLoc: Int = -1
    private var aTexLoc: Int = -1
    private var uMatLoc: Int = -1
    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null

    private val stMatrix = FloatArray(16)

    // Rotation is applied to the QUAD GEOMETRY, not the texture coordinates. Texcoords
    // flow through SurfaceTexture's transform matrix (which contains a V-flip, since
    // Android surfaces are origin-top-left and GL textures origin-bottom-left). Rotating
    // texcoords *before* that flip silently reverses the rotation's handedness — the
    // bug that put the picture 90° off in alternating directions. Vertex positions go
    // straight to gl_Position with no such flip, so the direction is unambiguous: the
    // known-good landscape baseline (rotationDegrees == 0) proves NDC space has no net
    // flip, so a clockwise vertex rotation is a clockwise picture rotation.
    // Vertex geometry carries both the rotation and the optional horizontal mirror, so a live
    // mirror toggle just swaps this buffer on the GL thread — no encoder/session rebuild. Read
    // and written only on [handler]'s thread (renderFrame + setMirror both run there).
    @Volatile private var mirror: Boolean = mirror
    private var vertexBuffer: FloatBuffer = floatBufferOf(*rotatedQuad(rotationDegrees, mirror))
    private val texCoordBuffer: FloatBuffer = floatBufferOf(
        // TRIANGLE_STRIP order: bottom-left, bottom-right, top-left, top-right.
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f,
    )

    init {
        val latch = CountDownLatch(1)
        var initError: Throwable? = null
        handler.post {
            try {
                setupEgl(encoderSurface)
                setupGl()
                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(cameraBufferWidth, cameraBufferHeight)
                    setOnFrameAvailableListener({ handler.post(::renderFrame) }, handler)
                }
            } catch (t: Throwable) {
                initError = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        initError?.let { release(); throw it }
        cameraSurface = Surface(surfaceTexture!!)
    }

    /** Live horizontal-flip toggle. Recomputes the quad on the GL thread so it composes with
     *  the fixed rotation without rebuilding the encoder pipeline. */
    fun setMirror(on: Boolean) {
        handler.post {
            if (released.get() || mirror == on) return@post
            mirror = on
            vertexBuffer = floatBufferOf(*rotatedQuad(rotationDegrees, on))
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            try {
                surfaceTexture?.release()
                surfaceTexture = null
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            } catch (t: Throwable) {
                Log.w(TAG, "release failed", t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        try { if (::cameraSurface.isInitialized) cameraSurface.release() } catch (_: Throwable) {}
        thread.quitSafely()
    }

    private fun renderFrame() {
        if (released.get()) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniformMatrix4fv(uMatLoc, 1, false, stMatrix, 0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aTexLoc)
            // Pass the camera's frame timestamp to the encoder Surface so the encoder
            // uses the real capture time as PTS (drives our muxer's PTS in turn).
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            Log.w(TAG, "renderFrame failed", t)
        }
    }

    private fun setupEgl(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        // EGL_RECORDABLE_ANDROID = 0x3142; required when targeting a MediaCodec input Surface,
        // otherwise eglCreateWindowSurface() succeeds but eglSwapBuffers() silently no-ops on
        // some vendor drivers.
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                && numConfigs[0] > 0
        ) { "eglChooseConfig failed" }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttribs, 0)
        require(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        val vertSrc = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fragSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        program = createProgram(vertSrc, fragSrc)
        aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMatLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, v); GLES20.glAttachShader(prog, f); GLES20.glLinkProgram(prog)
        val s = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, s, 0)
        if (s[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("GL link failed: $log")
        }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh)
        val s = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, s, 0)
        if (s[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("GL compile failed: $log")
        }
        return sh
    }

    companion object {
        private const val TAG = "GlRotator"

        private fun floatBufferOf(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(v); position(0) }

        /**
         * The four full-screen-quad NDC positions (TRIANGLE_STRIP order: bottom-left,
         * bottom-right, top-left, top-right), rotated CLOCKWISE by [deg] about the NDC
         * origin. [deg] uses the same semantics as `MediaFormat.KEY_ROTATION`: the
         * clockwise rotation to apply to the source for upright display. Since the quad
         * spans the symmetric NDC square (-1..1), a 90°/270° rotation permutes the
         * corners and still fills the (portrait) viewport exactly.
         */
        private fun rotatedQuad(deg: Int, mirror: Boolean = false): FloatArray {
            val base = floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f)
            val out = FloatArray(8)
            for (i in 0 until 4) {
                val x = base[i * 2]; val y = base[i * 2 + 1]
                val (rx, ry) = when (((deg % 360) + 360) % 360) {
                    90  -> y to -x     // clockwise 90
                    180 -> -x to -y
                    270 -> -y to x     // clockwise 270 (== ccw 90)
                    else -> x to y
                }
                // Mirror is a horizontal flip of the *displayed* frame: negate the final NDC x
                // (the viewport's horizontal axis) after rotation, so it's a left-right flip
                // regardless of the rotation applied above.
                out[i * 2] = if (mirror) -rx else rx
                out[i * 2 + 1] = ry
            }
            return out
        }
    }
}
