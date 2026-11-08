package com.sednium.kryptonide.agent.pocket

import android.content.Context
import android.util.Log
import com.sednium.kryptonide.agent.hardware.HardwareTopology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages the on-device local llama-server HTTP/SSE daemon on port 8765.
 *
 * Spawns native `llama-server` (or `libllama_server.so`) with optimized mobile arguments:
 * `--host 127.0.0.1 --port 8765 --ctx-size 8192 --threads <cores> --cache-reuse 256 --flash-attn --cont-batching -m <modelPath>`
 *
 * Provides persistent KV-caching across multiple conversation turns via SSE streaming.
 */
object LocalGgufServerManager {
    private const val TAG = "LocalGgufServer"
    const val DEFAULT_PORT = 8765
    const val SERVER_HOST = "127.0.0.1"

    private var currentProcess: Process? = null
    private var activeModelPath: String? = null
    var appContext: Context? = null

    private val logBuffer = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private const val MAX_LOG_LINES = 50
    private val serverMutex = kotlinx.coroutines.sync.Mutex()

    fun getLastErrorLog(): String = logBuffer.joinToString("\n")

    fun getBaseUrl(port: Int = DEFAULT_PORT): String = "http://$SERVER_HOST:$port/v1"

    /**
     * Checks if the server binary is present in native library dir or app storage.
     */
    fun findServerBinary(context: Context? = null): File? {
        val ctx = context ?: appContext
        val candidates = mutableListOf<File>()
        if (ctx != null) {
            candidates += File(ctx.applicationInfo.nativeLibraryDir, "libllama_server.so")
            candidates += File(ctx.applicationInfo.nativeLibraryDir, "llama-server")
            candidates += File(ctx.filesDir, "bin/llama-server")
            candidates += File(ctx.filesDir, "llama-server")
        }
        candidates += File("/data/data/com.sednium.kryptonide/lib", "libllama_server.so")
        candidates += File("/data/user/0/com.sednium.kryptonide/lib", "libllama_server.so")

        for (f in candidates) {
            val isValid = runCatching {
                if (f.exists() && f.canExecute() && f.length() > 1024L) return@runCatching true
                if (f.exists() && f.length() > 1024L) {
                    f.setExecutable(true, false)
                    return@runCatching f.canExecute()
                }
                false
            }.getOrDefault(false)

            if (isValid) return f
        }
        return null
    }

