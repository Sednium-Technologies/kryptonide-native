package com.sednium.kryptonide.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.storage.AppDatabase
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
class ConservativeSyncTest {

    private lateinit var app: Application
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ProjectRepository(AppDatabase.getInstance(app))
    }

    @Test
    fun testMissingDirectoryDoesNotWipeDatabase() = runBlocking {
        val proj = repo.createProject("GhostProject", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        assertTrue(projDir.exists())

        // Create a file in project
        val testFile = File(projDir, "critical_code.kt")
        testFile.writeText("fun secret() = 42")
        val initialNodes = repo.resyncProjectFromDisk(proj.id)
        assertTrue(initialNodes.any { it.name == "critical_code.kt" })

        // Simulate SD card unmounted or directory deleted outside app
        projDir.deleteRecursively()
        assertFalse(projDir.exists())

        // Resync must NOT wipe database records when directory is missing
        val preservedNodes = repo.resyncProjectFromDisk(proj.id)
        assertTrue("Database nodes must be preserved when directory is missing", preservedNodes.any { it.name == "critical_code.kt" })

        // getAllProjects() must NOT delete project from database either!
        val allProjects = repo.getAllProjects()
        assertTrue("Project must remain in database", allProjects.any { it.id == proj.id })
    }

    @Test
    fun testDuplicateBasenamesInDifferentFoldersPreserved() = runBlocking {
        val proj = repo.createProject("NestedApp", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)

        // src/utils.kt and test/utils.kt
        val srcDir = File(projDir, "src").apply { mkdirs() }
        val testDir = File(projDir, "test").apply { mkdirs() }
        File(srcDir, "utils.kt").writeText("package src")
        File(testDir, "utils.kt").writeText("package test")

        val nodes = repo.resyncProjectFromDisk(proj.id)
        val utilsNodes = nodes.filter { it.name == "utils.kt" }

        assertEquals("Both duplicate-named files in different directories must be preserved", 2, utilsNodes.size)
        val contents = utilsNodes.map { it.content }.toSet()
        assertTrue(contents.contains("package src"))
        assertTrue(contents.contains("package test"))
    }

    @Test
    fun testGenuinelyEmptyProjectSynchronizesDeletions() = runBlocking {
        val proj = repo.createProject("EmptySyncApp", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)
        val file1 = File(projDir, "to_delete.kt").apply { writeText("val a = 1") }

        val nodesBefore = repo.resyncProjectFromDisk(proj.id)
        assertTrue(nodesBefore.any { it.name == "to_delete.kt" })

        // User legitimately removes all files from disk to make project directory genuinely empty
        projDir.listFiles()?.forEach { it.deleteRecursively() }
        assertTrue(projDir.exists())
        assertEquals(0, projDir.listFiles()?.size ?: -1)

        val nodesAfter = repo.resyncProjectFromDisk(proj.id)
        assertFalse("Legitimate deletion must synchronize when directory is genuinely empty", nodesAfter.any { it.name == "to_delete.kt" })
        assertTrue("Root project node must remain", nodesAfter.any { it.parentId == null })
    }
}
