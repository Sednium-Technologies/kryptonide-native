package com.sednium.kryptonide.ui.preview

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captured console entry from KryptonIDE's Web Preview runtime.
 */
data class ConsoleEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String, // "ERROR", "WARNING", "LOG", "DEBUG", "INFO"
    val message: String,
    val sourceId: String = "",
    val lineNumber: Int = 0
)

/**
 * Singleton bridge connecting KryptonIDE's WebView preview console outputs
 * to Needle-3 and the autonomous agentic execution pipeline.
 */
object PreviewConsoleBridge {
    private const val MAX_ENTRIES = 100
    private val buffer = CopyOnWriteArrayList<ConsoleEntry>()
    private var activeWebViewRef: WeakReference<WebView>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Records a console log message into the thread-safe rolling buffer (max 100 entries).
     */
    fun record(level: String, message: String, sourceId: String = "", lineNumber: Int = 0): ConsoleEntry {
        val entry = ConsoleEntry(
            timestamp = System.currentTimeMillis(),
            level = level.uppercase().trim(),
            message = message,
            sourceId = sourceId,
            lineNumber = lineNumber
        )

        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.removeAt(0)
        }
        return entry
    }

    /**
     * Retrieves captured console entries, optionally filtered by severity level.
     * e.g. "ERROR", "WARNING", "LOG", or null for all.
     */
    fun getEntries(level: String? = null): List<ConsoleEntry> {
        val normalized = level?.trim()?.uppercase()
        if (normalized.isNullOrEmpty() || normalized == "ALL") {
            return buffer.toList()
        }
        return buffer.filter { it.level.equals(normalized, ignoreCase = true) }
    }

    /**
     * Clears all captured console entries from the buffer.
     */
    fun clear() {
        buffer.clear()
    }

    /**
     * Registers the active WebView preview instance.
     */
    fun registerWebView(webView: WebView) {
        activeWebViewRef = WeakReference(webView)
    }

    /**
     * Unregisters the WebView preview if it matches the current active instance.
     */
    fun unregisterWebView(webView: WebView? = null) {
        if (webView == null || activeWebViewRef?.get() == webView) {
            activeWebViewRef = null
        }
    }

    /**
     * Returns true if an active preview WebView is currently mounted.
     */
    fun hasActiveWebView(): Boolean {
        return activeWebViewRef?.get() != null
    }

    /**
     * Triggers a reload of the active WebView preview.
     */
    fun triggerReload(onReloadStarted: (() -> Unit)? = null) {
        val wv = activeWebViewRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            wv.reload()
            onReloadStarted?.invoke()
        } else {
            mainHandler.post {
                activeWebViewRef?.get()?.reload()
                onReloadStarted?.invoke()
            }
        }
    }

    /**
     * Evaluates JavaScript code inside the active preview WebView and returns result via callback.
     */
    fun evaluateJs(script: String, onResult: ((String?) -> Unit)? = null) {
        val wv = activeWebViewRef?.get()
        if (wv == null) {
            onResult?.invoke(null)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            wv.evaluateJavascript(script) { res ->
                onResult?.invoke(res)
            }
        } else {
            mainHandler.post {
                activeWebViewRef?.get()?.evaluateJavascript(script) { res ->
                    onResult?.invoke(res)
                } ?: onResult?.invoke(null)
            }
        }
    }
}
