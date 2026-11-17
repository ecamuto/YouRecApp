package it.ecamuto.yourec.android

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.ecamuto.yourec.android.capture.CaptureController
import it.ecamuto.yourec.android.capture.CapturePreview
import it.ecamuto.yourec.android.capture.CapturePhase
import it.ecamuto.yourec.android.capture.CaptureState
import it.ecamuto.yourec.android.capture.RecordedMedia
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFAC98))) {
                CaptureApp()
            }
        }
    }

    private fun hasPermissions() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun CaptureApp() {
        val owner = LocalLifecycleOwner.current
        val controller = remember { CaptureController(applicationContext) }
        val state by controller.state.collectAsStateWithLifecycle()
        // Capture the Boolean for this effect instance. Reading the delegated state in
        // onDispose would see the NEW busy=true value when the old idle effect exits,
        // undoing the orientation lock immediately after the Record button sets it.
        val busy = state.busy
        var granted by remember { mutableStateOf(hasPermissions()) }
        var showLibrary by remember { mutableStateOf(false) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = hasPermissions()
        }
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = hasPermissions()
                    controller.refreshRecordings()
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        DisposableEffect(controller) { onDispose { controller.close() } }
        DisposableEffect(busy) {
            if (busy) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                if (busy) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        BackHandler(enabled = state.busy) { controller.stop() }

        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Camera Lab", style = MaterialTheme.typography.headlineSmall)
                        Text("Prototipo Android · camera singola SDR", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !state.busy, onClick = { showLibrary = !showLibrary }) {
                        Text(if (showLibrary) "Fotocamera" else "Riprese (${state.recordings.size})")
                    }
                }
                if (showLibrary) {
                    Recordings(state) { media ->
                        startActivity(Intent(this@MainActivity, PlaybackActivity::class.java)
                            .putExtra(PlaybackActivity.FILE_NAME, media.file.name))
                    }
                } else if (!granted) {
                    Text("Per registrare video con audio servono fotocamera e microfono. Le riprese restano sul dispositivo.")
                    Button(onClick = { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                        Text("Consenti fotocamera e microfono")
                    }
                    TextButton(onClick = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    }) { Text("Apri impostazioni permessi") }
                } else {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val controls: @Composable () -> Unit = {
                            CaptureControls(state, controller) {
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                controller.start()
                                if (!controller.state.value.busy) {
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            }
                        }
                        val camera: @Composable (Modifier) -> Unit = { modifier ->
                            AndroidView(
                                modifier = modifier.background(Color.Black),
                                factory = { context ->
                                    CapturePreview(context).apply {
                                        controller.attach(owner, this)
                                    }
                                },
                                onRelease = { controller.detach() },
                            )
                        }
                        if (maxWidth > maxHeight) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                camera(Modifier.weight(1f).fillMaxSize())
                                Column(Modifier.width(280.dp)) { controls() }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                camera(Modifier.weight(1f).fillMaxWidth())
                                controls()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureControls(state: CaptureState, controller: CaptureController, start: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        if (state.busy) {
            Text("${durationLabel(state.elapsedNs / 1_000_000)} · ${state.bytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.headlineSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.busy) {
                Button(enabled = state.ready && !state.switching, onClick = start) { Text("Registra") }
                OutlinedButton(enabled = state.canChangeCamera, onClick = controller::switchCamera) {
                    Text(if (state.front) "Posteriore" else "Frontale")
                }
            } else {
                Button(enabled = state.phase !in listOf(CapturePhase.STOPPING, CapturePhase.SAVING), onClick = { controller.stop() }) {
                    Text("Ferma")
                }
                OutlinedButton(enabled = !state.switching && state.phase in listOf(CapturePhase.RECORDING, CapturePhase.PAUSED), onClick = controller::pauseOrResume) {
                    Text(if (state.phase == CapturePhase.PAUSED) "Riprendi" else "Pausa")
                }
            }
        }
        if (state.busy) {
            OutlinedButton(enabled = state.canChangeCamera, onClick = controller::switchCamera) {
                Text(if (state.front) "Posteriore" else "Frontale")
            }
        }
        if (!state.ready && !state.busy) {
            TextButton(onClick = controller::retryCamera) { Text("Riapri fotocamera") }
        }
        Text("Anteprima selfie specchiata; file non specchiato. In background la ripresa si ferma.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Recordings(state: CaptureState, play: (RecordedMedia) -> Unit) {
    val items = state.recordings
    if (state.pendingCount > 0) {
        Text("${state.pendingCount} file provvisori conservati. Il recupero delle riprese interrotte non è ancora implementato.")
    }
    if (state.unreadableCount > 0) Text("${state.unreadableCount} riprese non leggibili, conservate nell’archivio.")
    state.libraryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (items.isEmpty()) Text("Nessuna ripresa completata. I video salvati saranno disponibili anche alla riapertura dell’app.")
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.file.name }) { media ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { play(media) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(media.file.lastModified())))
                    Text("${durationLabel(media.durationMs)} · ${media.width}×${media.height} · rotazione ${media.rotation}°")
                    Text("${media.videoMime} · ${media.audioMime}", style = MaterialTheme.typography.bodySmall)
                    Text("Riproduci", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun durationLabel(ms: Long): String {
    val seconds = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
