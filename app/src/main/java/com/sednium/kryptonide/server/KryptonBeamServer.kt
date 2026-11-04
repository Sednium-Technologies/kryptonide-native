package com.sednium.kryptonide.server

import kotlinx.coroutines.*
import java.io.*
import java.net.*

object KryptonBeamServer {

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = 8080
        private set

    private var currentHtmlProvider: (() -> String?)? = null
    private var currentProjectDirProvider: (() -> File?)? = null

    fun getLocalWifiIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getBeamUrl(): String {
        val ip = getLocalWifiIp()
        return "http://$ip:$boundPort"
    }

    fun start(
        preferredPort: Int = 8080,
        htmlProvider: () -> String?,
        projectDirProvider: () -> File?
    ): Boolean {
        if (isRunning) return true
        currentHtmlProvider = htmlProvider
        currentProjectDirProvider = projectDirProvider

        var port = preferredPort
        var bound = false
        for (attempt in 0..5) {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                boundPort = port
                bound = true
                break
            } catch (_: Exception) {
                port++
            }
        }

        if (!bound) return false
        isRunning = true

        serverJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }
        return true
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendResponse(output, 400, "Bad Request", "text/plain", "Bad Request".toByteArray())
                return
            }

            var path = parts[1].substringBefore('?').substringBefore('#')
            if (path == "/" || path.isBlank()) path = "/index.html"
            val cleanPath = path.removePrefix("/")

            val htmlContent = currentHtmlProvider?.invoke()
            if (cleanPath == "index.html" || cleanPath.isEmpty()) {
                val body = (htmlContent ?: "<html><body><h1>Krypton Beam Active</h1><p>No project preview loaded.</p></body></html>")
                    .toByteArray(Charsets.UTF_8)
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", body)
                return
            }

            // Check if file exists in project directory
            val projectDir = currentProjectDirProvider?.invoke()
            val targetFile = if (projectDir != null) File(projectDir, cleanPath) else null
            if (targetFile != null && targetFile.exists() && targetFile.isFile) {
                val mime = getMimeType(targetFile.name)
                val bytes = targetFile.readBytes()
                sendResponse(output, 200, "OK", mime, bytes)
                return
            }

            // Fallback: serve index.html for SPA routes or 404
            if (htmlContent != null) {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", htmlContent.toByteArray(Charsets.UTF_8))
            } else {
                sendResponse(output, 404, "Not Found", "text/plain", "File Not Found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray
    ) {
        val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${body.size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.flush()
        out.write(body)
        out.flush()
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
