package com.sednium.kryptonide.ui.monaco

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Headless execution runtime for Monaco / VS Code JavaScript plugins.
 * Uses Android's V8 WebView engine with zero rendering overhead.
 */
@SuppressLint("SetJavaScriptEnabled")
object MonacoRuntime : MonacoBridge.MonacoEventListener {

    private const val TAG = "MonacoRuntime"
    private var webView: WebView? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pendingCompletions = ConcurrentHashMap<String, CompletableDeferred<List<MonacoCompletionItem>>>()
    private val requestIdCounter = AtomicLong(1)

    // Callbacks to Sora Editor Host
    var onThemeDefined: ((name: String, themeJson: String) -> Unit)? = null
    var onThemeChanged: ((name: String) -> Unit)? = null
    var onApplyEdits: ((edits: List<MonacoTextEdit>) -> Unit)? = null
    var onMarkers: ((owner: String, markers: List<MonacoMarker>) -> Unit)? = null
    var onActionRegistered: ((id: String, label: String) -> Unit)? = null

    // Stored themes defined dynamically by plugins
    val registeredThemes = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (isInitialized) return
        mainHandler.post {
            try {
                val wv = WebView(context.applicationContext)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                }
                wv.addJavascriptInterface(MonacoBridge(this), "KryptonNative")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        loadShim(context)
                    }
                }
                wv.loadDataWithBaseURL("https://krypton.monaco/", "<html><body></body></html>", "text/html", "UTF-8", null)
                webView = wv
                isInitialized = true
                Log.d(TAG, "Monaco headless runtime initialized.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Monaco runtime: ${e.message}", e)
            }
        }
    }

    private fun loadShim(context: Context) {
        try {
            val shimCode = context.assets.open("monaco/monaco-shim.js").bufferedReader().use { it.readText() }
            evaluateJs(shimCode) {
                Log.d(TAG, "Monaco shim loaded into V8 environment.")
                // Scan default plugin directory after shim is ready
                scanExternalPlugins(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading monaco-shim.js: ${e.message}", e)
        }
    }

    fun evaluateJs(code: String, callback: ((String?) -> Unit)? = null) {
        mainHandler.post {
            webView?.evaluateJavascript(code) { result ->
                callback?.invoke(result)
            }
        }
    }

    fun syncBuffer(uri: String, languageId: String, content: String) {
        if (!isInitialized) return
        val escapedContent = JSONObject.quote(content)
        val escapedUri = JSONObject.quote(uri)
        val escapedLang = JSONObject.quote(languageId)
        evaluateJs("if (window.monaco && window.monaco._syncBuffer) { window.monaco._syncBuffer($escapedUri, $escapedLang, $escapedContent); }")
    }

    suspend fun requestCompletions(
        languageId: String,
        line: Int,
        column: Int,
        word: String
    ): List<MonacoCompletionItem> = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext emptyList()

        val reqId = "comp_${requestIdCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<List<MonacoCompletionItem>>()
        pendingCompletions[reqId] = deferred

        val escapedWord = JSONObject.quote(word)
        val escapedLang = JSONObject.quote(languageId)

        evaluateJs("if (window.monaco && window.monaco._executeCompletionProviders) { window.monaco._executeCompletionProviders('$reqId', $escapedLang, $line, $column, $escapedWord); } else { window.KryptonNative.postCompletionResult('$reqId', '[]'); }")

        val result = withTimeoutOrNull(350L) {
            deferred.await()
        }
        pendingCompletions.remove(reqId)
        result ?: emptyList()
    }

    fun loadPluginScript(pluginId: String, scriptCode: String) {
        Log.d(TAG, "Loading Monaco plugin script: $pluginId")
        val wrapped = """
            (function() {
                try {
                    $scriptCode
                    console.log('Plugin [$pluginId] loaded successfully.');
                } catch(e) {
                    console.error('Error in plugin [$pluginId]:', e);
                }
            })();
        """.trimIndent()
        evaluateJs(wrapped)
    }

    fun scanExternalPlugins(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val externalDir = File(com.sednium.kryptonide.utils.StorageUtils.getKryptonideRoot(), "plugins/monaco")
                if (!externalDir.exists()) {
                    externalDir.mkdirs()
                    // Create sample plugin so users have a template
                    createSampleMonacoPlugin(externalDir)
                }

                val pluginFiles = externalDir.listFiles { f -> f.isFile && f.name.endsWith(".js") } ?: emptyArray()
                for (file in pluginFiles) {
                    val code = file.readText()
                    loadPluginScript(file.nameWithoutExtension, code)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning external Monaco plugins: ${e.message}", e)
            }
        }
    }

    private fun createSampleMonacoPlugin(dir: File) {
        val sampleFile = File(dir, "html-snippets.js")
        if (!sampleFile.exists()) {
            sampleFile.writeText("""
                // Sample Monaco Extension for KryptonIDE
                monaco.languages.registerCompletionItemProvider('html', {
                    provideCompletionItems: function(model, position) {
                        return {
                            suggestions: [
                                {
                                    label: 'html5-boilerplate',
                                    kind: monaco.languages.CompletionItemKind.Snippet,
                                    documentation: 'Modern HTML5 boilerplate with viewport meta and stylesheet link',
                                    insertText: '<!DOCTYPE html>\n<html lang="en">\n<head>\n  <meta charset="UTF-8">\n  <meta name="viewport" content="width=device-width, initial-scale=1.0">\n  <title>${'$'}{1:Document}</title>\n</head>\n<body>\n  ${'$'}{0}\n</body>\n</html>',
                                    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
                                },
                                {
                                    label: 'bs-container',
                                    kind: monaco.languages.CompletionItemKind.Snippet,
                                    documentation: 'Responsive Bootstrap / Flex container',
                                    insertText: '<div class="container">\n  ${'$'}{0}\n</div>',
                                    insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
                                }
                            ]
                        };
                    }
                });
            """.trimIndent())
        }
    }

    // ── Bridge Event Callbacks ────────────────────────────────────────────────
    override fun onConsoleLog(level: String, message: String) {
        when (level.lowercase()) {
            "error" -> Log.e(TAG, "[Monaco JS] $message")
            "warn" -> Log.w(TAG, "[Monaco JS] $message")
            else -> Log.i(TAG, "[Monaco JS] $message")
        }
    }

    override fun onThemeDefined(name: String, themeJson: String) {
        registeredThemes[name] = themeJson
        onThemeDefined?.invoke(name, themeJson)
    }

    override fun onThemeChanged(name: String) {
        onThemeChanged?.invoke(name)
    }

    override fun onCompletionResult(requestId: String, itemsJson: String) {
        val deferred = pendingCompletions[requestId] ?: return
        try {
            val jsonArray = JSONArray(itemsJson)
            val list = mutableListOf<MonacoCompletionItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    MonacoCompletionItem(
                        label = obj.optString("label", ""),
                        kind = obj.optInt("kind", 1),
                        detail = obj.optString("detail", ""),
                        documentation = obj.optString("documentation", ""),
                        insertText = obj.optString("insertText", ""),
                        isSnippet = obj.optBoolean("isSnippet", false)
                    )
                )
            }
            deferred.complete(list)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse completion results: ${e.message}", e)
            deferred.complete(emptyList())
        }
    }

    override fun onApplyEdits(editsJson: String) {
        try {
            val jsonArray = JSONArray(editsJson)
            val edits = mutableListOf<MonacoTextEdit>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rangeObj = obj.optJSONObject("range")
                val range = if (rangeObj != null) {
                    MonacoRange(
                        rangeObj.optInt("startLineNumber", 1),
                        rangeObj.optInt("startColumn", 1),
                        rangeObj.optInt("endLineNumber", 1),
                        rangeObj.optInt("endColumn", 1)
                    )
                } else {
                    MonacoRange(1, 1, 1, 1)
                }
                edits.add(MonacoTextEdit(range, obj.optString("text", "")))
            }
            onApplyEdits?.invoke(edits)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse applied edits: ${e.message}", e)
        }
    }

    override fun onMarkers(owner: String, markersJson: String) {
        try {
            val jsonArray = JSONArray(markersJson)
            val markers = mutableListOf<MonacoMarker>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                markers.add(
                    MonacoMarker(
                        owner = owner,
                        severity = obj.optInt("severity", 8),
                        message = obj.optString("message", ""),
                        startLineNumber = obj.optInt("startLineNumber", 1),
                        startColumn = obj.optInt("startColumn", 1),
                        endLineNumber = obj.optInt("endLineNumber", 1),
                        endColumn = obj.optInt("endColumn", 1)
                    )
                )
            }
            onMarkers?.invoke(owner, markers)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse markers: ${e.message}", e)
        }
    }

    override fun onActionRegistered(actionId: String, label: String) {
        onActionRegistered?.invoke(actionId, label)
    }
}
