package com.sednium.kryptonide.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.utils.BinaryFileDetector
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BinaryPreservationTest {

    private lateinit var app: Application
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ProjectRepository(AppDatabase.getInstance(app))
    }

    @Test
    fun testBinaryFilePreservedByteForByte() = runBlocking {
        val proj = repo.createProject("BinarySafeProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        assertTrue(projDir.exists())

        // Create a binary PNG asset on disk
        val pngFile = File(projDir, "icon.png")
        val originalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x12, 0x34)
        pngFile.writeBytes(originalBytes)

        // Resync project from disk
        val nodes = repo.resyncProjectFromDisk(proj.id)
        val pngNode = nodes.find { it.name == "icon.png" }

        assertNotNull("Binary node should be discovered", pngNode)
        assertEquals(FileType.FILE, pngNode!!.type)
        assertNull("Binary file content should be null (not decoded as string)", pngNode.content)

        // Verify disk content was NEVER altered or corrupted
        assertArrayEquals("Disk bytes must match original byte-for-byte", originalBytes, pngFile.readBytes())

        // Now simulate markFileSaved on another file or trigger sync
        repo.markFileSaved(proj.id, pngNode.id, "")
        // Even if markFileSaved was invoked, because content was null or file is binary, bytes must remain untouched
        assertArrayEquals("Disk bytes must still match original after save attempt", originalBytes, pngFile.readBytes())
    }

    @Test
    fun testLargeFileExceedingTextLimitHasNullContent() = runBlocking {
        val proj = repo.createProject("LargeFileProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        val largeFile = File(projDir, "huge.txt")

        // 2.2 MB text file
        val chunk = ByteArray(1024 * 64) { 'A'.code.toByte() }
        largeFile.outputStream().use { fos ->
            repeat(35) { fos.write(chunk) }
        }

        val nodes = repo.resyncProjectFromDisk(proj.id)
        val largeNode = nodes.find { it.name == "huge.txt" }

        assertNotNull(largeNode)
        assertNull("Large file exceeding 2MB must have null content to prevent OOM", largeNode!!.content)
    }

    @Test
    fun testSyncFullProjectDoesNotTruncateBinaryFiles() = runBlocking {
        val proj = repo.createProject("BinaryFullSyncProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        val pngFile = File(projDir, "logo.png")
        val originalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xAA.toByte(), 0xBB.toByte())
        pngFile.writeBytes(originalBytes)

        repo.resyncProjectFromDisk(proj.id)

        // Upserting another file triggers syncFullProject
        repo.upsertFile(
            com.sednium.kryptonide.data.model.FileNode(
                id = "new_file_id",
                projectId = proj.id,
                name = "Readme.txt",
                type = FileType.FILE,
                parentId = null,
                content = "Hello World"
            )
        )

        // Verify logo.png is STILL 10 bytes and NOT truncated to 0 bytes!
        assertArrayEquals("Binary bytes must remain intact after syncFullProject", originalBytes, pngFile.readBytes())
    }

    @Test
    fun testRenameBinaryFilePreservesBytesAndRenamesOnDisk() = runBlocking {
        val proj = repo.createProject("BinaryRenameProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        val pngFile = File(projDir, "logo.png")
        val originalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x11, 0x22)
        pngFile.writeBytes(originalBytes)

        val nodes = repo.resyncProjectFromDisk(proj.id)
        val pngNode = nodes.find { it.name == "logo.png" }!!

        // Rename file on disk + DB via repo.renameFile
        val renamed = repo.renameFile(proj.id, pngNode.id, "brand.png")
        assertTrue("renameFile should succeed", renamed)

        val newFile = File(projDir, "brand.png")
        assertFalse("Old file logo.png must not exist on disk", pngFile.exists())
        assertTrue("New file brand.png must exist on disk", newFile.exists())
        assertArrayEquals("Binary bytes must remain 100% byte-identical after rename", originalBytes, newFile.readBytes())

        val dbNode = repo.getFiles(proj.id).find { it.id == pngNode.id }!!
        assertEquals("brand.png", dbNode.name)
        assertNull("Binary content must remain null", dbNode.content)
    }

    @Test
    fun testMoveBinaryFilePreservesBytesAndMovesOnDisk() = runBlocking {
        val proj = repo.createProject("BinaryMoveProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        val assetsDir = File(projDir, "assets").apply { mkdirs() }
        val pngFile = File(projDir, "icon.png")
        val originalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x33, 0x44)
        pngFile.writeBytes(originalBytes)

        val nodes = repo.resyncProjectFromDisk(proj.id)
        val pngNode = nodes.find { it.name == "icon.png" }!!
        val assetsNode = nodes.find { it.name == "assets" }!!

        // Move icon.png into assets folder
        val moved = repo.moveFile(proj.id, pngNode.id, assetsNode.id)
        assertTrue("moveFile should succeed", moved)

        val movedFile = File(assetsDir, "icon.png")
        assertFalse("Old location icon.png must not exist on disk", pngFile.exists())
        assertTrue("New location assets/icon.png must exist on disk", movedFile.exists())
        assertArrayEquals("Binary bytes must remain 100% byte-identical after move", originalBytes, movedFile.readBytes())

        val dbNode = repo.getFiles(proj.id).find { it.id == pngNode.id }!!
        assertEquals(assetsNode.id, dbNode.parentId)
        assertNull("Binary content must remain null", dbNode.content)
    }
}
