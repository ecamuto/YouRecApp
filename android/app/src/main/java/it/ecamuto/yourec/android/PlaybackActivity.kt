package it.ecamuto.yourec.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.io.File

/** Small local player for capture acceptance. The editor will use its own playback pipeline. */
class PlaybackActivity : Activity() {
    private lateinit var video: VideoView
    private var positionMs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(FILE_NAME).orEmpty()
        val root = File(filesDir, "capture-prototype/completed")
        val file = File(root, name)
        if (!name.matches(Regex("[a-f0-9-]{36}\\.mp4")) || !file.isFile || file.canonicalFile.parentFile != root.canonicalFile) {
            finish()
            return
        }
        positionMs = savedInstanceState?.getInt("positionMs") ?: 0
        video = VideoView(this)
        val frame = FrameLayout(this)
        frame.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(frame)
        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Impossibile riprodurre la ripresa. Il file è conservato.", Toast.LENGTH_LONG).show()
            true
        }
        video.setOnPreparedListener {
            video.seekTo(positionMs)
            video.start()
        }
        video.setVideoPath(file.absolutePath)
    }

    override fun onPause() {
        if (::video.isInitialized) {
            positionMs = video.currentPosition
            video.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("positionMs", if (::video.isInitialized) video.currentPosition else positionMs)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }

    companion object { const val FILE_NAME = "recordingFileName" }
}