    /**
     * Checks if a socket can connect to the target port.
     */
    fun isPortOpen(host: String = SERVER_HOST, port: Int = DEFAULT_PORT, timeoutMs: Int = 300): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the /health endpoint of llama-server returns HTTP 200.
     */
    fun isServerHealthy(port: Int = DEFAULT_PORT): Boolean {
        return try {
            val url = java.net.URL("http://$SERVER_HOST:$port/health")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 300
                readTimeout = 300
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Verifies that llama-server is listening and ready to accept completions.
     */
    fun isServerReady(port: Int = DEFAULT_PORT): Boolean {
        if (!isPortOpen(SERVER_HOST, port, timeoutMs = 150)) return false
        if (isServerHealthy(port)) return true
        return logBuffer.any {
            it.contains("listening on http", ignoreCase = true) ||
            it.contains("server_on_li", ignoreCase = true)
        }
    }

    /**
     * Returns true if llama-server is currently active and accepting connections.
     */
    suspend fun isServerRunning(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        val proc = currentProcess
        if (proc != null && !proc.isAlive) {
            currentProcess = null
            activeModelPath = null
        }
        isServerReady(port)
    }

    /**
     * Preloads model into RAM by starting the local llama-server ahead of time.
     * Automatically discovers the best available model (prioritizing MiniCPM 1B or any installed GGUF model).
     */
    /**
     * Checks if a file has a valid GGUF binary magic header ('GGUF').
     */
    fun isGgufFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            file.inputStream().use { stream ->
                val magic = ByteArray(4)
                val read = stream.read(magic)
                read == 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                        magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (e: Throwable) {
            false
        }
    }

    suspend fun preloadModel(context: Context? = null, port: Int = DEFAULT_PORT): Boolean {
        val targetFile = withContext(Dispatchers.IO) {
            val ctx = context ?: appContext
            val selectedModelId = if (ctx != null) {
                runCatching { com.sednium.kryptonide.data.storage.SettingsStore(ctx).getSelectedPocketModelId() }
                    .getOrDefault(PocketModels.DEFAULT_MODEL_ID)
            } else PocketModels.DEFAULT_MODEL_ID
            val selectedSpec = PocketModels.findCatalogSpec(selectedModelId)

            // Check scan directories for valid GGUF models for llama-server
            val dirs = if (ctx != null) {
                listOf(
                    ModelDownloadManager.getModelsDirectory(ctx),
                    File(ctx.filesDir, "models"),
                    File("/sdcard/Kryptonide/models"),
                    File("/sdcard/KryptonIDE/models")
                )
            } else {
                listOf(
                    File("/sdcard/Kryptonide/models"),
                    File("/sdcard/KryptonIDE/models")
                )
            }
            for (dir in dirs) {
                val files = dir.listFiles { f ->
                    f.isFile && f.name.endsWith(".gguf") && !f.name.endsWith(".downloading") && !f.name.endsWith(".tmp") && f.length() > 0 && !f.name.contains("needle")
                } ?: continue

                // 1. Check user-selected model
                if (selectedSpec != null) {
                    val matchingSelected = files.find { (it.name.contains(selectedSpec.id, ignoreCase = true) || (selectedSpec.downloadUrl != null && it.name.equals(selectedSpec.downloadUrl.substringAfterLast('/'), ignoreCase = true))) && isGgufFile(it) }
                    if (matchingSelected != null) return@withContext matchingSelected
                }

                // 2. Default to MiniCPM 1B base model
                val minicpm = files.find { it.name.lowercase().contains("minicpm") && isGgufFile(it) }
                if (minicpm != null) return@withContext minicpm

                // 3. Fallback to any valid GGUF model on disk
                val firstValid = files.firstOrNull { isGgufFile(it) }
                if (firstValid != null) return@withContext firstValid
            }
            null
        } ?: return false

        return startServer(context, targetFile, port)
    }

    /**
     * Preloads model into RAM by starting the local llama-server ahead of time.
     */
    suspend fun preloadModel(context: Context? = null, modelFile: File, port: Int = DEFAULT_PORT): Boolean {
        if (modelFile.name.endsWith(".cact", ignoreCase = true) || modelFile.name.contains("needle", ignoreCase = true)) {
            Log.i(TAG, "Needle 3 is a Cactus tool-calling engine (.cact) running in-process, skipping llama-server.")
            return true
        }
        return startServer(context, modelFile, port)
    }

    /**
     * Starts the local llama-server on [port] with [modelFile].
     * Returns true if the server is ready to accept requests.
     */
    suspend fun startServer(context: Context? = null, modelFile: File, port: Int = DEFAULT_PORT): Boolean = serverMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.w(TAG, "Cannot start server: Model file does not exist: ${modelFile.absolutePath}")
                return@withContext false
            }

            if (!isGgufFile(modelFile)) {
                Log.w(TAG, "Cannot start llama-server: Model file '${modelFile.name}' is not a valid GGUF binary format.")
                return@withContext false
            }

            val proc = currentProcess
            if (proc != null && proc.isAlive) {
                if (activeModelPath == modelFile.absolutePath) {
                    if (isServerReady(port)) {
                        Log.d(TAG, "llama-server already running and ready on port $port with active model ${modelFile.name}")
                        return@withContext true
                    } else {
                        // Process is alive and loading weights into RAM, wait up to 30s
                        Log.i(TAG, "llama-server is currently loading ${modelFile.name}, waiting for readiness...")
                        val deadline = System.currentTimeMillis() + 30_000L
                        while (System.currentTimeMillis() < deadline) {
                            if (!proc.isAlive) break
                            if (isServerReady(port)) {
                                Log.i(TAG, "llama-server became ready on port $port!")
                                return@withContext true
                            }
                            delay(250)
                        }
                    }
                } else {
                    Log.i(TAG, "Switching model from $activeModelPath to ${modelFile.absolutePath}: stopping existing llama-server...")
                    stopServer()
                }
            }

            val binary = findServerBinary(context)
            if (binary == null) {
                Log.d(TAG, "No native llama-server binary found")
                return@withContext false
            }

            logBuffer.clear()

            val threads = HardwareTopology.getOptimalThreadCount().coerceIn(2, 6)
            val modelNameLower = modelFile.name.lowercase(java.util.Locale.ROOT)
            val args = mutableListOf(
                binary.absolutePath,
                "-m", modelFile.absolutePath,
                "--host", SERVER_HOST,
                "--port", port.toString(),
                "-c", "4096",
                "-t", threads.toString(),
                "--temp", "0.3",
                "--repeat-penalty", "1.12"
            )

            if (modelNameLower.contains("minicpm") || modelNameLower.contains("thinking")) {
                args += listOf("--chat-template", "chatml")
            }

            Log.i(TAG, "Spawning llama-server: ${args.joinToString(" ")}")
            try {
                val workDir = (context ?: appContext)?.filesDir ?: modelFile.parentFile ?: File(".")
                val pb = ProcessBuilder(args)
                    .directory(workDir)
                    .redirectErrorStream(true)

                // Setup isolated ggml backends directory to eliminate "load_backend: failed to load" on unrelated .so files
                binary.parentFile?.absolutePath?.let { libDir ->
                    val existingLd = System.getenv("LD_LIBRARY_PATH")
                    val ldPath = if (existingLd.isNullOrBlank()) libDir else "$libDir:$existingLd"
                    pb.environment()["LD_LIBRARY_PATH"] = ldPath

                    val backendsDir = File(workDir, "ggml_backends").apply { mkdirs() }
                    File(libDir).listFiles()?.forEach { f ->
                        if (f.name.startsWith("libggml-cpu") && f.name.endsWith(".so")) {
                            val link = File(backendsDir, f.name)
                            if (!link.exists()) {
                                runCatching {
                                    android.system.Os.symlink(f.absolutePath, link.absolutePath)
                                }.onFailure {
                                    runCatching { f.copyTo(link, overwrite = true) }
                                }
                            }
                        }
                    }
                    val targetBackendPath = if (backendsDir.list()?.isNotEmpty() == true) backendsDir.absolutePath else libDir
                    pb.environment()["GGML_BACKEND_PATH"] = targetBackendPath
                    pb.environment()["GGML_BACKEND_DIR"] = targetBackendPath
                }

                val newProc = pb.start()
                currentProcess = newProc
                activeModelPath = modelFile.absolutePath

                Thread({
                    try {
                        newProc.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                Log.d(TAG, "[llama-server] $line")
                                logBuffer.offer(line)
                                while (logBuffer.size > MAX_LOG_LINES) {
                                    logBuffer.poll()
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }, "llama-server-logger").apply {
                    isDaemon = true
                    start()
                }

                // Wait up to 30 seconds for server to load weights and bind
                val deadline = System.currentTimeMillis() + 30_000L
                while (System.currentTimeMillis() < deadline) {
                    if (!newProc.isAlive) {
                        val exit = runCatching { newProc.exitValue() }.getOrNull()
                        Log.w(TAG, "llama-server exited prematurely with code $exit. Output logs:\n${getLastErrorLog()}")
                        currentProcess = null
                        activeModelPath = null
                        return@withContext false
                    }
                    if (isServerReady(port)) {
                        Log.i(TAG, "llama-server successfully bound and listening on port $port!")
                        return@withContext true
                    }
                    delay(250)
                }

                Log.w(TAG, "Timed out waiting for llama-server to bind to port $port. Output logs:\n${getLastErrorLog()}")
                stopServer()
                false
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to launch llama-server process: ${e.message}", e)
                stopServer()
                false
            }
        }
    }

    /**
     * Terminates any running server process.
     */
    fun stopServer() {
        val proc = currentProcess
        if (proc != null) {
            Log.i(TAG, "Stopping llama-server process...")
            runCatching { proc.destroyForcibly() }
            currentProcess = null
            activeModelPath = null
        }
    }
}
