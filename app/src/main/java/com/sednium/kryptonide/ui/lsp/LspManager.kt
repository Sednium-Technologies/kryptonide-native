package com.sednium.kryptonide.ui.lsp

import android.content.Context
import com.sednium.kryptonide.ui.build.ToolchainDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LspServerStatus { NOT_INSTALLED, INSTALLING, STARTING, READY, FAILED, UNAVAILABLE }

/**
 * One of these lives per open project. Lazily installs + launches the right
 * language server the first time a matching file is opened, keeps it alive
 * across files of the same language, and converts LSP <-> editor marker formats
 * (they're both "JSON with line/column info" but disagree on 0- vs 1-based
 * lines, severity numbering, and completion-kind numbering).
 */
class LspManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val projectRoot: String
) {
    private val clients = mutableMapOf<String, LspClient>()
    private val statusByServer = mutableMapOf<String, LspServerStatus>()
    private val startLocks = mutableMapOf<String, Mutex>()

    var onDiagnostics: ((uri: String, markersJson: String) -> Unit)? = null
    var onStatusChanged: ((serverId: String, status: LspServerStatus, message: String) -> Unit)? = null
    var isLspEnabled: (String) -> Boolean = { true }

    fun statusFor(languageId: String): LspServerStatus {
        val config = LspServerRegistry.forLanguageId(languageId) ?: return LspServerStatus.UNAVAILABLE
        return statusByServer[config.id] ?: LspServerStatus.NOT_INSTALLED
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    private suspend fun ensureServer(languageId: String): LspClient? {
        val config = LspServerRegistry.forLanguageId(languageId) ?: return null
        if (!isLspEnabled(config.id)) return null
        clients[config.id]?.let { return it }

        val lock = startLocks.getOrPut(config.id) { Mutex() }
        return lock.withLock {
            clients[config.id]?.let { return@withLock it } // someone else won the race

            val tc = ToolchainDetector.detect(context)
            if (!tc.isUsable) {
                setStatus(config.id, LspServerStatus.UNAVAILABLE, "No JDK/toolchain — set up the toolchain first")
                return@withLock null
            }

            setStatus(config.id, LspServerStatus.INSTALLING, "Checking ${config.displayName}…")
            val installed = LspInstaller.install(context, config) { msg -> android.util.Log.d("LspManager", msg) }
            if (!installed) {
                setStatus(config.id, LspServerStatus.FAILED, "Install failed")
                return@withLock null
            }

            setStatus(config.id, LspServerStatus.STARTING, "Starting ${config.displayName}…")
            val proc = ToolchainDetector.spawnBash(tc, listOf("-c", config.launchCommand), File(projectRoot))
            if (proc == null) {
                setStatus(config.id, LspServerStatus.FAILED, "Could not launch server process")
                return@withLock null
            }

            val client = LspClient(proc, scope)
            client.onDiagnostics = { uri, diagnostics -> publishDiagnostics(uri, diagnostics) }
            client.onCrash = {
                clients.remove(config.id)
                setStatus(config.id, LspServerStatus.FAILED, "Server process exited")
            }
            client.start()

            val initResult = client.request("initialize", buildInitializeParams())
            client.notify("initialized", JSONObject())

            if (initResult == null) {
                setStatus(config.id, LspServerStatus.FAILED, "Server didn't respond to initialize")
                client.stop()
                return@withLock null
            }

            clients[config.id] = client
            setStatus(config.id, LspServerStatus.READY, "${config.displayName} ready")
            client
        }
    }

    private fun buildInitializeParams(): JSONObject = JSONObject().apply {
        put("processId", JSONObject.NULL)
        put("rootUri", "file://$projectRoot")
        put("capabilities", JSONObject().apply {
            put("textDocument", JSONObject().apply {
                put("completion", JSONObject().apply {
                    put("completionItem", JSONObject().apply { put("snippetSupport", false) })
                })
                put("hover", JSONObject())
                put("definition", JSONObject())
                put("publishDiagnostics", JSONObject())
            })
        })
    }

    private fun setStatus(serverId: String, status: LspServerStatus, message: String) {
        statusByServer[serverId] = status
        scope.launch(Dispatchers.Main) {
            onStatusChanged?.invoke(serverId, status, message)
        }
    }

    // ─── Document sync ───────────────────────────────────────────────────────

    fun didOpen(languageId: String, uri: String, text: String) {
        scope.launch(Dispatchers.IO) {
            val client = ensureServer(languageId) ?: return@launch
            client.notify("textDocument/didOpen", JSONObject().apply {
                put("textDocument", JSONObject().apply {
                    put("uri", uri); put("languageId", languageId); put("version", 1); put("text", text)
                })
            })
        }
    }

    fun didChange(languageId: String, uri: String, text: String, version: Int) {
        val config = LspServerRegistry.forLanguageId(languageId) ?: return
        val client = clients[config.id] ?: return
        client.notify("textDocument/didChange", JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri); put("version", version) })
            put("contentChanges", JSONArray().put(JSONObject().apply { put("text", text) }))
        })
    }

    // ─── Requests (1-based line/col -> LSP 0-based) ─────────────────────

    suspend fun completion(languageId: String, uri: String, line1: Int, char1: Int): JSONArray? {
        val config = LspServerRegistry.forLanguageId(languageId) ?: return null
        val client = clients[config.id] ?: return null
        val result = client.request("textDocument/completion", positionParams(uri, line1, char1)) ?: return null
        val items = result.optJSONArray("items") ?: JSONArray()
        return JSONArray().apply {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                put(JSONObject().apply {
                    put("label", item.optString("label"))
                    put("insertText", item.optString("insertText", item.optString("label")))
                    put("detail", item.optString("detail", ""))
                    put("documentation", extractDoc(item.opt("documentation")))
                    put("completionKind", lspCompletionKindName(item.optInt("kind", 1)))
                })
            }
        }
    }

    suspend fun hover(languageId: String, uri: String, line1: Int, char1: Int): JSONObject? {
        val client = ensureServer(languageId) ?: return null
        val result = client.request("textDocument/hover", positionParams(uri, line1, char1)) ?: return null
        val contents = extractDoc(result.opt("contents"))
        if (contents.isBlank()) return null
        return JSONObject().apply { put("contents", contents) }
    }

    suspend fun definition(languageId: String, uri: String, line1: Int, char1: Int): JSONObject? {
        val client = ensureServer(languageId) ?: return null
        val result = client.request("textDocument/definition", positionParams(uri, line1, char1)) ?: return null
        val loc = if (result.has("uri")) result else null // some servers return an array; keep simple for the first slice
        loc ?: return null
        val range = loc.optJSONObject("range") ?: return null
        return JSONObject().apply {
            put("uri", loc.optString("uri"))
            put("startLine", (range.optJSONObject("start")?.optInt("line", 0) ?: 0) + 1)
            put("startCol", (range.optJSONObject("start")?.optInt("character", 0) ?: 0) + 1)
            put("endLine", (range.optJSONObject("end")?.optInt("line", 0) ?: 0) + 1)
            put("endCol", (range.optJSONObject("end")?.optInt("character", 0) ?: 0) + 1)
        }
    }

    private fun positionParams(uri: String, line1: Int, char1: Int) = JSONObject().apply {
        put("textDocument", JSONObject().apply { put("uri", uri) })
        put("position", JSONObject().apply { put("line", (line1 - 1).coerceAtLeast(0)); put("character", (char1 - 1).coerceAtLeast(0)) })
    }

    private fun extractDoc(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONObject -> raw.optString("value", "")
        else -> ""
    }

    // ─── Diagnostics: LSP (0-based, numeric severity) -> marker format ────────

    private fun publishDiagnostics(uri: String, diagnostics: JSONArray) {
        val markers = JSONArray()
        for (i in 0 until diagnostics.length()) {
            val d = diagnostics.optJSONObject(i) ?: continue
            val range = d.optJSONObject("range") ?: continue
            val start = range.optJSONObject("start") ?: continue
            val end = range.optJSONObject("end") ?: continue
            markers.put(JSONObject().apply {
                put("severity", lspSeverityToMarkerSeverity(d.optInt("severity", 1)))
                put("message", d.optString("message"))
                put("startLineNumber", start.optInt("line", 0) + 1)
                put("startColumn", start.optInt("character", 0) + 1)
                put("endLineNumber", end.optInt("line", 0) + 1)
                put("endColumn", end.optInt("character", 0) + 1)
                put("source", d.optString("source", "lsp"))
            })
        }
        val markersJson = markers.toString()
        scope.launch(Dispatchers.Main) {
            onDiagnostics?.invoke(uri, markersJson)
        }
    }

    /** LSP DiagnosticSeverity (1=Error..4=Hint) -> editor marker severity numeric values. */
    private fun lspSeverityToMarkerSeverity(lspSeverity: Int): Int = when (lspSeverity) {
        1 -> 8  // Error
        2 -> 4  // Warning
        3 -> 2  // Info
        else -> 1 // Hint
    }

    /** LSP CompletionItemKind (1..25) -> a readable completion-kind name. */
    private fun lspCompletionKindName(lspKind: Int): String = when (lspKind) {
        2 -> "Method"; 3 -> "Function"; 4 -> "Constructor"; 5 -> "Field"; 6 -> "Variable"
        7 -> "Class"; 8 -> "Interface"; 9 -> "Module"; 10 -> "Property"; 11 -> "Unit"
        12 -> "Value"; 13 -> "Enum"; 14 -> "Keyword"; 15 -> "Snippet"; 16 -> "Color"
        17 -> "File"; 18 -> "Reference"; 19 -> "Folder"; 20 -> "EnumMember"; 21 -> "Constant"
        22 -> "Struct"; 23 -> "Event"; 24 -> "Operator"; 25 -> "TypeParameter"
        else -> "Text"
    }

    fun shutdown() {
        clients.values.forEach { it.stop() }
        clients.clear()
    }
}
