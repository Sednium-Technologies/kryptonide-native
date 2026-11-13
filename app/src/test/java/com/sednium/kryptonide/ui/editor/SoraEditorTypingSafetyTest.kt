package com.sednium.kryptonide.ui.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.repository.ProjectRepository
import com.sednium.kryptonide.data.storage.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
class SoraEditorTypingSafetyTest {

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
    fun testMarkUnsavedSetsDirtyWithoutIncrementingRevisionOrAlteringContent() = runTest(testDispatcher) {
        val viewModel = EditorViewModel(app)
        val proj = repo.createProject("TypingTest", ProjectTemplate.BLANK)
        val fileId = "typing_file_1"
        repo.upsertFile(
            FileNode(
                id = fileId,
                projectId = proj.id,
                name = "main.py",
                type = FileType.FILE,
                parentId = null,
                content = "print('hello')",
                language = "python",
                isUnsaved = false
            )
        )

        viewModel.loadProject(proj.id, enableAutoSave = false)
        var loadAttempts = 0
        while (viewModel.state.value.files[fileId] == null && loadAttempts++ < 20) {
            Thread.sleep(25)
            testScheduler.runCurrent()
        }

        val initialFile = viewModel.state.value.files[fileId]!!
        assertFalse(initialFile.isUnsaved ?: false)
        assertEquals("print('hello')", initialFile.content)
        assertEquals(0L, viewModel.getFileRevision(fileId))

        // Mark unsaved during active typing (as onContentChanged does)
        viewModel.markUnsaved(fileId)
        testScheduler.runCurrent()

        val markedFile = viewModel.state.value.files[fileId]!!
        assertTrue(markedFile.isUnsaved == true)
        assertEquals("print('hello')", markedFile.content)
        // Revision must NOT increment on markUnsaved (only increments when content actually changes)
        assertEquals(0L, viewModel.getFileRevision(fileId))

        // Calling markUnsaved again must be idempotent and not replace state
        val stateBefore = viewModel.state.value
        viewModel.markUnsaved(fileId)
        testScheduler.runCurrent()
        assertSame(stateBefore, viewModel.state.value)

        viewModel.unloadProject()
        testScheduler.runCurrent()
    }

    @Test
    fun testSoraEditorHostDirectAccessPropsConfiguredForImeSafety() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val host = SoraEditorHost(
            context = app,
            scope = scope,
            onContentChanged = {},
            onSaveTriggered = {}
        )

        val editor = host.createView(app)
        val props = editor.props

        // Verify disallowSuggestions is FALSE so Sora Editor does not abort composing text or return 0
        assertFalse("disallowSuggestions must be false to prevent IME duplication on Gboard/Samsung Keyboard", props.disallowSuggestions)
        assertTrue("trackComposingTextOnCommit must be true", props.trackComposingTextOnCommit)
        assertTrue("minimizeComposingTextUpdate must be true", props.minimizeComposingTextUpdate)
        assertTrue("autoCompletionOnComposing must be true", props.autoCompletionOnComposing)
    }

    @Test
    fun testIsUserTypingTracksActiveInputWindow() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val host = SoraEditorHost(
            context = app,
            scope = scope,
            onContentChanged = {},
            onSaveTriggered = {}
        )

        host.createView(app)
        assertFalse("Initially user is not typing", host.isUserTyping())

        // Simulate typing timestamp update
        val editField = SoraEditorHost::class.java.getDeclaredField("lastUserEditTimeMs").apply { isAccessible = true }
        editField.setLong(host, System.currentTimeMillis())

        assertTrue("Within 1 second of edit, isUserTyping must return true", host.isUserTyping())

        // Simulate after 1.5 seconds of inactivity
        editField.setLong(host, System.currentTimeMillis() - 1500L)
        assertFalse("After 1.5 seconds of inactivity, isUserTyping must return false", host.isUserTyping())
    }

    @Test
    fun testSetContentDoesNotOverwriteWhileUserIsTyping() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val host = SoraEditorHost(
            context = app,
            scope = scope,
            onContentChanged = {},
            onSaveTriggered = {}
        )

        host.createView(app)
        host.loadFile("original buffer", "plaintext", "test.txt")
        assertEquals("original buffer", host.getContent())

        // Mark user as actively typing
        val editField = SoraEditorHost::class.java.getDeclaredField("lastUserEditTimeMs").apply { isAccessible = true }
        editField.setLong(host, System.currentTimeMillis())
        assertTrue(host.isUserTyping())

        // External setContent attempt while typing should be safely skipped
        host.setContent("clobbered buffer")
        assertEquals("Buffer must remain intact while user is actively typing", "original buffer", host.getContent())
    }

    @Test
    fun testLoadFileDoesNotClobberSameFileWhileUserIsTyping() {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val host = SoraEditorHost(
            context = app,
            scope = scope,
            onContentChanged = {},
            onSaveTriggered = {}
        )

        host.createView(app)
        host.loadFile("original code", "kotlin", "Main.kt")
        assertEquals("original code", host.getContent())

        // User is typing
        val editField = SoraEditorHost::class.java.getDeclaredField("lastUserEditTimeMs").apply { isAccessible = true }
        editField.setLong(host, System.currentTimeMillis())
        assertTrue(host.isUserTyping())

        // Redundant loadFile for the same active file during typing should be skipped
        host.loadFile("disk content", "kotlin", "Main.kt")
        assertEquals("Current file buffer must not be overwritten while user is typing", "original code", host.getContent())
    }
}
