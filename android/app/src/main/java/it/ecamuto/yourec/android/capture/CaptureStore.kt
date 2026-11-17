package it.ecamuto.yourec.android.capture

import java.io.File
import java.io.IOException
import java.util.UUID

/** Prototype footage only. This is deliberately not the YouRec project format. */
class CaptureStore(root: File) {
    private val pending = File(root, "pending")
    private val completed = File(root, "completed")

    init {
        for (directory in listOf(pending, completed)) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Impossibile creare l'archivio delle riprese.")
            }
        }
    }

    fun allocate(): File = File(pending, "${UUID.randomUUID()}.mp4")

    /** Called only after a successful Finalize AND track validation, never on Stop. */
    fun publish(file: File): File {
        require(file.parentFile?.canonicalFile == pending.canonicalFile)
        if (!file.isFile || file.length() == 0L) throw IOException("Ripresa vuota o assente.")
        val destination = File(completed, file.name)
        if (destination.exists() || !file.renameTo(destination)) {
            throw IOException("Impossibile completare il salvataggio. Il file provvisorio è conservato.")
        }
        return destination
    }

    fun recordings(): List<File> = list(completed).filter { it.isFile && it.extension == "mp4" }
        .sortedByDescending { it.lastModified() }

    // Incomplete/error files are retained. A future recovery prototype will inspect them.
    fun pendingCount(): Int = list(pending).count { it.isFile }

    private fun list(directory: File): Array<File> = directory.listFiles()
        ?: throw IOException("Impossibile leggere l'archivio delle riprese.")
}
