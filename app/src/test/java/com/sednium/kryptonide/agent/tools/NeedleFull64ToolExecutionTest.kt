package com.sednium.kryptonide.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NeedleFull64ToolExecutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File

    @Before
    fun setUp() {
        projectDir = tempFolder.newFolder("krypton_workspace")
        NeedleToolExecutor.projectDirProvider = { projectDir }
        NeedleToolExecutor.registerAll()
    }

    @After
    fun tearDown() {
        AgentToolRegistry.clear()
    }

    @Test
    fun testAll64ToolsRegisteredInRegistry() {
        val tools = AgentToolRegistry.listTools()
        val toolNames = tools.map { it.name }.toSet()

        val expected64 = listOf(
            // Category 1: Workspace & Filesystem (10)
            "fs_list_tree", "fs_read_file", "fs_write_file", "fs_patch_file",
            "fs_create_file", "fs_delete_path", "fs_copy_file", "fs_move_file",
            "fs_file_info", "fs_create_dir",

            // Category 2: Search, Grep & Code Navigation (8)
            "search_grep", "search_find_files", "search_replace_all",
            "code_get_diagnostics", "code_outline_symbols", "code_find_definition",
            "code_find_references", "code_format_buffer",

            // Category 3: Terminal, Process & Environment (8)
            "terminal_exec_command", "terminal_read_last_output", "terminal_kill_process",
            "terminal_get_env", "terminal_set_env", "terminal_run_script",
            "terminal_which", "terminal_system_info",

            // Category 4: Web Preview & Browser Runtime (6)
            "preview_reload", "preview_get_console_logs", "preview_eval_js",
            "preview_clear_logs", "preview_get_url", "preview_capture_dom",

            // Category 5: Git & Version Control (10)
            "git_status", "git_diff", "git_log", "git_commit",
            "git_branch", "git_checkout", "git_pull", "git_push",
            "git_stash", "git_reset",

            // Category 6: GitHub Cloud & Issues (6)
            "github_status", "github_list_repos", "github_create_repo",
            "github_clone", "github_list_issues", "github_create_issue",

            // Category 7: Editor, Buffer & UI Interaction (6)
            "editor_open_file", "editor_get_active_context", "editor_insert_text",
            "editor_close_tab", "editor_list_tabs", "editor_save_all",

            // Category 8: Package Manager & Dependencies (4)
            "package_install", "package_list", "package_audit", "package_remove",

            // Category 9: AIF & Autonomous Semantic Memory (4)
            "aif_store_memory", "aif_query_memory", "aif_get_context", "aif_clear_memory",

            // Category 10: Model Orchestration & OTA Self-Healing (2)
            "model_orchestrate_status", "ota_check_update"
        )

        assertEquals("Expected exactly 64 core tools in specification", 64, expected64.size)

        for (toolName in expected64) {
            assertTrue("AgentToolRegistry must contain tool '$toolName'", toolNames.contains(toolName))
            val def = AgentToolRegistry.getTool(toolName)
            assertNotNull("Tool definition for '$toolName' must not be null", def)
            assertTrue("Description for '$toolName' must not be blank", def!!.description.isNotBlank())
        }

        assertTrue("Tool count must be at least 64", tools.size >= 64)
    }

    @Test
    fun testCategory1FileSystemExecution() = runBlocking {
        // 1. Create file
        val createRes = NeedleToolExecutor.execute("fs_create_file", mapOf("path" to "hello.txt", "content" to "Hello World\nLine 2"))
        assertTrue(createRes.success)
        assertTrue(File(projectDir, "hello.txt").exists())

        // 2. Read file
        val readRes = NeedleToolExecutor.execute("fs_read_file", mapOf("path" to "hello.txt", "start_line" to 1, "end_line" to 2))
        assertTrue(readRes.success)
        assertTrue(readRes.output.contains("1: Hello World"))

        // 3. Patch file
        val patchRes = NeedleToolExecutor.execute("fs_patch_file", mapOf("path" to "hello.txt", "search" to "Line 2", "replace" to "Line Patched"))
        assertTrue(patchRes.success)
        assertTrue(File(projectDir, "hello.txt").readText().contains("Line Patched"))

        // 4. File info
        val infoRes = NeedleToolExecutor.execute("fs_file_info", mapOf("path" to "hello.txt"))
        assertTrue(infoRes.success)
        assertTrue(infoRes.output.contains("hello.txt"))

        // 5. Copy file
        val copyRes = NeedleToolExecutor.execute("fs_copy_file", mapOf("source" to "hello.txt", "destination" to "hello_copy.txt"))
        assertTrue(copyRes.success)
        assertTrue(File(projectDir, "hello_copy.txt").exists())

        // 6. Move file
        val moveRes = NeedleToolExecutor.execute("fs_move_file", mapOf("source" to "hello_copy.txt", "destination" to "hello_moved.txt"))
        assertTrue(moveRes.success)
        assertTrue(File(projectDir, "hello_moved.txt").exists())
        assertFalse(File(projectDir, "hello_copy.txt").exists())

        // 7. Create dir
        val dirRes = NeedleToolExecutor.execute("fs_create_dir", mapOf("path" to "nested/sub/folder"))
        assertTrue(dirRes.success)
        assertTrue(File(projectDir, "nested/sub/folder").isDirectory)

        // 8. List tree
        val treeRes = NeedleToolExecutor.execute("fs_list_tree", mapOf("path" to "."))
        assertTrue(treeRes.success)
        assertTrue(treeRes.output.contains("hello.txt"))

        // 9. Delete path
        val delRes = NeedleToolExecutor.execute("fs_delete_path", mapOf("path" to "hello_moved.txt"))
        assertTrue(delRes.success)
        assertFalse(File(projectDir, "hello_moved.txt").exists())
    }

    @Test
    fun testCategory2SearchAndDiagnosticsExecution() = runBlocking {
        File(projectDir, "app.js").writeText("function calculateTotal(items) {\n    const sum = items.reduce((a, b) => a + b, 0);\n    return sum;\n}")

        // 1. Search grep
        val grepRes = NeedleToolExecutor.execute("search_grep", mapOf("query" to "calculateTotal"))
        assertTrue(grepRes.success)
        assertTrue(grepRes.output.contains("app.js"))

        // 2. Find files
        val findRes = NeedleToolExecutor.execute("search_find_files", mapOf("glob" to "*.js"))
        assertTrue(findRes.success)
        assertTrue(findRes.output.contains("app.js"))

        // 3. Search replace all
        val repRes = NeedleToolExecutor.execute("search_replace_all", mapOf("query" to "calculateTotal", "replace" to "computeTotal"))
        assertTrue(repRes.success)
        assertTrue(File(projectDir, "app.js").readText().contains("computeTotal"))

        // 4. Code outline symbols
        val outlineRes = NeedleToolExecutor.execute("code_outline_symbols", mapOf("path" to "app.js"))
        assertTrue(outlineRes.success)
        assertTrue(outlineRes.output.contains("computeTotal"))

        // 5. Code find definition
        val defRes = NeedleToolExecutor.execute("code_find_definition", mapOf("symbol" to "computeTotal"))
        assertTrue(defRes.success)
        assertTrue(defRes.output.contains("app.js"))

        // 6. Diagnostics
        val diagRes = NeedleToolExecutor.execute("code_get_diagnostics", mapOf("path" to "app.js"))
        assertTrue(diagRes.success)
        assertTrue(diagRes.output.contains("syntax is clean"))

        // 7. Format buffer
        val fmtRes = NeedleToolExecutor.execute("code_format_buffer", mapOf("path" to "app.js"))
        assertTrue(fmtRes.success)
    }

    @Test
    fun testCategory3TerminalAndSystemExecution() = runBlocking {
        // 1. System info
        val sysRes = NeedleToolExecutor.execute("terminal_system_info", emptyMap())
        assertTrue(sysRes.success)
        assertTrue(sysRes.output.contains("OS:"))
        assertTrue(sysRes.output.contains("CPU Cores:"))

        // 2. Set and Get environment variables
        val setEnvRes = NeedleToolExecutor.execute("terminal_set_env", mapOf("key" to "MY_TEST_KEY", "value" to "12345"))
        assertTrue(setEnvRes.success)

        val getEnvRes = NeedleToolExecutor.execute("terminal_get_env", emptyMap())
        assertTrue(getEnvRes.success)
        assertTrue(getEnvRes.output.contains("MY_TEST_KEY=12345"))

        // 3. Terminal exec command
        val execRes = NeedleToolExecutor.execute("terminal_exec_command", mapOf("command" to "echo 'Terminal Test'"))
        assertTrue(execRes.success)
        assertTrue(execRes.output.contains("Terminal Test"))

        // 4. Terminal read last output
        val lastOutRes = NeedleToolExecutor.execute("terminal_read_last_output", mapOf("lines" to 5))
        assertTrue(lastOutRes.success)
        assertTrue(lastOutRes.output.contains("Terminal Test"))
    }

    @Test
    fun testCategory4PreviewAndDomExecution() = runBlocking {
        File(projectDir, "index.html").writeText("<!DOCTYPE html><html><body><h1>Krypton Preview</h1></body></html>")

        val urlRes = NeedleToolExecutor.execute("preview_get_url", emptyMap())
        assertTrue(urlRes.success)
        assertTrue(urlRes.output.contains("http://localhost:8080"))

        val domRes = NeedleToolExecutor.execute("preview_capture_dom", emptyMap())
        assertTrue(domRes.success)
        assertTrue(domRes.output.contains("Krypton Preview"))

        val clearRes = NeedleToolExecutor.execute("preview_clear_logs", emptyMap())
        assertTrue(clearRes.success)

        val reloadRes = NeedleToolExecutor.execute("preview_reload", emptyMap())
        assertTrue(reloadRes.success)
    }

    @Test
    fun testCategory5GitExecution() = runBlocking {
        val statusRes = NeedleToolExecutor.execute("git_status", emptyMap())
        assertTrue(statusRes.success)

        val diffRes = NeedleToolExecutor.execute("git_diff", emptyMap())
        assertTrue(diffRes.success)

        val logRes = NeedleToolExecutor.execute("git_log", mapOf("max_commits" to 5))
        assertTrue(logRes.success)

        val branchRes = NeedleToolExecutor.execute("git_branch", mapOf("action" to "list"))
        assertTrue(branchRes.success)
    }

    @Test
    fun testCategory8PackageExecution() = runBlocking {
        File(projectDir, "package.json").writeText("{\n  \"name\": \"test-app\",\n  \"dependencies\": {\n    \"react\": \"^18.2.0\"\n  }\n}")

        val listRes = NeedleToolExecutor.execute("package_list", emptyMap())
        assertTrue(listRes.success)
        assertTrue(listRes.output.contains("react"))

        val auditRes = NeedleToolExecutor.execute("package_audit", emptyMap())
        assertTrue(auditRes.success)
    }

    @Test
    fun testCategory9AifMemoryExecution() = runBlocking {
        val storeRes = NeedleToolExecutor.execute("aif_store_memory", mapOf("category" to "architecture", "content" to "Use Needle-3 for tool routing", "importance" to 0.9f))
        assertTrue(storeRes.success)

        val queryRes = NeedleToolExecutor.execute("aif_query_memory", mapOf("query" to "Needle-3"))
        assertTrue(queryRes.success)

        val getCtxRes = NeedleToolExecutor.execute("aif_get_context", emptyMap())
        assertTrue(getCtxRes.success)
    }

    @Test
    fun testCategory10ModelOrchestrationExecution() = runBlocking {
        val statusRes = NeedleToolExecutor.execute("model_orchestrate_status", emptyMap())
        assertTrue(statusRes.success)
        assertTrue(statusRes.output.contains("Tier 1 Permanent Brain: LFM-250M (~150MB) + Needle-3 (~30MB)"))

        val otaRes = NeedleToolExecutor.execute("ota_check_update", emptyMap())
        assertTrue(otaRes.success)
        assertTrue(otaRes.output.contains("KryptonIDE"))
    }
}
