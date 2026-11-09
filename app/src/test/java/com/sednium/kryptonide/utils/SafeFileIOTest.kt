package com.sednium.kryptonide.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeFileIOTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWriteAtomicallyCreatesFileSuccessfully() {
        val target = File(tempFolder.root, "atomic_test.txt")
        val content = "Line 1\nLine 2\nLine 3 with unicode: 🚀"

        SafeFileIO.writeAtomically(target, content)

        assertTrue(target.exists())
        assertEquals(content, target.readText())
    }

    @Test
    fun testWriteAtomicallyReplacesExistingFileAtomically() {
        val target = tempFolder.newFile("replace_me.txt").apply {
            writeText("Original Content")
        }

        val newContent = "Updated Content Completely"
        SafeFileIO.writeAtomically(target, newContent)

        assertEquals(newContent, target.readText())
    }

    @Test
    fun testWriteAtomicallyPreservesOriginalOnFailedWrite() {
        val target = tempFolder.newFile("preserved.txt").apply {
            writeText("Critical Data Must Not Be Lost")
        }

        // Attempt write where target directory becomes read-only or invalid
        // Or testing with exception during stream writing
        try {
            SafeFileIO.writeAtomicallyWithHook(target, "Broken Content") {
                throw IllegalStateException("Simulated disk error or out-of-space")
            }
            fail("Expected exception was not thrown")
        } catch (e: Exception) {
            assertEquals("Simulated disk error or out-of-space", e.message)
        }

        // Original file must remain 100% intact
        assertEquals("Critical Data Must Not Be Lost", target.readText())

        // Verify no leftover .tmp files
        val tmpFiles = target.parentFile?.listFiles { _, name -> name.contains(".tmp_") }
        assertTrue("Temp files must be cleaned up on failure", tmpFiles == null || tmpFiles.isEmpty())
    }

    @Test
    fun testWriteAtomicallyBytesPreservesBinaryExactness() {
        val target = File(tempFolder.root, "binary.bin")
        val binaryData = ByteArray(1024) { index -> (index % 256).toByte() }

        SafeFileIO.writeAtomically(target, binaryData)

        assertTrue(target.exists())
        assertArrayEquals(binaryData, target.readBytes())
    }

    @Test
    fun testConcurrentAtomicWritesToSameFileDoNotCorrupt() {
        val target = tempFolder.newFile("concurrent.txt")
        val threads = mutableListOf<Thread>()
        val payloads = (0 until 10).map { i -> "Payload-$i-${"x".repeat(1000)}" }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        for (payload in payloads) {
            threads.add(Thread {
                try {
                    SafeFileIO.writeAtomically(target, payload)
                } catch (t: Throwable) {
                    errors.add(t)
                }
            })
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("No exceptions during concurrent writes: $errors", errors.isEmpty())
        val finalContent = target.readText()
        assertTrue("Destination must contain one valid full payload", payloads.contains(finalContent))
    }

    @Test
    fun testFailureInjectionTempCreationPreservesOriginal() {
        val target = tempFolder.newFile("fail_temp_create.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.TEMP_CREATION
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected exception")
        } catch (_: java.io.IOException) {}
        finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("Original V1", target.readText())
    }

    @Test
    fun testFailureInjectionTempWritePreservesOriginal() {
        val target = tempFolder.newFile("fail_temp_write.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.TEMP_WRITE
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected exception")
        } catch (_: java.io.IOException) {}
        finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("Original V1", target.readText())
    }

    @Test
    fun testFailureInjectionFlushSyncPreservesOriginal() {
        val target = tempFolder.newFile("fail_sync.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.FLUSH_SYNC
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected exception")
        } catch (_: java.io.IOException) {}
        finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("Original V1", target.readText())
    }

    @Test
    fun testFailureInjectionAtomicMoveFallsBackSuccessfully() {
        val target = tempFolder.newFile("fallback_move.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.ATOMIC_MOVE
        try {
            SafeFileIO.writeAtomically(target, "New V2")
        } finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("New V2", target.readText())
    }

    @Test
    fun testFailureInjectionBackupCreationPreservesOriginal() {
        val target = tempFolder.newFile("fail_backup_create.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.BACKUP_CREATION
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected exception")
        } catch (_: java.io.IOException) {}
        finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("Original V1", target.readText())
    }

    @Test
    fun testFailureInjectionReplacementRestoresOriginalFromBackup() {
        val target = tempFolder.newFile("fail_replace.txt").apply { writeText("Original V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.REPLACEMENT
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected exception")
        } catch (e: java.io.IOException) {
            assertTrue("Should report restored original", e.message?.contains("restored original") == true)
        } finally {
            SafeFileIO.failureInjectionStage = null
        }
        assertEquals("Original V1", target.readText())
    }

    @Test
    fun testFailureInjectionBackupRestorationFailurePreservesBackupFile() {
        val target = tempFolder.newFile("fail_restore.txt").apply { writeText("Critical V1") }
        SafeFileIO.failureInjectionStage = SafeFileIO.FailureStage.BACKUP_RESTORATION
        try {
            SafeFileIO.writeAtomically(target, "New V2")
            fail("Expected critical exception")
        } catch (e: java.io.IOException) {
            assertTrue("Must report critical failure", e.message?.contains("Critical failure") == true)
        } finally {
            SafeFileIO.failureInjectionStage = null
        }
        val backupFiles = target.parentFile?.listFiles { _, name -> name.contains(".bak_") }
        assertTrue("Backup file must be preserved when restoration fails", backupFiles != null && backupFiles.isNotEmpty())
        assertEquals("Critical V1", backupFiles!!.first().readText())
    }
}
