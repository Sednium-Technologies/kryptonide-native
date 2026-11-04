package com.sednium.kryptonide.ui.format

data class FormatterConfig(
    val id: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val checkInstalledCommand: String,
    val installCommand: String,
    val formatCommand: String  // reads original content from stdin, writes formatted content to stdout
)

object FormatterRegistry {

    val ALL: List<FormatterConfig> = listOf(
        FormatterConfig(
            id = "prettier",
            displayName = "Prettier",
            fileExtensions = listOf("js", "jsx", "ts", "tsx", "json", "css", "scss", "html", "md", "yaml", "yml"),
            checkInstalledCommand = "command -v prettier >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g prettier",
            formatCommand = "prettier --stdin-filepath file.{ext}"
        ),
        FormatterConfig(
            id = "ktlint",
            displayName = "ktlint",
            fileExtensions = listOf("kt", "kts"),
            checkInstalledCommand = "test -f \$HOME/.krypton-fmt/ktlint.jar",
            installCommand = """
                mkdir -p ${'$'}HOME/.krypton-fmt && cd ${'$'}HOME/.krypton-fmt && \
                curl -L -o ktlint.jar https://github.com/pinterest/ktlint/releases/latest/download/ktlint
            """.trimIndent(),
            formatCommand = "java -jar \$HOME/.krypton-fmt/ktlint.jar --format --stdin --log-level=none"
        ),
        FormatterConfig(
            id = "black",
            displayName = "Black",
            fileExtensions = listOf("py"),
            checkInstalledCommand = "command -v black >/dev/null 2>&1",
            installCommand = "pkg install -y python && pip install --quiet black",
            formatCommand = "black --quiet -"
        ),
        FormatterConfig(
            id = "gofmt",
            displayName = "gofmt",
            fileExtensions = listOf("go"),
            checkInstalledCommand = "command -v gofmt >/dev/null 2>&1",
            installCommand = "pkg install -y golang",
            formatCommand = "gofmt"
        ),
        FormatterConfig(
            id = "rustfmt",
            displayName = "rustfmt",
            fileExtensions = listOf("rs"),
            checkInstalledCommand = "command -v rustfmt >/dev/null 2>&1",
            installCommand = "pkg install -y rust",
            formatCommand = "rustfmt --emit stdout"
        ),
        FormatterConfig(
            id = "clang-format",
            displayName = "clang-format",
            fileExtensions = listOf("c", "cpp", "cc", "cxx", "h", "hpp"),
            checkInstalledCommand = "command -v clang-format >/dev/null 2>&1",
            installCommand = "pkg install -y clang",
            formatCommand = "clang-format --assume-filename=file.{ext}"
        )
    )

    fun forExtension(ext: String): FormatterConfig? =
        ALL.firstOrNull { it.fileExtensions.contains(ext.lowercase()) }
}
