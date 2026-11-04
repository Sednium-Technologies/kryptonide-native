package com.sednium.kryptonide.ui.lsp

/**
 * Concrete, real language servers — the same ones VSCode uses under the
 * hood for these languages — installed on demand into whichever sandbox
 * [com.sednium.kryptonide.ui.build.ToolchainDetector] finds (AndroidIDE,
 * Termux, or KryptonIDE's own private shell).
 *
 * Kotlin reuses the JDK already set up for Gradle builds — no extra
 * runtime needed. The npm-based ones (TS/JS, Python, JSON) need Node.js,
 * which the install command pulls in via `pkg install nodejs` the first
 * time (idempotent — harmless if already present).
 */
data class LspServerConfig(
    val id: String,
    val displayName: String,
    val languageIds: List<String>,      // language ids this server handles
    val fileExtensions: List<String>,   // without the dot
    val checkInstalledCommand: String,  // exit 0 if already installed
    val installCommand: String,
    val launchCommand: String,          // must speak LSP over stdio
    val initializationOptions: String = "{}",
    /** Installed automatically on first app launch. Off for less-common
     *  languages so first-run doesn't download things most people won't use —
     *  they still install for real the first time a matching file opens, or
     *  via an explicit Install in the Plugins panel. */
    val eagerOnFirstRun: Boolean = true
)

object LspServerRegistry {

    val ALL: List<LspServerConfig> = listOf(
        LspServerConfig(
            id = "kotlin",
            displayName = "Kotlin Language Server",
            languageIds = listOf("kotlin"),
            fileExtensions = listOf("kt", "kts"),
            checkInstalledCommand = "test -x \$HOME/.krypton-lsp/kotlin-language-server/server/bin/kotlin-language-server",
            installCommand = """
                mkdir -p ${'$'}HOME/.krypton-lsp && cd ${'$'}HOME/.krypton-lsp && \
                curl -L -o kls.zip https://github.com/fwcd/kotlin-language-server/releases/latest/download/server.zip && \
                unzip -o kls.zip -d kotlin-language-server && \
                chmod +x kotlin-language-server/server/bin/kotlin-language-server && \
                rm -f kls.zip
            """.trimIndent(),
            launchCommand = "\$HOME/.krypton-lsp/kotlin-language-server/server/bin/kotlin-language-server"
        ),
        LspServerConfig(
            id = "typescript",
            displayName = "TypeScript / JavaScript Language Server",
            languageIds = listOf("typescript", "javascript"),
            fileExtensions = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs"),
            checkInstalledCommand = "command -v typescript-language-server >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g typescript typescript-language-server",
            launchCommand = "typescript-language-server --stdio"
        ),
        LspServerConfig(
            id = "python",
            displayName = "Pyright (Python)",
            languageIds = listOf("python"),
            fileExtensions = listOf("py"),
            checkInstalledCommand = "command -v pyright-langserver >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g pyright",
            launchCommand = "pyright-langserver --stdio"
        ),
        LspServerConfig(
            id = "json",
            displayName = "JSON Language Server",
            languageIds = listOf("json"),
            fileExtensions = listOf("json"),
            checkInstalledCommand = "command -v vscode-json-languageserver >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g vscode-json-languageserver",
            launchCommand = "vscode-json-languageserver --stdio"
        ),
        LspServerConfig(
            id = "rust",
            displayName = "rust-analyzer",
            languageIds = listOf("rust"),
            fileExtensions = listOf("rs"),
            // Termux/AndroidIDE package this pre-built for bionic — GitHub's own
            // releases are glibc-only and won't run here, same issue as Android's
            // SDK build-tools.
            checkInstalledCommand = "command -v rust-analyzer >/dev/null 2>&1",
            installCommand = "pkg install -y rust-analyzer",
            launchCommand = "rust-analyzer",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "go",
            displayName = "gopls",
            languageIds = listOf("go"),
            fileExtensions = listOf("go"),
            checkInstalledCommand = "test -x \$HOME/go/bin/gopls",
            installCommand = "pkg install -y golang && go install golang.org/x/tools/gopls@latest",
            launchCommand = "\$HOME/go/bin/gopls",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "html",
            displayName = "HTML Language Server",
            languageIds = listOf("html"),
            fileExtensions = listOf("html", "htm"),
            checkInstalledCommand = "command -v vscode-html-languageserver >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g vscode-langservers-extracted",
            launchCommand = "vscode-html-languageserver --stdio",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "css",
            displayName = "CSS Language Server",
            languageIds = listOf("css", "scss", "less"),
            fileExtensions = listOf("css", "scss", "less"),
            checkInstalledCommand = "command -v vscode-css-languageserver >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g vscode-langservers-extracted",
            launchCommand = "vscode-css-languageserver --stdio",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "yaml",
            displayName = "YAML Language Server",
            languageIds = listOf("yaml"),
            fileExtensions = listOf("yaml", "yml"),
            checkInstalledCommand = "command -v yaml-language-server >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g yaml-language-server",
            launchCommand = "yaml-language-server --stdio",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "bash",
            displayName = "Bash Language Server",
            languageIds = listOf("bash", "sh"),
            fileExtensions = listOf("sh", "bash"),
            checkInstalledCommand = "command -v bash-language-server >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g bash-language-server",
            launchCommand = "bash-language-server start",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "cpp",
            displayName = "clangd (C/C++)",
            languageIds = listOf("c", "cpp"),
            fileExtensions = listOf("c", "cpp", "cc", "cxx", "h", "hpp"),
            checkInstalledCommand = "command -v clangd >/dev/null 2>&1",
            installCommand = "pkg install -y clang",
            launchCommand = "clangd --background-index",
            eagerOnFirstRun = false
        ),
        LspServerConfig(
            id = "php",
            displayName = "Intelephense (PHP)",
            languageIds = listOf("php"),
            fileExtensions = listOf("php"),
            checkInstalledCommand = "command -v intelephense >/dev/null 2>&1",
            installCommand = "pkg install -y nodejs && npm install -g intelephense",
            launchCommand = "intelephense --stdio",
            eagerOnFirstRun = false
        )
    )

    fun forExtension(ext: String): LspServerConfig? =
        ALL.firstOrNull { it.fileExtensions.contains(ext.lowercase()) }

    fun forLanguageId(languageId: String): LspServerConfig? =
        ALL.firstOrNull { it.languageIds.contains(languageId.lowercase()) }
}
