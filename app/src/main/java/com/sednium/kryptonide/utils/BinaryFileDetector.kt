package com.sednium.kryptonide.utils

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object BinaryFileDetector {

    const val MAX_TEXT_SIZE_BYTES: Long = 2 * 1024 * 1024L // 2 MB
    private const val SAMPLE_SIZE: Int = 8192

    private val KNOWN_TEXT_EXTENSIONS = setOf(
        "kt", "kts", "java", "c", "cpp", "h", "hpp", "cs", "py", "js", "jsx",
        "ts", "tsx", "html", "htm", "css", "scss", "sass", "less", "json",
        "xml", "yaml", "yml", "md", "markdown", "txt", "sh", "bash", "zsh",
        "gradle", "properties", "env", "ini", "conf", "sql", "rs", "go",
        "php", "rb", "dart", "swift", "toml", "proto", "svg", "vue", "svelte",
        "gitignore", "gitattributes", "editorconfig", "npmignore", "dockerignore",
        "babelrc", "eslintrc", "prettierrc"
    )

    private val KNOWN_BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff", "psd",
        "mp3", "mp4", "wav", "ogg", "m4a", "flac", "mkv", "avi", "mov",
        "zip", "tar", "gz", "7z", "rar", "jar", "aar", "apk", "aab", "so",
        "a", "dylib", "dll", "exe", "class", "dex", "pdf", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "ttf", "otf", "woff", "woff2", "eot",
        "db", "sqlite", "sqlite3", "bin", "dat", "cact", "gguf"
    )

    fun isBinaryFile(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        if (file.length() == 0L) return false

        val ext = file.extension.lowercase()
        if (ext in KNOWN_BINARY_EXTENSIONS) return true
        if (ext in KNOWN_TEXT_EXTENSIONS) {
            // Check for null bytes in sample just in case file was renamed with text extension
            return containsBinaryBytes(file)
        }

        // Unknown extension or extensionless file (e.g. LICENSE, Makefile, Dockerfile, or binary blob)
        return containsBinaryBytes(file)
    }

    fun isTextFile(file: File): Boolean = !isBinaryFile(file)

    fun isExceedingTextLimit(file: File): Boolean = file.length() > MAX_TEXT_SIZE_BYTES

    private fun containsBinaryBytes(file: File): Boolean {
        return try {
            val buffer = ByteArray(SAMPLE_SIZE)
            val bytesRead = FileInputStream(file).use { it.read(buffer) }
            if (bytesRead <= 0) return false

            var controlChars = 0
            for (i in 0 until bytesRead) {
                val b = buffer[i]
                if (b == 0.toByte()) {
                    return true // Null byte is definitive binary marker
                }
                val ub = b.toInt() and 0xFF
                // ASCII control characters other than BS, TAB, LF, CR, FF
                if (ub < 32 && ub != 9 && ub != 10 && ub != 13 && ub != 8 && ub != 12) {
                    controlChars++
                }
            }

            // If more than 2% control characters in sample, treat as binary
            if (controlChars.toDouble() / bytesRead.toDouble() > 0.02) {
                return true
            }

            // Verify valid UTF-8 encoding
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

            val byteBuf = ByteBuffer.wrap(buffer, 0, bytesRead)
            try {
                decoder.decode(byteBuf)
                false
            } catch (_: Exception) {
                true // Malformed UTF-8 sequence
            }
        } catch (_: Exception) {
            true
        }
    }
}
