package com.sednium.kryptonide.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BinaryFileDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRecognizesKnownTextExtensions() {
        val textFile = tempFolder.newFile("main.kt").apply {
            writeText("package com.example\nfun main() { println(\"Hello\") }")
        }
        assertFalse(BinaryFileDetector.isBinaryFile(textFile))
        assertTrue(BinaryFileDetector.isTextFile(textFile))
    }

    @Test
    fun testRecognizesKnownBinaryExtensions() {
        val pngFile = tempFolder.newFile("logo.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        }
        assertTrue(BinaryFileDetector.isBinaryFile(pngFile))
        assertFalse(BinaryFileDetector.isTextFile(pngFile))
    }

    @Test
    fun testDetectsBinaryFromNullBytesInExtensionlessFile() {
        val extensionlessBinary = tempFolder.newFile("data_blob").apply {
            writeBytes(byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00, 0x57, 0x6F, 0x72, 0x6C, 0x64))
        }
        assertTrue(BinaryFileDetector.isBinaryFile(extensionlessBinary))
    }

    @Test
    fun testDetectsTextInExtensionlessFile() {
        val extensionlessText = tempFolder.newFile("LICENSE").apply {
            writeText("Apache License 2.0\nLicensed under the Apache License")
        }
        assertFalse(BinaryFileDetector.isBinaryFile(extensionlessText))
        assertTrue(BinaryFileDetector.isTextFile(extensionlessText))
    }

    @Test
    fun testEmptyFileTreatedAsText() {
        val emptyFile = tempFolder.newFile("empty.txt")
        assertFalse(BinaryFileDetector.isBinaryFile(emptyFile))
        assertTrue(BinaryFileDetector.isTextFile(emptyFile))
    }

    @Test
    fun testDetectsInvalidUtf8Sequences() {
        // Invalid UTF-8 multibyte sequence (e.g. lone continuation bytes or invalid lead)
        val badUtf8 = tempFolder.newFile("corrupted.dat").apply {
            writeBytes(byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0xFF.toByte(), 0xFE.toByte()))
        }
        assertTrue(BinaryFileDetector.isBinaryFile(badUtf8))
    }

    @Test
    fun testDetectsFilesExceedingTextLimit() {
        val largeFile = tempFolder.newFile("large.log")
        // Create file larger than 2MB limit (e.g. 2.1 MB)
        val chunk = ByteArray(1024 * 64) { 'A'.code.toByte() }
        largeFile.outputStream().use { fos ->
            repeat(35) { // 35 * 64KB = 2240 KB > 2048 KB
                fos.write(chunk)
            }
        }
        assertTrue(BinaryFileDetector.isExceedingTextLimit(largeFile))
    }
}
