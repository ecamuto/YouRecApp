package it.ecamuto.yourec.android.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A persistent encoder canvas, independent of the currently open CameraDevice.
 * The retained FBO supplies an explicit freeze during camera replacement. The encoder
 * and microphone keep their clocks; switching never becomes an implicit pause.
 * All EGL operations run on a separate thread so swapBuffers cannot block PCM delivery.
 */
internal class VideoCompositor(
    output: Surface,
    private val onFrame: (Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("CaptureGL").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraTexture = 0
    private var retainedTexture = 0
    private var framebuffer = 0
    private var oesProgram = 0
    private var copyProgram = 0
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set
    private var closed = false
    private var accepting = false
    private var token = 0
    private var acceptFromNs = Long.MAX_VALUE
    private var firstForCamera = true
    private var sensorDegrees = 0
    private var deltaDegrees = 0
    private var hasImage = false
    private var sourceAnchorNs = 0L
    private var renderAnchorNs = 0L
    private var lastPresentationNs = -1L
    private var lastFreshNs = 0L
    private var switchingAtNs = 0L
    private val transform = FloatArray(16)
    private val predicted = FloatArray(16)
    private val inverse = FloatArray(16)
    private val desired = FloatArray(16)
    private val additional = FloatArray(16)
    private val finalMatrix = FloatArray(16)
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val uv = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }

    init {
        val task = FutureTask {
            try { initialize(output) } catch (error: Exception) { release(); throw error }
        }
        handler.post(task)
        try { task.get(5, TimeUnit.SECONDS) } catch (error: Exception) {
            handler.post { release(); thread.quitSafely() }
            throw IllegalStateException("Inizializzazione video GPU fallita.", error)
        }
    }

    fun beginSwitch() { handler.post {
        if (!closed) { accepting = false; switchingAtNs = SystemClock.elapsedRealtimeNanos() }
    } }

    fun expectCamera(sensor: Int, relativeRotation: Int, generation: Int, afterNs: Long) { handler.post {
        if (!closed) {
            sensorDegrees = sensor
            deltaDegrees = relativeRotation
            token = generation
            acceptFromNs = afterNs
            firstForCamera = true
            accepting = true
        }
    } }

    /** Stop drawing before the codec receives EOS. The camera producer must already be drained. */
    fun finish(complete: () -> Unit) { handler.post {
        release()
        complete()
        thread.quitSafely()
    } }

    private fun initialize(output: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "Display EGL non disponibile." }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "Configurazione EGL encoder non disponibile." }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Creazione contesto EGL fallita." }
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) { "Superficie EGL encoder non disponibile." }
        oesProgram = program(true)
        copyProgram = program(false)
        cameraTexture = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        retainedTexture = newTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0); framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, retainedTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Canvas GPU incompleto." }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        texture = SurfaceTexture(cameraTexture).apply {
            setDefaultBufferSize(WIDTH, HEIGHT)
            setOnFrameAvailableListener({ receiveFrame() }, handler)
        }
        surface = Surface(texture)
        glCheck()
    }

    private fun receiveFrame() {
        if (closed) return
        try {
            texture.updateTexImage()
            val sourceNs = texture.timestamp
            if (!accepting || sourceNs < acceptFromNs) return
            val now = SystemClock.elapsedRealtimeNanos()
            check(kotlin.math.abs(sourceNs - now) < 5_000_000_000) { "Clock camera GPU non confrontabile con il microfono." }
            texture.getTransformMatrix(transform)
            // SurfaceTexture carries the camera producer transform (including the GL
            // vertical flip). Remove its predicted orientation, retaining actual crop,
            // then rotate raw pixels into the take's fixed MP4 orientation. Outputs
            // use MIRROR_MODE_NONE; the separate preview alone receives a selfie mirror.
            Matrix.setIdentityM(predicted, 0)
            Matrix.translateM(predicted, 0, 0f, 1f, 0f)
            Matrix.scaleM(predicted, 0, 1f, -1f, 1f)
            rotateTexture(predicted, sensorDegrees)
            check(Matrix.invertM(inverse, 0, predicted, 0))
            Matrix.setIdentityM(desired, 0)
            Matrix.translateM(desired, 0, 0f, 1f, 0f)
            Matrix.scaleM(desired, 0, 1f, -1f, 1f)
            rotateTexture(desired, deltaDegrees)
            Matrix.multiplyMM(additional, 0, inverse, 0, desired, 0)
            Matrix.multiplyMM(finalMatrix, 0, transform, 0, additional, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, WIDTH, HEIGHT)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // Fit rather than stretch if a different sensor mounting swaps the axes.
            if (deltaDegrees % 180 != 0) {
                val fittedWidth = HEIGHT * HEIGHT / WIDTH
                GLES20.glViewport((WIDTH - fittedWidth) / 2, 0, fittedWidth, HEIGHT)
            }
            draw(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture, finalMatrix)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            glCheck()
            lastFreshNs = now
            if (firstForCamera) {
                firstForCamera = false
                Log.i(TAG, "camera frame generation=$token source=$sourceNs sensor=$sensorDegrees delta=$deltaDegrees transform=${transform.joinToString()} freezeMs=${if (switchingAtNs == 0L) 0 else (now - switchingAtNs) / 1_000_000}")
                switchingAtNs = 0
                onFrame(token)
            }
            if (!hasImage) {
                hasImage = true
                sourceAnchorNs = sourceNs
                renderAnchorNs = now
                handler.post(render)
            }
        } catch (error: Exception) { fail(error) }
    }

    private val render = object : Runnable {
        override fun run() {
            if (closed || !hasImage) return
            try {
                val now = SystemClock.elapsedRealtimeNanos()
                check(switchingAtNs != 0L || now - lastFreshNs < 2_000_000_000) { "La fotocamera non consegna nuovi fotogrammi." }
                // Anchor once to the first hardware timestamp. Never reset this clock
                // when a camera disappears: frozen frames occupy real elapsed time.
                val pts = sourceAnchorNs + now - renderAnchorNs
                check(pts > lastPresentationNs) { "Clock GPU non crescente." }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, WIDTH, HEIGHT)
                draw(copyProgram, GLES20.GL_TEXTURE_2D, retainedTexture, identity)
                check(EGLExt.eglPresentationTimeANDROID(display, window, pts)) { "Timestamp EGL rifiutato." }
                check(EGL14.eglSwapBuffers(display, window)) { "Invio fotogramma encoder fallito." }
                glCheck()
                lastPresentationNs = pts
                val remaining = 33_333_333 - (SystemClock.elapsedRealtimeNanos() - now)
                handler.postDelayed(this, maxOf(1L, remaining / 1_000_000))
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun draw(program: Int, target: Int, id: Int, matrix: FloatArray) {
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coord = GLES20.glGetAttribLocation(program, "coord")
        quad.position(0); uv.position(0)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coord)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(coord)
    }

    private fun newTexture(target: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun program(external: Boolean): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); error("Shader GPU: $log") }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec4 position;
            attribute vec4 coord;
            uniform mat4 transform;
            varying vec2 uv;
            void main() { gl_Position = position; uv = (transform * coord).xy; }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            (if (external) "#extension GL_OES_EGL_image_external : require\n" else "") + """
            precision mediump float;
            uniform ${if (external) "samplerExternalOES" else "sampler2D"} image;
            varying vec2 uv;
            void main() { gl_FragColor = texture2D(image, uv); }
        """.trimIndent())
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex); GLES20.glAttachShader(id, fragment); GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val ok = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetProgramInfoLog(id); GLES20.glDeleteProgram(id); error("Programma GPU: $log") }
        return id
    }

    private fun fail(error: Exception) {
        accepting = false
        handler.removeCallbacks(render)
        onError(error.message ?: "Errore compositore video.")
    }

    private fun release() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (::texture.isInitialized) { texture.setOnFrameAvailableListener(null); texture.release() }
        if (::surface.isInitialized) surface.release()
        if (context != EGL14.EGL_NO_CONTEXT) {
            GLES20.glDeleteProgram(oesProgram); GLES20.glDeleteProgram(copyProgram)
            GLES20.glDeleteTextures(2, intArrayOf(cameraTexture, retainedTexture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val TAG = "CaptureGL"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private fun rotateTexture(matrix: FloatArray, degrees: Int) {
            Matrix.translateM(matrix, 0, .5f, .5f, 0f)
            Matrix.rotateM(matrix, 0, degrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(matrix, 0, -.5f, -.5f, 0f)
        }
        private fun glCheck() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Errore OpenGL durante l'acquisizione." } }
    }
}
