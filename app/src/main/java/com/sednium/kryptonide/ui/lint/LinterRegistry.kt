package com.sednium.kryptonide.ui.lint

data class LinterConfig(
    val id: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val checkInstalledCommand: String,
    val installCommand: String,
    val lintCommand: String
)

object LinterRegistry {

    val ALL: List<LinterConfig> = listOf(
        LinterConfig(
            id = "eslint",
            displayName = "ESLint",
            fileExtensions = listOf("js", "jsx", "ts", "tsx", "mjs", "cjs"),
            checkInstalledCommand = "command -v eslint >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g eslint",
            lintCommand = "eslint -f json \"{file}\""
        ),
        LinterConfig(
            id = "ruff",
            displayName = "Ruff (Python)",
            fileExtensions = listOf("py"),
            checkInstalledCommand = "command -v ruff >/dev/null 2>&1",
            installCommand = "pkg install -y python && pip install --quiet ruff",
            lintCommand = "ruff check --output-format json \"{file}\""
        ),
        LinterConfig(
            id = "shellcheck",
            displayName = "ShellCheck",
            fileExtensions = listOf("sh", "bash"),
            checkInstalledCommand = "command -v shellcheck >/dev/null 2>&1",
            installCommand = "pkg install -y shellcheck",
            lintCommand = "shellcheck -f json \"{file}\""
        ),
        LinterConfig(
            id = "detekt",
            displayName = "detekt (Kotlin)",
            fileExtensions = listOf("kt", "kts"),
            checkInstalledCommand = "test -x \$HOME/.krypton-lint/detekt-cli/bin/detekt-cli",
            installCommand = """
                mkdir -p ${'$'}HOME/.krypton-lint && cd ${'$'}HOME/.krypton-lint && \
                curl -L -o detekt.zip https://github.com/detekt/detekt/releases/latest/download/detekt-cli-1.23.7.zip && \
                unzip -o detekt.zip -d detekt-cli && \
                chmod +x detekt-cli/bin/detekt-cli && \
                rm -f detekt.zip
            """.trimIndent(),
            lintCommand = "\$HOME/.krypton-lint/detekt-cli/bin/detekt-cli --input \"{file}\" --report sarif:stdout"
        ),
        LinterConfig(
            id = "clang-tidy",
            displayName = "Clang-Tidy (C/C++)",
            fileExtensions = listOf("c", "cpp", "cc", "cxx", "h", "hpp"),
            checkInstalledCommand = "command -v clang-tidy >/dev/null 2>&1",
            installCommand = "pkg install -y clang",
            lintCommand = "clang-tidy \"{file}\" --"
        )
    )

    fun forExtension(ext: String): LinterConfig? =
        ALL.firstOrNull { it.fileExtensions.contains(ext.lowercase()) }
}
