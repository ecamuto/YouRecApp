package it.ecamuto.yourec.android.capture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CaptureStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun onlyPublishedRecordingsSurviveInTheCompletedList() {
        val root = temp.newFolder()
        val store = CaptureStore(root)
        val successful = store.allocate().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val interrupted = store.allocate().apply { writeBytes(byteArrayOf(4, 5)) }
        assertTrue(store.recordings().isEmpty())
        val published = store.publish(successful)

        val reopened = CaptureStore(root)
        assertEquals(listOf(published), reopened.recordings())
        assertEquals(1, reopened.pendingCount())
        assertArrayEquals(byteArrayOf(4, 5), interrupted.readBytes())
        assertFalse(successful.exists())
    }

    @Test fun failedPublicationPreservesBothExistingAndPendingFiles() {
        val root = temp.newFolder()
        val store = CaptureStore(root)
        val pending = store.allocate().apply { writeBytes(byteArrayOf(8)) }
        val existing = File(root, "completed/${pending.name}").apply { writeBytes(byteArrayOf(9)) }
        assertThrows(IOException::class.java) { store.publish(pending) }
        assertArrayEquals(byteArrayOf(8), pending.readBytes())
        assertArrayEquals(byteArrayOf(9), existing.readBytes())
    }

    @Test fun emptyAndForeignFilesCannotBePublished() {
        val store = CaptureStore(temp.newFolder())
        assertThrows(IOException::class.java) { store.publish(store.allocate().apply { createNewFile() }) }
        val foreign = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { store.publish(foreign) }
        assertTrue(foreign.exists())
    }

    @Test fun missingArchiveIsReportedInsteadOfAppearingEmpty() {
        val root = temp.newFolder()
        val store = CaptureStore(root)
        assertTrue(File(root, "completed").delete())
        assertThrows(IOException::class.java) { store.recordings() }
    }
}
