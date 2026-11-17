package it.ecamuto.yourec.android.capture

enum class CapturePhase { IDLE, STARTING, RECORDING, PAUSING, PAUSED, RESUMING, STOPPING, SAVING }

data class CaptureState(
    val ready: Boolean = false,
    val front: Boolean = false,
    val canSwitch: Boolean = false,
    val phase: CapturePhase = CapturePhase.IDLE,
    val elapsedNs: Long = 0,
    val bytes: Long = 0,
    val message: String = "Apertura fotocamera…",
    val recordings: List<RecordedMedia> = emptyList(),
    val pendingCount: Int = 0,
    val unreadableCount: Int = 0,
    val libraryError: String? = null,
) {
    val busy: Boolean get() = phase != CapturePhase.IDLE
}

