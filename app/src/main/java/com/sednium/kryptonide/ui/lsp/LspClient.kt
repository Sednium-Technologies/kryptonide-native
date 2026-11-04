package com.sednium.kryptonide.ui.lsp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real LSP client speaking the actual protocol (Content-Length framed
 * JSON-RPC 2.0 over stdio) to a language server process — the same wire
 * format VSCode/IntelliJ/Zed all use. This class doesn't know or care which
 * language server it's talking to; [LspServerRegistry] + [LspManager] handle
 * picking and launching the right one.
 */
class LspClient(
    private val process: Process,
    private val scope: CoroutineScope
) {
    private val outStream: OutputStream = process.outputStream
    private val nextId = AtomicInteger(1)
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject?>>()
    private val writeLock = Any()

    var onDiagnostics: ((uri: String, diagnostics: JSONArray) -> Unit)? = null
    var onCrash: (() -> Unit)? = null

    private var readerJob: Job? = null

    fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                readLoop(BufferedInputStream(process.inputStream))
            } catch (e: Exception) {
                android.util.Log.w("LspClient", "Reader loop ended: ${e.message}")
            } finally {
                onCrash?.invoke()
            }
        }
    }

    fun stop() {
        readerJob?.cancel()
        pending.values.forEach { it.complete(null) }
        pending.clear()
        runCatching { process.destroy() }
    }

    // ─── Outgoing ────────────────────────────────────────────────────────────

    suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 5000): JSONObject? {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject?>()
        pending[id] = deferred

        val msg = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        writeMessage(msg)

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            .also { pending.remove(id) }
    }

    fun notify(method: String, params: JSONObject) {
        val msg = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        writeMessage(msg)
    }

    private fun writeMessage(msg: JSONObject) {
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        synchronized(writeLock) {
            runCatching {
                outStream.write(header)
                outStream.write(body)
                outStream.flush()
            }
        }
    }

    // ─── Incoming ────────────────────────────────────────────────────────────

    private fun readLoop(input: BufferedInputStream) {
        while (!Thread.currentThread().isInterrupted) {
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val length = headers["Content-Length"]?.toIntOrNull() ?: continue
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return
                read += n
            }
            runCatching { handleMessage(JSONObject(String(body, Charsets.UTF_8))) }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when {
            msg.has("id") && (msg.has("result") || msg.has("error")) -> {
                val id = msg.optInt("id", -1)
                pending.remove(id)?.complete(msg.optJSONObject("result"))
            }
            msg.optString("method") == "textDocument/publishDiagnostics" -> {
                val params = msg.optJSONObject("params") ?: return
                onDiagnostics?.invoke(params.optString("uri"), params.optJSONArray("diagnostics") ?: JSONArray())
            }
            // window/logMessage, window/showMessage, etc. intentionally ignored for now.
        }
    }
}
