package it.ecamuto.yourec.android.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor

/** Camera2 preview/capture; AvRecorder owns the microphone, codecs and shared clock. */
class CaptureController(context: Context) : DefaultLifecycleObserver {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(CameraManager::class.java)
    // Match Dispatchers.Main's async delivery: a VSYNC barrier must not let the
    // save coroutine overtake a queued STOPPING update and leave the UI busy.
    private val main = Handler.createAsync(Looper.getMainLooper())
    private val thread = HandlerThread("CaptureMedia").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { CaptureStore(File(this.context.filesDir, "capture-prototype")) }
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()
    private var owner: LifecycleOwner? = null
    private var view: CapturePreview? = null
    @Volatile private var disposed = false
    private var foreground = false
    private var front = false
    private var displayRotation = Surface.ROTATION_0
    private var previewSurface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var opening = false
    private var generation = 0
    private var sessionGeneration = 0
    private var recorder: AvRecorder? = null
    private var pendingDrain: Pair<CameraCaptureSession, () -> Unit>? = null

    init { refreshRecordings() }

    private fun update(block: (CaptureState) -> CaptureState) { main.post { mutableState.value = block(mutableState.value) } }

    fun attach(owner: LifecycleOwner, view: CapturePreview) {
        this.owner = owner
        this.view = view
        displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        view.onAvailable = {
            val surface = view.surface
            // The first composition may run before the view is attached to a display.
            val rotation = view.display?.rotation ?: displayRotation
            handler.post { displayRotation = rotation; previewSurface = surface; if (foreground) openCamera() }
        }
        view.onGone = { handler.post { previewSurface = null; recorder?.stop(); closeCamera() } }
        handler.post { previewSurface = view.surface }
        owner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        displayRotation = view?.display?.rotation ?: Surface.ROTATION_0
        handler.post { foreground = true; openCamera() }
    }

    override fun onStop(owner: LifecycleOwner) { handler.post { foreground = false; recorder?.stop(); closeCamera() } }

    fun detach() {
        owner?.lifecycle?.removeObserver(this)
        owner = null
        view?.onAvailable = null
        view?.onGone = null
        view = null
        handler.post { foreground = false; previewSurface = null; recorder?.stop(); closeCamera() }
    }

    fun switchCamera() {
        if (state.value.busy || !state.value.canSwitch) return
        displayRotation = view?.display?.rotation ?: Surface.ROTATION_0
        handler.post { front = !front; closeCamera(); openCamera() }
    }

    fun retryCamera() { if (!state.value.busy) handler.post { closeCamera(); openCamera() } }

