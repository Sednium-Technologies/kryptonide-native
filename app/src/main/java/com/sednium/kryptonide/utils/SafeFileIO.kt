package com.sednium.kryptonide.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object SafeFileIO {

    enum class FailureStage {
        TEMP_CREATION,
        TEMP_WRITE,
        FLUSH_SYNC,
        ATOMIC_MOVE,
        BACKUP_CREATION,
        REPLACEMENT,
        BACKUP_RESTORATION
    }

    @Volatile
    internal var failureInjectionStage: FailureStage? = null

    @Throws(IOException::class)
    fun writeAtomically(destination: File, content: String, charset: Charset = StandardCharsets.UTF_8) {
        writeAtomically(destination, content.toByteArray(charset))
    }

    @Throws(IOException::class)
    fun writeAtomically(destination: File, bytes: ByteArray) {
        writeAtomicallyInternal(destination, bytes, null)
    }

    /**
     * Testing hook to simulate I/O interruption right before rename/atomic move.
     */
    internal fun writeAtomicallyWithHook(
        destination: File,
        content: String,
        charset: Charset = StandardCharsets.UTF_8,
        preMoveHook: (() -> Unit)? = null
    ) {
        writeAtomicallyInternal(destination, content.toByteArray(charset), preMoveHook)
    }

    private fun writeAtomicallyInternal(
        destination: File,
        bytes: ByteArray,
        preMoveHook: (() -> Unit)?
    ) {
        val parentDir = destination.canonicalFile.parentFile
            ?: throw IOException("Destination file has no parent directory: ${destination.path}")

        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Failed to create parent directories for: ${destination.path}")
        }

        if (failureInjectionStage == FailureStage.TEMP_CREATION) {
            throw IOException("Injected: Temp file creation failed")
        }

        val tempFile = File(parentDir, ".${destination.name}.tmp_${UUID.randomUUID()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                if (failureInjectionStage == FailureStage.TEMP_WRITE) {
                    throw IOException("Injected: Temp write failed")
                }
                fos.write(bytes)
                if (failureInjectionStage == FailureStage.FLUSH_SYNC) {
                    throw IOException("Injected: Flush or sync failure")
                }
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {
                    // Ignored if filesystem/underlying storage doesn't support sync
                }
            }

            preMoveHook?.invoke()

            // Attempt atomic move first
            val sourcePath = tempFile.toPath()
            val destPath = destination.toPath()
            var moveSucceeded = false

            try {
                if (failureInjectionStage == FailureStage.ATOMIC_MOVE ||
                    failureInjectionStage == FailureStage.BACKUP_CREATION ||
                    failureInjectionStage == FailureStage.REPLACEMENT ||
                    failureInjectionStage == FailureStage.BACKUP_RESTORATION) {
                    throw AtomicMoveNotSupportedException(sourcePath.toString(), destPath.toString(), "Injected: Atomic move not supported")
                }
                Files.move(
                    sourcePath,
                    destPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                moveSucceeded = true
            } catch (_: AtomicMoveNotSupportedException) {
                if (failureInjectionStage != FailureStage.BACKUP_CREATION &&
                    failureInjectionStage != FailureStage.REPLACEMENT &&
                    failureInjectionStage != FailureStage.BACKUP_RESTORATION) {
                    try {
                        Files.move(
                            sourcePath,
                            destPath,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        moveSucceeded = true
                    } catch (_: IOException) {
                        moveSucceeded = false
                    }
                }
            } catch (_: IOException) {
                moveSucceeded = false
            }

            if (!moveSucceeded) {
                // Final fallback if Files.move throws on certain Android / Linux filesystems.
                // Safely back up destination first so failure never leaves the original file destroyed.
                if (destination.exists()) {
                    val backupFile = File(parentDir, ".${destination.name}.bak_${UUID.randomUUID()}")
                    if (failureInjectionStage == FailureStage.BACKUP_CREATION || !destination.renameTo(backupFile)) {
                        throw IOException("Failed to create backup for replacement of: ${destination.path}")
                    }
                    val replaceSuccess = if (failureInjectionStage == FailureStage.REPLACEMENT || failureInjectionStage == FailureStage.BACKUP_RESTORATION) {
                        false
                    } else {
                        tempFile.renameTo(destination)
                    }

                    if (replaceSuccess) {
                        backupFile.delete()
                    } else {
                        if (failureInjectionStage == FailureStage.BACKUP_RESTORATION) {
                            throw IOException("Critical failure: Failed to replace destination and failed to restore original from backup at ${backupFile.path}")
                        }
                        val restored = backupFile.renameTo(destination)
                        if (!restored) {
                            throw IOException("Critical failure: Failed to replace destination and failed to restore original from backup at ${backupFile.path}")
                        }
                        throw IOException("Failed to rename temporary file to destination; restored original: ${destination.path}")
                    }
                } else {
                    if (!tempFile.renameTo(destination)) {
                        throw IOException("Failed to rename temporary file to destination: ${destination.path}")
                    }
                }
            }

            // Sync parent directory metadata where supported for full crash durability
            try {
                java.nio.channels.FileChannel.open(parentDir.toPath(), java.nio.file.StandardOpenOption.READ).use { ch ->
                    ch.force(true)
                }
            } catch (_: Exception) {
                // Ignored on virtual / non-POSIX filesystems without directory fsync support
            }
        } catch (t: Throwable) {
            // Clean up temporary file on any error
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
        }
    }
}
