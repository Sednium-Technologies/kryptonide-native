package com.sednium.kryptonide.ui.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.repository.ProjectRepository
import com.sednium.kryptonide.service.FileWatcherService
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
class FileWatcherLifecycleTest {

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
        FileWatcherService.stopWatching()
        Dispatchers.resetMain()
    }

    @Test
    fun testStartFileWatcherIsIdempotentAndCancelsPriorCollector() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("ProjectA", ProjectTemplate.BLANK)
        testScheduler.runCurrent()

        viewModel.startFileWatcher(proj.id, proj.name)
        val job1 = viewModel.getWatcherCollectorJob()
        val session1 = viewModel.getCurrentWatcherSessionId()

        assertNotNull("Watcher collector job must be active", job1)
        assertTrue(job1!!.isActive)
        assertNotNull(session1)

        // Starting again must cancel job1 and establish a new session
        viewModel.startFileWatcher(proj.id, proj.name)
        val job2 = viewModel.getWatcherCollectorJob()
        val session2 = viewModel.getCurrentWatcherSessionId()

        assertTrue("Prior collector job must be cancelled", job1.isCancelled)
        assertNotNull(job2)
        assertTrue(job2!!.isActive)
        assertNotEquals(session1, session2)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testStopFileWatcherCancelsCollectorJob() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("ProjectB", ProjectTemplate.BLANK)
        testScheduler.runCurrent()

        viewModel.startFileWatcher(proj.id, proj.name)
        val job = viewModel.getWatcherCollectorJob()
        assertNotNull(job)
        assertTrue(job!!.isActive)

        viewModel.stopFileWatcher(proj.name)
        testScheduler.runCurrent()

        assertNull("Collector job should be cleared on stop", viewModel.getWatcherCollectorJob())
        assertNull("Session ID should be cleared on stop", viewModel.getCurrentWatcherSessionId())
        assertTrue("Job should be cancelled", job.isCancelled)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testUnloadProjectStopsFileWatcher() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("ProjectC", ProjectTemplate.BLANK)
        testScheduler.runCurrent()

        viewModel.startFileWatcher(proj.id, proj.name)
        val job = viewModel.getWatcherCollectorJob()
        assertNotNull(job)
        assertTrue(job!!.isActive)

        viewModel.unloadProject()
        testScheduler.runCurrent()

        assertNull("Watcher job should be cancelled and null on unload", viewModel.getWatcherCollectorJob())
        assertNull("Session ID should be null on unload", viewModel.getCurrentWatcherSessionId())
        assertTrue(job.isCancelled)
    }

    @Test
    fun testWatcherBufferOverflowTriggersRecoveryRescanAndGuaranteesEventualConsistency() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("OverflowProj", ProjectTemplate.BLANK)
        val projDir = repo.getProjectDir(proj)

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var loadAttempts = 0
        while (viewModel.state.value.files.isEmpty() && loadAttempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        FileWatcherService.hasDroppedEvents.set(false)

        // Rapidly emit 100 events to overflow the 64 capacity buffer without giving collector time to drain
        for (i in 1..100) {
            FileWatcherService.safeEmit(
                com.sednium.kryptonide.service.DiskSyncEvent.FileCreated(
                    absolutePath = "${projDir.absolutePath}/dummy_$i.tmp",
                    projectName = proj.name
                ),
                projectName = proj.name
            )
        }

        // Verify that buffer overflow was detected
        assertTrue("Overflow must be detected when bursting 100 events into 64-capacity buffer", FileWatcherService.hasDroppedEvents.get())

        // Create a critical file on disk that was created during the burst
        val criticalFile = java.io.File(projDir, "reconciled_after_overflow.kt").apply {
            writeText("val recovered = true")
        }

        // Allow burst settling delay and recovery rescan to fire
        var attempts = 0
        while (viewModel.state.value.files.values.none { it.name == "reconciled_after_overflow.kt" } && attempts++ < 30) {
            Thread.sleep(30)
            testScheduler.runCurrent()
        }

        val recoveredNode = viewModel.state.value.files.values.find { it.name == "reconciled_after_overflow.kt" }
        assertNotNull("Eventual consistency guarantee: dropped events must be recovered via RescanRequired full reconciliation", recoveredNode)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }
}
