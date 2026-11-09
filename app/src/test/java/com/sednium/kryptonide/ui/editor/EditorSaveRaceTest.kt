package com.sednium.kryptonide.ui.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.repository.ProjectRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSaveRaceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        repo = ProjectRepository(AppDatabase.getInstance(app))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testFileRevisionIncrementsOnEachUpdate() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("TestProject", ProjectTemplate.BLANK)
        val fileId = "test_file_rev"
        repo.upsertFile(
            FileNode(
                id = fileId,
                projectId = proj.id,
                name = "index.js",
                type = FileType.FILE,
                parentId = null,
                content = "const a = 0;",
                language = "javascript"
            )
        )

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var loadAttempts = 0
        while (viewModel.state.value.files[fileId] == null && loadAttempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        assertEquals(0L, viewModel.getFileRevision(fileId))

        viewModel.updateContent(fileId, "const a = 1;")
        assertEquals(1L, viewModel.getFileRevision(fileId))

        viewModel.updateContent(fileId, "const a = 2;")
        assertEquals(2L, viewModel.getFileRevision(fileId))

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testSavePreservesDirtyFlagWhenIntermediateEditOccurs() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("RaceProject", ProjectTemplate.BLANK)
        val fileId = "test_script_file"
        repo.upsertFile(
            FileNode(
                id = fileId,
                projectId = proj.id,
                name = "script.js",
                type = FileType.FILE,
                parentId = null,
                content = "initial content",
                language = "javascript"
            )
        )

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var loadAttempts = 0
        while (viewModel.state.value.files[fileId] == null && loadAttempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        // Edit to Rev 1
        viewModel.updateContent(fileId, "console.log('Rev 1');")
        assertEquals(1L, viewModel.getFileRevision(fileId))
        assertTrue(viewModel.state.value.files[fileId]?.isUnsaved == true)

        // Initiate save for Rev 1
        viewModel.saveFileToDisk(fileId)

        // Simulate user typing another keystroke right before I/O finishes -> Rev 2
        viewModel.updateContent(fileId, "console.log('Rev 1 - more edits');")
        assertEquals(2L, viewModel.getFileRevision(fileId))

        // Let the pending save finish on Dispatchers.IO
        var save1Attempts = 0
        while (save1Attempts++ < 10) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        // The document MUST still be marked unsaved because an edit occurred while Rev 1 was being written!
        val fileAfterSave = viewModel.state.value.files[fileId]
        assertNotNull(fileAfterSave)
        assertTrue(
            "File must remain unsaved when edited while save is in flight",
            fileAfterSave!!.isUnsaved
        )

        // Now save again with no intermediate edits
        viewModel.saveFileToDisk(fileId)
        var save2Attempts = 0
        while (viewModel.state.value.files[fileId]?.isUnsaved == true && save2Attempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        // Now it should be cleanly saved
        val finalFile = viewModel.state.value.files[fileId]
        assertNotNull(finalFile)
        assertFalse(
            "File must be marked saved when no intermediate edits occur",
            finalFile!!.isUnsaved
        )

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testBinaryFileSaveIsIgnoredAndDoesNotCorruptContent() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("BinarySaveIgnoreProj", ProjectTemplate.BLANK)
        val binaryId = "binary_logo_node"
        repo.upsertFile(
            FileNode(
                id = binaryId,
                projectId = proj.id,
                name = "logo.png",
                type = FileType.FILE,
                parentId = null,
                content = null,
                language = "plaintext"
            )
        )

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var attempts = 0
        while (viewModel.state.value.files[binaryId] == null && attempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        val binaryNode = viewModel.state.value.files[binaryId]
        assertNotNull(binaryNode)
        assertNull(binaryNode!!.content)

        // Attempt to save binary file
        viewModel.saveFileToDisk(binaryId)
        testScheduler.runCurrent()

        // Content must STILL be null, not converted to empty string
        val afterSave = viewModel.state.value.files[binaryId]
        assertNotNull(afterSave)
        assertNull("Binary file content must remain null after saveFileToDisk", afterSave!!.content)

        val dbFiles = repo.getFiles(proj.id)
        val dbBinary = dbFiles.find { it.id == binaryId }
        assertNotNull(dbBinary)
        assertNull("Database content must remain null", dbBinary!!.content)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testOlderSaveSnapshotCannotOverwriteNewerSaveOnDisk() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("OutdatedSaveProj", ProjectTemplate.BLANK)
        val fileId = "test_ordering_file"
        val projDir = repo.getProjectDir(proj)
        val physicalFile = File(projDir, "doc.txt")
        repo.upsertFile(
            FileNode(
                id = fileId,
                projectId = proj.id,
                name = "doc.txt",
                type = FileType.FILE,
                parentId = null,
                content = "Revision 0",
                language = "plaintext"
            )
        )

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var attempts = 0
        while (viewModel.state.value.files[fileId] == null && attempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        // 1. Save A captures Revision 1
        viewModel.updateContent(fileId, "Content from Revision 1")
        val saveA_rev = viewModel.getFileRevision(fileId)
        val saveA_content = viewModel.state.value.files[fileId]!!.content!!
        assertEquals(1L, saveA_rev)

        // 2. Save B captures Revision 2
        viewModel.updateContent(fileId, "Content from Revision 2")
        val saveB_rev = viewModel.getFileRevision(fileId)
        val saveB_content = viewModel.state.value.files[fileId]!!.content!!
        assertEquals(2L, saveB_rev)

        // 3. Save B is allowed to complete BEFORE Save A
        viewModel.saveSnapshotToDisk(fileId, saveB_content, saveB_rev)
        var saveBWait = 0
        while (viewModel.state.value.files[fileId]?.isUnsaved == true && saveBWait++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }
        assertEquals("Content from Revision 2", physicalFile.readText())
        assertFalse("Document marked clean when Save B matches editor revision", viewModel.state.value.files[fileId]!!.isUnsaved)

        // 4. Save A is now dispatched (holding older revision 1)
        viewModel.saveSnapshotToDisk(fileId, saveA_content, saveA_rev)
        var saveAWait = 0
        while (saveAWait++ < 10) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        // 4. Verify that Revision 1 CANNOT overwrite Revision 2 on disk!
        assertEquals("Disk content must remain Revision 2 and NOT be overwritten by older Save A", "Content from Revision 2", physicalFile.readText())

        // 5. Verify that document is marked clean only when actual disk content matches latest editor revision
        assertFalse(viewModel.state.value.files[fileId]!!.isUnsaved)

        // Now edit to Revision 3 without saving
        viewModel.updateContent(fileId, "Content from Revision 3")
        assertTrue("Document must be dirty when editor has newer edits than disk", viewModel.state.value.files[fileId]!!.isUnsaved)

        // Dispatching stale Save A (rev 1) again
        viewModel.saveSnapshotToDisk(fileId, saveA_content, saveA_rev)
        testScheduler.runCurrent()
        assertTrue("Document must remain dirty because disk (rev 2) != editor (rev 3)", viewModel.state.value.files[fileId]!!.isUnsaved)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }
}
