package com.sednium.kryptonide.data.model

import kotlinx.serialization.Serializable

enum class FileType { FILE, FOLDER }

@Serializable
data class FileNode(
    val id: String,
    val projectId: String,
    val name: String,
    val type: FileType,
    val content: String? = null,
    val parentId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val isUnsaved: Boolean = false,
    val language: String = "plaintext",
)

fun languageFromFilename(name: String): String {
    val lower = name.lowercase()
    return when {
        lower == "dockerfile"  -> "dockerfile"
        lower == "makefile"    -> "makefile"
        lower == ".gitignore"  -> "ini"
        lower.endsWith(".gradle") || lower.endsWith(".gradle.kts") -> "groovy"
        else -> when (name.substringAfterLast('.', "").lowercase()) {
            "js", "jsx"            -> "javascript"
            "ts", "tsx"            -> "typescript"
            "html", "htm"          -> "html"
            "css", "scss", "less"  -> "css"
            "json"                 -> "json"
            "md", "markdown"       -> "markdown"
            "py"                   -> "python"
            "rs"                   -> "rust"
            "go"                   -> "go"
            "java", "aidl"         -> "java"
            "kt", "kts"            -> "kotlin"
            "cpp","c","h","hpp","cc","cxx" -> "cpp"
            "xml", "svg"           -> "xml"
            "yaml", "yml"          -> "yaml"
            "sh", "bash", "zsh"    -> "shell"
            "sql"                  -> "sql"
            "php"                  -> "php"
            "rb"                   -> "ruby"
            "swift"                -> "swift"
            "dart"                 -> "dart"
            "toml"                 -> "toml"
            "ini", "cfg", "conf"   -> "ini"
            else                   -> "plaintext"
        }
    }
}