    @SuppressLint("MissingPermission") // Runtime permission checked before opening.
    private fun openCamera() {
        if (disposed || !foreground || previewSurface == null || device != null || opening || recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val token = ++generation
        update { it.copy(ready = false, message = "Apertura fotocamera…") }
        try {
            val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
            val back = cameras.firstOrNull { it.second[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            val selfie = cameras.firstOrNull { it.second[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT }
            val selected = (if (front) selfie ?: back else back ?: selfie) ?: error("Nessuna fotocamera disponibile.")
            front = selected.second[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
            update { it.copy(front = front, canSwitch = back != null && selfie != null) }
            characteristics = selected.second
            check(selected.second[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                "Il prototipo richiede timestamp camera REALTIME confrontabili con il microfono."
            }
            val map = checkNotNull(selected.second[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            check(Size(1920, 1080) in map.getOutputSizes(MediaCodec::class.java).orEmpty() &&
                Size(1920, 1080) in map.getOutputSizes(SurfaceHolder::class.java).orEmpty()) {
                "Camera priva della combinazione 1080p SDR richiesta."
            }
            val rotation = outputRotation()
            main.post { view?.configure(rotation) }
            opening = true
            manager.openCamera(selected.first, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (disposed || token != generation || !foreground) { camera.close(); return }
                    opening = false
                    device = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraFailed("Fotocamera disconnessa.", token) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraFailed("Errore camera $error.", token) }
            })
        } catch (error: Exception) { cameraFailed(error.message ?: "Apertura camera fallita.", token) }
    }

    private fun outputRotation(): Int {
        val degrees = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return (sensor + (if (front) degrees else -degrees) + 360) % 360
    }

    private fun cameraFailed(reason: String, token: Int = generation) {
        if (token != generation) return
        opening = false
        recorder?.abort(reason)
        closeCamera()
        update { it.copy(ready = false, message = reason) }
    }

    private fun createSession() {
        val camera = device ?: return
        val preview = previewSurface?.takeIf { it.isValid } ?: return
        val token = ++sessionGeneration
        session?.close()
        session = null
        val writer = recorder
        val surfaces = listOfNotNull(preview, writer?.surface)
        try {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                surfaces.map { OutputConfiguration(it).apply {
                    // Apply the producer transform only to the selfie preview. A view
                    // scale is not a reliable mirror of SurfaceView's separate surface.
                    setMirrorMode(if (it == preview && front) OutputConfiguration.MIRROR_MODE_H
                        else OutputConfiguration.MIRROR_MODE_NONE)
                } }, executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (disposed || token != sessionGeneration || device != camera || !foreground) { configured.close(); return }
                        if (writer != null && (writer !== recorder || writer.isStopping)) { configured.close(); return }
                        session = configured
                        try {
                            val request = camera.createCaptureRequest(if (recorder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                            surfaces.forEach { request.addTarget(it) }
                            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                ?: intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
                            val afMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_OFF)
                                .first { it in afModes }
                            request.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                                .filter { it.upper == 30 }.maxByOrNull { it.lower }?.let {
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                }
                            configured.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                                    cameraDrained(session)
                                }
                                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) { cameraDrained(session) }
                            }, handler)
                            Log.i("CaptureCamera", "camera=${camera.id} rotation=${outputRotation()} front=$front surfaces=${surfaces.size} SDR 1920x1080")
                            update { it.copy(ready = true, front = front,
                                message = if (it.busy) it.message else "1080p · SDR · audio incluso · 30 fps richiesti") }
                        } catch (error: Exception) { cameraFailed(error.message ?: "Avvio camera fallito.") }
                    }
                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        if (token == sessionGeneration) cameraFailed("Configurazione camera/encoder non supportata.")
                    }
                }))
        } catch (error: Exception) { cameraFailed(error.message ?: "Configurazione camera fallita.") }
    }

    @SuppressLint("UsableSpace") // Conservative check; no automatic cache reclamation.
    fun start() {
        if (!state.value.ready || state.value.busy || disposed) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update { it.copy(message = "È necessario il permesso microfono.") }
            return
        }
        if (context.filesDir.usableSpace < 100L * 1024 * 1024) {
            update { it.copy(message = "Spazio insufficiente: servono almeno 100 MB liberi.") }
            return
        }
        mutableState.value = state.value.copy(phase = CapturePhase.STARTING, elapsedNs = 0, bytes = 0, message = "Preparazione ripresa…")
        displayRotation = view?.display?.rotation ?: Surface.ROTATION_0
        handler.post {
            try {
                check(device != null && foreground) { "Fotocamera non disponibile." }
                val file = store.allocate()
                recorder = AvRecorder(file, outputRotation(), handler,
                    onPhase = { phase -> update { it.copy(phase = phase, message = when (phase) {
                        CapturePhase.RECORDING -> "Registrazione in corso"
                        CapturePhase.PAUSED -> "In pausa"
                        CapturePhase.RESUMING -> "Preparazione ripresa…"
                        else -> "Chiusura della ripresa…"
                    }) } },
                    onStats = { duration, bytes -> update { it.copy(elapsedNs = duration, bytes = bytes) } },
                    onStopping = { complete ->
                        val current = session
                        if (current == null) complete() else {
                            pendingDrain = current to complete
                            runCatching { current.stopRepeating() }.onFailure { cameraDrained(current) }
                        }
                    },
                    onFinished = { error -> finalized(file, error) })
                recorder!!.startMicrophone()
                createSession()
            } catch (error: Exception) {
                if (recorder != null) recorder?.abort(error.message ?: "Avvio fallito.") else {
                    update { it.copy(phase = CapturePhase.IDLE, message = error.message ?: "Avvio fallito.") }
                    createSession()
                }
            }
        }
    }

    fun pauseOrResume() {
        val phase = state.value.phase
        if (phase !in listOf(CapturePhase.RECORDING, CapturePhase.PAUSED)) return
        mutableState.value = state.value.copy(phase = if (phase == CapturePhase.PAUSED) CapturePhase.RESUMING else CapturePhase.PAUSING)
        handler.post { if (phase == CapturePhase.PAUSED) recorder?.resume() else recorder?.pause() }
    }

    fun stop() { handler.post { recorder?.stop() } }

    private fun finalized(file: File, error: String?) {
        recorder = null
        if (foreground && !disposed) { if (device == null) openCamera() else createSession() }
        scope.launch {
            mutableState.value = state.value.copy(phase = CapturePhase.SAVING, message = "Salvataggio della ripresa…")
            val result = withContext(Dispatchers.IO) {
                runCatching { check(error == null) { error.orEmpty() }; inspectRecording(file); store.publish(file) }
            }
            mutableState.value = state.value.copy(phase = CapturePhase.IDLE, message = result.fold(
                { "Ripresa salvata con tracce video e audio." }, { "${it.message} File provvisorio conservato." }))
            refreshRecordings()
            if (disposed) { scope.cancel(); thread.quitSafely() }
        }
    }

    fun refreshRecordings() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val files = store.recordings()
                val items = files.mapNotNull { runCatching { inspectRecording(it) }.getOrNull() }
                Triple(items, store.pendingCount(), files.size - items.size)
            } }
            result.fold({ (items, pending, unreadable) ->
                mutableState.value = state.value.copy(recordings = items, pendingCount = pending, unreadableCount = unreadable, libraryError = null)
            }, { error -> mutableState.value = state.value.copy(libraryError = error.message ?: "Lettura archivio fallita.") })
        }
    }

    private fun closeCamera() {
        ++generation
        ++sessionGeneration
        opening = false
        val drain = pendingDrain?.second
        pendingDrain = null
        session?.close()
        session = null
        device?.close()
        device = null
        drain?.invoke()
        update { it.copy(ready = false) }
    }

    private fun cameraDrained(completed: CameraCaptureSession) {
        val waiting = pendingDrain?.takeIf { it.first === completed } ?: return
        pendingDrain = null
        waiting.second()
    }

    fun close() {
        if (disposed) return
        disposed = true
        owner?.lifecycle?.removeObserver(this)
        owner = null
        view?.onAvailable = null
        view?.onGone = null
        view = null
        handler.post {
            foreground = false
            recorder?.stop()
            closeCamera()
            previewSurface = null
            if (recorder == null) { scope.cancel(); thread.quitSafely() }
        }
    }
}
