package it.ecamuto.yourec.android.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException

data class RecordedMedia(
    val file: File,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val videoMime: String,
    val audioMime: String,
)

/** Reads the actual muxed tracks, rather than treating requested settings as evidence. */
fun inspectRecording(file: File): RecordedMedia {
    val extractor = MediaExtractor()
    var video: MediaFormat? = null
    var audio: MediaFormat? = null
    try {
        extractor.setDataSource(file.absolutePath)
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) video = format
            if (mime.startsWith("audio/")) audio = format
        }
    } finally {
        extractor.release()
    }
    val videoTrack = video ?: throw IOException("Il file non contiene una traccia video.")
    val audioTrack = audio ?: throw IOException("Il file non contiene una traccia audio.")
    val metadata = MediaMetadataRetriever()
    try {
        metadata.setDataSource(file.absolutePath)
        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0
        if (duration <= 0) throw IOException("La ripresa non ha una durata valida.")
        return RecordedMedia(
            file = file,
            durationMs = duration,
            width = videoTrack.getInteger(MediaFormat.KEY_WIDTH),
            height = videoTrack.getInteger(MediaFormat.KEY_HEIGHT),
            rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0,
            videoMime = videoTrack.getString(MediaFormat.KEY_MIME).orEmpty(),
            audioMime = audioTrack.getString(MediaFormat.KEY_MIME).orEmpty(),
        )
    } finally {
        metadata.release()
    }
}
