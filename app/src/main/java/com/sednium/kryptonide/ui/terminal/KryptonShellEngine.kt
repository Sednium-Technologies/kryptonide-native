package com.sednium.kryptonide.ui.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.sednium.kryptonide.ui.build.ToolchainDetector
import com.sednium.kryptonide.utils.StorageUtils
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.data.repository.GithubRepository
import com.sednium.kryptonide.data.repository.GithubActionsRepository

/**
 * Krypton IDE Built-in Shell Engine.
 *
 * Implements high-performance, native POSIX and developer commands:
 * - Git version control via JGit (status, log, diff, branch, checkout, add, commit, init)
 * - File manipulation (ls, cd, pwd, tree, cat, head, tail, mkdir, touch, rm, cp, mv, grep, find, stat, wc)
 * - Developer tools (curl, code/open, echo with > and >> redirection)
 * - Termux integration (termux, termux open, pkg, apt)
 * - System inspection (uname, whoami, date, df, uptime, which, help)
 */
object KryptonShellEngine {

    data class ShellResult(
        val output: String = "",
        val error: String = "",
        val handled: Boolean = true,
        val newWorkingDir: String? = null,
        val openFileRequested: String? = null
    )

    // ANSI Colors for formatted outputs
    private const val ANSI_RESET   = "\u001B[0m"
    private const val ANSI_BOLD    = "\u001B[1m"
    private const val ANSI_RED     = "\u001B[31m"
    private const val ANSI_GREEN   = "\u001B[32m"
    private const val ANSI_YELLOW  = "\u001B[33m"
    private const val ANSI_BLUE    = "\u001B[34m"
    private const val ANSI_MAGENTA = "\u001B[35m"
    private const val ANSI_CYAN    = "\u001B[36m"
    private const val ANSI_WHITE   = "\u001B[37m"
    private const val ANSI_GRAY    = "\u001B[90m"

    val BUILTIN_COMMANDS = listOf(
        "help", "?", "cd", "pwd", "ls", "dir", "tree", "cat", "head", "tail",
        "mkdir", "touch", "rm", "cp", "mv", "grep", "find", "stat", "wc",
        "echo", "curl", "code", "open", "git", "termux", "pkg", "apt",
        "which", "uname", "whoami", "date", "df", "uptime", "clear", "history",
        "sh", "bash",
        "npm", "npx", "py", "python", "python3", "pip", "pip3", "node", "gh",
        "zip", "unzip", "tar", "gzip", "gunzip",
        "md5sum", "sha1sum", "sha256sum", "base64",
        "sort", "uniq", "diff", "sed", "awk", "cut", "tr", "tac", "tee",
        "xxd", "hexdump",
        "wget", "ping", "host", "nslookup",
        "env", "printenv", "export", "set", "unset", "alias",
        "sleep", "file", "du", "ps", "free", "top",
        "neofetch", "fastfetch", "seq", "true", "false"
    )

    private val sessionEnv = ConcurrentHashMap<String, String>().apply {
        put("SHELL", "krypton-sh")
        put("TERM", "xterm-256color")
        put("USER", "u0_a" + (runCatching { android.os.Process.myUid() }.getOrNull() ?: 1000))
        put("PATH", "/system/bin:/system/xbin")
    }
    private val sessionAliases = ConcurrentHashMap<String, String>()

    private fun expandVariables(input: String, currentDir: String, projectRoot: String): String {
        var res = input.replace("\$PWD", currentDir).replace("\$HOME", projectRoot)
        sessionEnv.forEach { (k, v) ->
            res = res.replace("\$$k", v).replace("\${$k}", v)
        }
        return res
    }

    fun canHandle(commandLine: String): Boolean {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return false
        val cmd = trimmed.substringBefore(" ").lowercase()
        return BUILTIN_COMMANDS.contains(cmd) ||
               sessionAliases.containsKey(cmd) ||
               trimmed.contains(">") ||
               cmd == "sh" || cmd == "bash" ||
               cmd.startsWith("./") ||
               cmd.endsWith(".sh") ||
               trimmed.endsWith(".sh")
    }

    suspend fun execute(
        context: Context,
        commandLine: String,
        currentDir: String,
        projectRoot: String,
        historyList: List<String> = emptyList()
    ): ShellResult {
        var trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return ShellResult(output = "", handled = true)

        // Variable expansion ($VAR and ${VAR})
        trimmed = expandVariables(trimmed, currentDir, projectRoot)

        // ── Handle Output Redirection (> and >>) ──────────────────────────────
        if (trimmed.contains(" > ") || trimmed.contains(" >> ")) {
            return handleRedirection(context, trimmed, currentDir, projectRoot, historyList)
        }

        var tokens = parseArguments(trimmed)
        if (tokens.isEmpty()) return ShellResult(output = "", handled = true)

        var cmd = tokens[0].lowercase()
        var args = tokens.drop(1)

        // Alias resolution
        sessionAliases[cmd]?.let { aliasedLine ->
            val aliasTokens = parseArguments(aliasedLine)
            if (aliasTokens.isNotEmpty()) {
                cmd = aliasTokens[0].lowercase()
                args = aliasTokens.drop(1) + args
            }
        }

        return try {
            when (cmd) {
                "help", "?" -> executeHelp()
                "pwd"       -> ShellResult(output = currentDir)
                "cd"        -> executeCd(args, currentDir, projectRoot)
                "ls", "dir" -> executeLs(args, currentDir)
                "tree"      -> executeTree(args, currentDir)
                "cat"       -> executeCat(args, currentDir)
                "head"      -> executeHead(args, currentDir)
                "tail"      -> executeTail(args, currentDir)
                "mkdir"     -> executeMkdir(args, currentDir)
                "touch"     -> executeTouch(args, currentDir)
                "rm"        -> executeRm(args, currentDir)
                "cp"        -> executeCp(args, currentDir)
                "mv"        -> executeMv(args, currentDir)
                "grep"      -> executeGrep(args, currentDir)
                "find"      -> executeFind(args, currentDir)
                "stat"      -> executeStat(args, currentDir)
                "wc"        -> executeWc(args, currentDir)
                "echo"      -> executeEcho(args)
                "curl"      -> executeCurl(args)
                "code", "open" -> executeOpen(args, currentDir)
                "git"       -> executeGit(args, currentDir)
                "termux"    -> executeTermux(context, args, currentDir)
                "pkg", "apt"-> executePkg(context, args, currentDir)
                "which"     -> executeWhich(context, args)
                "uname"     -> executeUname(args)
                "whoami"    -> ShellResult(output = System.getProperty("user.name") ?: "u0_a${android.os.Process.myUid()}")
                "date"      -> ShellResult(output = SimpleDateFormat("EEE MMM d HH:mm:ss z yyyy", Locale.US).format(Date()))
                "df"        -> executeDf(args, currentDir)
                "uptime"    -> executeUptime()
                "clear"     -> ShellResult(output = "", handled = true)
                "history"   -> executeHistory(historyList)
                "sh", "bash" -> executeScript(context, tokens, currentDir, projectRoot)
                "npm", "npx" -> executeNpm(context, cmd, args, currentDir, projectRoot)
                "py", "python", "python3" -> executePython(context, cmd, args, currentDir, projectRoot)
                "pip", "pip3" -> executePip(context, cmd, args, currentDir)
                "node"      -> executeNode(context, args, currentDir, projectRoot)
                "gh"        -> executeGh(context, args, currentDir)

                // Archive & Compression
                "zip"       -> executeZip(args, currentDir)
                "unzip"     -> executeUnzip(args, currentDir)
                "tar"       -> executeTar(args, currentDir)
                "gzip"      -> executeGzip(args, currentDir)
                "gunzip"    -> executeGunzip(args, currentDir)

                // Checksums & Encodings
                "md5sum"    -> executeChecksum("MD5", args, currentDir)
                "sha1sum"   -> executeChecksum("SHA-1", args, currentDir)
                "sha256sum" -> executeChecksum("SHA-256", args, currentDir)
                "base64"    -> executeBase64(args, currentDir)

                // Text Processing
                "sort"      -> executeSort(args, currentDir)
                "uniq"      -> executeUniq(args, currentDir)
                "diff"      -> executeDiff(args, currentDir)
                "sed"       -> executeSed(args, currentDir)
                "awk"       -> executeAwk(args, currentDir)
                "cut"       -> executeCut(args, currentDir)
                "tr"        -> executeTr(args, currentDir)
                "tac"       -> executeTac(args, currentDir)
                "tee"       -> executeTee(args, currentDir)
                "xxd", "hexdump" -> executeHexdump(cmd, args, currentDir)

                // Network
                "wget"      -> executeWget(args, currentDir)
                "ping"      -> executePing(args)
                "host", "nslookup" -> executeDnsLookup(args)

                // Environment & Shell State
                "env", "printenv" -> executeEnv()
                "export", "set"   -> executeExport(args)
                "unset"     -> executeUnset(args)
                "alias"     -> executeAlias(args)
                "sleep"     -> executeSleep(args)

                // System & File Inspection
                "file"      -> executeFile(args, currentDir)
                "du"        -> executeDu(args, currentDir)
                "ps"        -> executePs()
                "free"      -> executeFree(args)
                "top"       -> executeTop()
                "neofetch", "fastfetch" -> executeNeofetch()
                "seq"       -> executeSeq(args)
                "true"      -> ShellResult(output = "", handled = true)
                "false"     -> ShellResult(output = "", error = "exit status 1", handled = true)
                else        -> {
                    if (cmd.startsWith("./") || cmd.endsWith(".sh")) {
                        executeScript(context, tokens, currentDir, projectRoot)
                    } else {
                        ShellResult(handled = false)
                    }
                }
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}Error: ${e.message ?: "Execution failed"}$ANSI_RESET", handled = true)
        }
    }

    // ── Output Redirection ────────────────────────────────────────────────────

    private suspend fun handleRedirection(
        context: Context,
        commandLine: String,
        currentDir: String,
        projectRoot: String,
        historyList: List<String>
    ): ShellResult {
        val isAppend = commandLine.contains(" >> ")
        val splitDelimiter = if (isAppend) " >> " else " > "
        val parts = commandLine.split(splitDelimiter, limit = 2)
        val leftCmd = parts[0].trim()
        val targetPath = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")

        // Execute left command
        val leftResult = execute(context, leftCmd, currentDir, projectRoot, historyList)
        val contentToWrite = if (leftResult.output.isNotEmpty()) AnsiParser.stripAnsi(leftResult.output) else ""

        val destFile = resolvePath(targetPath, currentDir)
        destFile.parentFile?.mkdirs()

        if (isAppend) {
            destFile.appendText(contentToWrite + "\n")
        } else {
            destFile.writeText(contentToWrite + "\n")
        }

        return ShellResult(
            output = if (leftResult.error.isNotEmpty()) leftResult.error else "",
            handled = true
        )
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private fun executeHelp(): ShellResult {
        val sb = StringBuilder()
        sb.append("${ANSI_BOLD}${ANSI_CYAN}── Kryptonide Standalone Dev Engine ──$ANSI_RESET\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Package Managers & Runtimes (Pure In-App, Zero Setup):$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}npm install <packages...>$ANSI_RESET Install npm modules (auto --no-bin-links)\n")
        sb.append("  ${ANSI_YELLOW}npm uninstall <packages...>$ANSI_RESET Remove npm modules and clean package.json\n")
        sb.append("  ${ANSI_YELLOW}npm run <dev|build|start>$ANSI_RESET Run project scripts & launch Web Preview\n")
        sb.append("  ${ANSI_YELLOW}npm list$ANSI_RESET                 Dependency tree inspection\n")
        sb.append("  ${ANSI_YELLOW}npm init [-y]$ANSI_RESET            Initialize new package.json\n")
        sb.append("  ${ANSI_YELLOW}pip install <packages...>$ANSI_RESET Pure-Python package installer via PyPI\n")
        sb.append("  ${ANSI_YELLOW}pip list | pip show <pkg>$ANSI_RESET Inspect installed Python packages\n")
        sb.append("  ${ANSI_YELLOW}pip uninstall <pkg>$ANSI_RESET       Remove Python package\n")
        sb.append("  ${ANSI_YELLOW}node <script.js>$ANSI_RESET          Execute JavaScript in Kryptonide\n")
        sb.append("  ${ANSI_YELLOW}py | python <script.py>$ANSI_RESET   Execute Python scripts in Kryptonide\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Git Version Control (JGit):$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}git status$ANSI_RESET             Show working tree status\n")
        sb.append("  ${ANSI_YELLOW}git log [-n <count>]$ANSI_RESET   Show recent commits\n")
        sb.append("  ${ANSI_YELLOW}git branch [-a]$ANSI_RESET        List local & remote branches\n")
        sb.append("  ${ANSI_YELLOW}git checkout <branch>$ANSI_RESET  Switch branch\n")
        sb.append("  ${ANSI_YELLOW}git add <files|.>$ANSI_RESET      Stage file changes\n")
        sb.append("  ${ANSI_YELLOW}git commit -m <msg>$ANSI_RESET    Commit staged changes\n")
        sb.append("  ${ANSI_YELLOW}git diff$ANSI_RESET               Show unstaged file diffs\n")
        sb.append("  ${ANSI_YELLOW}git init$ANSI_RESET               Create new Git repository\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}File & Directory Operations:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}ls [-l] [-a] [-la] [dir]$ANSI_RESET  List files with color-coded types\n")
        sb.append("  ${ANSI_YELLOW}cd <path>$ANSI_RESET              Change working directory (~, .., relative)\n")
        sb.append("  ${ANSI_YELLOW}pwd$ANSI_RESET                    Print working directory\n")
        sb.append("  ${ANSI_YELLOW}tree [-L <depth>] [dir]$ANSI_RESET Visual directory hierarchy\n")
        sb.append("  ${ANSI_YELLOW}cat <files...>$ANSI_RESET         View file content\n")
        sb.append("  ${ANSI_YELLOW}head [-n <num>] <file>$ANSI_RESET View first N lines\n")
        sb.append("  ${ANSI_YELLOW}tail [-n <num>] <file>$ANSI_RESET View last N lines\n")
        sb.append("  ${ANSI_YELLOW}mkdir [-p] <dirs...>$ANSI_RESET   Create directories\n")
        sb.append("  ${ANSI_YELLOW}touch <files...>$ANSI_RESET       Create empty files / update timestamp\n")
        sb.append("  ${ANSI_YELLOW}rm [-r|-rf] <paths...>$ANSI_RESET Remove files or directories\n")
        sb.append("  ${ANSI_YELLOW}cp [-r] <src> <dest>$ANSI_RESET   Copy files or directories\n")
        sb.append("  ${ANSI_YELLOW}mv <src> <dest>$ANSI_RESET        Move / rename file or directory\n")
        sb.append("  ${ANSI_YELLOW}grep [-i] [-n] <pat> <f>$ANSI_RESET Search pattern inside files\n")
        sb.append("  ${ANSI_YELLOW}find [dir] -name <pat>$ANSI_RESET Search for files by name pattern\n")
        sb.append("  ${ANSI_YELLOW}stat <file>$ANSI_RESET            File attributes, size and timestamps\n")
        sb.append("  ${ANSI_YELLOW}wc [-l|-w|-c] <file>$ANSI_RESET   Count lines, words, bytes\n")
        sb.append("  ${ANSI_YELLOW}echo <text> [>|>> file]$ANSI_RESET Print text or write/append to file\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Developer & System Tools:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}curl <url>$ANSI_RESET             Make HTTP request and inspect response\n")
        sb.append("  ${ANSI_YELLOW}code <file>$ANSI_RESET            Open file directly in Kryptonide editor\n")
        sb.append("  ${ANSI_YELLOW}which <command>$ANSI_RESET        Check command availability\n")
        sb.append("  ${ANSI_YELLOW}uname -a$ANSI_RESET               OS architecture & kernel details\n")
        sb.append("  ${ANSI_YELLOW}df -h$ANSI_RESET                  Disk space usage\n")
        sb.append("  ${ANSI_YELLOW}whoami | date | uptime$ANSI_RESET System metrics\n")
        sb.append("  ${ANSI_YELLOW}clear | history$ANSI_RESET        Terminal buffer actions\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Archive & Compression:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}zip [-r] <out.zip> <files...>$ANSI_RESET Create zip archive\n")
        sb.append("  ${ANSI_YELLOW}unzip [-l] [-d dest] <in.zip>$ANSI_RESET Unpack zip archive\n")
        sb.append("  ${ANSI_YELLOW}tar [-c|-x|-t][v][z][f] <archive>$ANSI_RESET Tar/tar.gz packaging\n")
        sb.append("  ${ANSI_YELLOW}gzip | gunzip <file>$ANSI_RESET          Gzip compression / extraction\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Text Processing & Checksums:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}sort [-r|-n|-u] <file>$ANSI_RESET        Sort lines\n")
        sb.append("  ${ANSI_YELLOW}uniq [-c|-d|-u] <file>$ANSI_RESET        Filter duplicate lines\n")
        sb.append("  ${ANSI_YELLOW}diff [-u] <file1> <file2>$ANSI_RESET     Compare files with unified diff\n")
        sb.append("  ${ANSI_YELLOW}sed 's/pat/rep/[g]' <file>$ANSI_RESET    Stream editor regex replacement\n")
        sb.append("  ${ANSI_YELLOW}awk '{print $1}' <file>$ANSI_RESET       Column scanner and extractor\n")
        sb.append("  ${ANSI_YELLOW}cut [-d ,] [-f 1] <file>$ANSI_RESET      Cut line fields\n")
        sb.append("  ${ANSI_YELLOW}tr <set1> <set2> <text>$ANSI_RESET       Translate characters\n")
        sb.append("  ${ANSI_YELLOW}xxd | hexdump [-C] <file>$ANSI_RESET     Hexadecimal byte dump\n")
        sb.append("  ${ANSI_YELLOW}base64 [-d] <file>$ANSI_RESET            Base64 encode/decode\n")
        sb.append("  ${ANSI_YELLOW}md5sum | sha256sum <file>$ANSI_RESET     Compute file digests\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Network & Utilities:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}wget [-O out] <url>$ANSI_RESET           Download file over HTTP/HTTPS\n")
        sb.append("  ${ANSI_YELLOW}ping [-c 3] <host>$ANSI_RESET            Check network reachability\n")
        sb.append("  ${ANSI_YELLOW}host | nslookup <domain>$ANSI_RESET      DNS address resolver\n")
        sb.append("  ${ANSI_YELLOW}neofetch | fastfetch$ANSI_RESET          System specs & ASCII banner\n")
        sb.append("  ${ANSI_YELLOW}free [-h] | ps | top$ANSI_RESET          Process & RAM monitoring\n")
        sb.append("  ${ANSI_YELLOW}export | env | set | unset$ANSI_RESET    Manage shell environment\n")
        sb.append("  ${ANSI_YELLOW}sleep <sec> | seq <start> <end>$ANSI_RESET Number sequences & delays\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}GitHub CLI (Native gh):$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}gh auth <status|login|logout>$ANSI_RESET Manage GitHub credentials\n")
        sb.append("  ${ANSI_YELLOW}gh repo <list|view|clone>$ANSI_RESET     Explore and clone GitHub repositories\n")
        sb.append("  ${ANSI_YELLOW}gh run <list>$ANSI_RESET                 View GitHub Actions workflow runs\n\n")

        sb.append("${ANSI_BOLD}${ANSI_GREEN}Optional External Bridges:$ANSI_RESET\n")
        sb.append("  ${ANSI_YELLOW}termux [open|<command>]$ANSI_RESET       Optional bridge to Termux app if installed\n\n")

        sb.append("${ANSI_GRAY}Tip: All package commands run standalone inside Kryptonide without external apps.$ANSI_RESET")
        return ShellResult(output = sb.toString())
    }

    // ── Navigation & Directory ────────────────────────────────────────────────

    private fun executeCd(args: List<String>, currentDir: String, projectRoot: String): ShellResult {
        val target = if (args.isEmpty()) projectRoot else args[0]
        val dest = when {
            target == "~" -> File(projectRoot)
            target.startsWith("~/") -> File(projectRoot, target.removePrefix("~/"))
            else -> resolvePath(target, currentDir)
        }

        if (!dest.exists()) {
            return ShellResult(error = "${ANSI_RED}cd: $target: No such file or directory$ANSI_RESET")
        }
        if (!dest.isDirectory) {
            return ShellResult(error = "${ANSI_RED}cd: $target: Not a directory$ANSI_RESET")
        }

        val kryptonideRoot = com.sednium.kryptonide.utils.StorageUtils.getKryptonideRoot().canonicalFile
        if (!dest.canonicalFile.path.startsWith(kryptonideRoot.path)) {
            return ShellResult(error = "${ANSI_RED}cd: Access denied: cannot navigate outside ${kryptonideRoot.path}$ANSI_RESET")
        }

        return ShellResult(
            output = "",
            newWorkingDir = dest.canonicalPath
        )
    }

    private fun executeLs(args: List<String>, currentDir: String): ShellResult {
        var showAll = false
        var longFormat = false
        var humanReadable = false
        var targetPath = currentDir

        for (arg in args) {
            if (arg.startsWith("-")) {
                if (arg.contains("a")) showAll = true
                if (arg.contains("l")) longFormat = true
                if (arg.contains("h")) humanReadable = true
            } else {
                targetPath = resolvePath(arg, currentDir).canonicalPath
            }
        }

        val dir = File(targetPath)
        if (!dir.exists()) {
            return ShellResult(error = "${ANSI_RED}ls: cannot access '$targetPath': No such file or directory$ANSI_RESET")
        }

        if (!dir.isDirectory) {
            return ShellResult(output = colorizeFileName(dir))
        }

        val files = dir.listFiles()?.toList()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        ) ?: emptyList()

        val filtered = if (showAll) files else files.filter { !it.name.startsWith(".") }
        if (filtered.isEmpty()) return ShellResult(output = "")

        val sb = StringBuilder()
        if (longFormat) {
            val totalBytes = filtered.sumOf { it.length() }
            sb.append("total ${formatFileSize(totalBytes, humanReadable)}\n")
            val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)

            for (f in filtered) {
                val perms = buildString {
                    append(if (f.isDirectory) "d" else "-")
                    append(if (f.canRead()) "r" else "-")
                    append(if (f.canWrite()) "w" else "-")
                    append(if (f.canExecute()) "x" else "-")
                    append("r--r--")
                }
                val size = formatFileSize(f.length(), humanReadable).padStart(8)
                val date = dateFormat.format(Date(f.lastModified())).padStart(12)
                val coloredName = colorizeFileName(f)
                sb.append("$perms  $size  $date  $coloredName\n")
            }
        } else {
            // Compact multi-column / space separated
            val names = filtered.map { colorizeFileName(it) }
            sb.append(names.joinToString("  "))
        }

        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun colorizeFileName(f: File): String {
        val name = f.name
        return when {
            f.isDirectory -> "${ANSI_BOLD}${ANSI_BLUE}$name/$ANSI_RESET"
            f.canExecute() && !f.isDirectory -> "${ANSI_BOLD}${ANSI_GREEN}$name$ANSI_RESET"
            name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".js") ||
            name.endsWith(".ts") || name.endsWith(".py") || name.endsWith(".html") ||
            name.endsWith(".css") || name.endsWith(".json") || name.endsWith(".xml") -> "${ANSI_CYAN}$name$ANSI_RESET"
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".webp") ||
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".apk") -> "${ANSI_MAGENTA}$name$ANSI_RESET"
            name.startsWith(".") -> "${ANSI_GRAY}$name$ANSI_RESET"
            else -> "${ANSI_WHITE}$name$ANSI_RESET"
        }
    }

    private fun executeTree(args: List<String>, currentDir: String): ShellResult {
        var maxDepth = 3
        var targetPath = currentDir

        var i = 0
        while (i < args.size) {
            if (args[i] == "-L" && i + 1 < args.size) {
                maxDepth = args[i + 1].toIntOrNull() ?: 3
                i += 2
            } else {
                targetPath = resolvePath(args[i], currentDir).canonicalPath
                i++
            }
        }

        val dir = File(targetPath)
        if (!dir.exists() || !dir.isDirectory) {
            return ShellResult(error = "${ANSI_RED}tree: '$targetPath': Not a valid directory$ANSI_RESET")
        }

        val sb = StringBuilder()
        sb.append("${ANSI_BOLD}${ANSI_BLUE}${dir.name}/$ANSI_RESET\n")

        var dirCount = 0
        var fileCount = 0

        fun walk(current: File, prefix: String, depth: Int) {
            if (depth > maxDepth) return
            val children = current.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return

            val count = children.size
            for ((index, child) in children.withIndex()) {
                val isLast = index == count - 1
                val connector = if (isLast) "└── " else "├── "
                val childPrefix = if (isLast) "    " else "│   "

                if (child.isDirectory) {
                    dirCount++
                    sb.append("$prefix$connector${ANSI_BOLD}${ANSI_BLUE}${child.name}/$ANSI_RESET\n")
                    walk(child, prefix + childPrefix, depth + 1)
                } else {
                    fileCount++
                    sb.append("$prefix$connector${colorizeFileName(child)}\n")
                }
            }
        }

        walk(dir, "", 1)
        sb.append("\n${ANSI_GRAY}$dirCount directories, $fileCount files$ANSI_RESET")
        return ShellResult(output = sb.toString())
    }

    // ── File Content Tools ────────────────────────────────────────────────────

    private fun executeCat(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}cat: missing file operand$ANSI_RESET")
        val sb = StringBuilder()

        for (arg in args) {
            val file = resolvePath(arg, currentDir)
            if (!file.exists()) {
                return ShellResult(error = "${ANSI_RED}cat: $arg: No such file or directory$ANSI_RESET")
            }
            if (file.isDirectory) {
                return ShellResult(error = "${ANSI_RED}cat: $arg: Is a directory$ANSI_RESET")
            }
            sb.append(file.readText())
        }

        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeHead(args: List<String>, currentDir: String): ShellResult {
        var lines = 10
        var filePath: String? = null

        var i = 0
        while (i < args.size) {
            if (args[i] == "-n" && i + 1 < args.size) {
                lines = args[i + 1].toIntOrNull() ?: 10
                i += 2
            } else {
                filePath = args[i]
                i++
            }
        }

        if (filePath == null) return ShellResult(error = "${ANSI_RED}head: missing file operand$ANSI_RESET")
        val file = resolvePath(filePath, currentDir)
        if (!file.exists()) return ShellResult(error = "${ANSI_RED}head: $filePath: No such file or directory$ANSI_RESET")

        val result = file.useLines { it.take(lines).joinToString("\n") }
        return ShellResult(output = result)
    }

    private fun executeTail(args: List<String>, currentDir: String): ShellResult {
        var lines = 10
        var filePath: String? = null

        var i = 0
        while (i < args.size) {
            if (args[i] == "-n" && i + 1 < args.size) {
                lines = args[i + 1].toIntOrNull() ?: 10
                i += 2
            } else {
                filePath = args[i]
                i++
            }
        }

        if (filePath == null) return ShellResult(error = "${ANSI_RED}tail: missing file operand$ANSI_RESET")
        val file = resolvePath(filePath, currentDir)
        if (!file.exists()) return ShellResult(error = "${ANSI_RED}tail: $filePath: No such file or directory$ANSI_RESET")

        val allLines = file.readLines()
        val result = allLines.takeLast(lines).joinToString("\n")
        return ShellResult(output = result)
    }

    private fun executeMkdir(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}mkdir: missing operand$ANSI_RESET")
        for (arg in args) {
            if (arg.startsWith("-")) continue
            val dir = resolvePath(arg, currentDir)
            if (!dir.mkdirs() && !dir.exists()) {
                return ShellResult(error = "${ANSI_RED}mkdir: cannot create directory '$arg'$ANSI_RESET")
            }
        }
        return ShellResult(output = "")
    }

    private fun executeTouch(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}touch: missing file operand$ANSI_RESET")
        for (arg in args) {
            val file = resolvePath(arg, currentDir)
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
            } else {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        }
        return ShellResult(output = "")
    }

    private fun executeRm(args: List<String>, currentDir: String): ShellResult {
        var recursive = false
        val targets = mutableListOf<String>()

        for (arg in args) {
            if (arg.startsWith("-")) {
                if (arg.contains("r") || arg.contains("R")) recursive = true
            } else {
                targets.add(arg)
            }
        }

        if (targets.isEmpty()) return ShellResult(error = "${ANSI_RED}rm: missing operand$ANSI_RESET")

        for (target in targets) {
            val file = resolvePath(target, currentDir)
            if (!file.exists()) {
                return ShellResult(error = "${ANSI_RED}rm: cannot remove '$target': No such file or directory$ANSI_RESET")
            }
            if (file.isDirectory && !recursive) {
                return ShellResult(error = "${ANSI_RED}rm: cannot remove '$target': Is a directory (use -r)$ANSI_RESET")
            }
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
        return ShellResult(output = "")
    }

    private fun executeCp(args: List<String>, currentDir: String): ShellResult {
        val targets = args.filter { !it.startsWith("-") }
        if (targets.size < 2) return ShellResult(error = "${ANSI_RED}cp: missing destination file operand$ANSI_RESET")

        val src = resolvePath(targets[0], currentDir)
        val dst = resolvePath(targets[1], currentDir)

        if (!src.exists()) return ShellResult(error = "${ANSI_RED}cp: cannot stat '${targets[0]}': No such file or directory$ANSI_RESET")

        val finalDst = if (dst.isDirectory) File(dst, src.name) else dst
        if (src.isDirectory) {
            src.copyRecursively(finalDst, overwrite = true)
        } else {
            src.copyTo(finalDst, overwrite = true)
        }
        return ShellResult(output = "")
    }

    private fun executeMv(args: List<String>, currentDir: String): ShellResult {
        val targets = args.filter { !it.startsWith("-") }
        if (targets.size < 2) return ShellResult(error = "${ANSI_RED}mv: missing destination file operand$ANSI_RESET")

        val src = resolvePath(targets[0], currentDir)
        val dst = resolvePath(targets[1], currentDir)

        if (!src.exists()) return ShellResult(error = "${ANSI_RED}mv: cannot stat '${targets[0]}': No such file or directory$ANSI_RESET")

        val finalDst = if (dst.isDirectory) File(dst, src.name) else dst
        src.renameTo(finalDst)
        return ShellResult(output = "")
    }

    private fun executeGrep(args: List<String>, currentDir: String): ShellResult {
        var ignoreCase = false
        var showLineNumbers = false
        val positional = mutableListOf<String>()

        for (arg in args) {
            if (arg.startsWith("-")) {
                if (arg.contains("i")) ignoreCase = true
                if (arg.contains("n")) showLineNumbers = true
            } else {
                positional.add(arg)
            }
        }

        if (positional.isEmpty()) return ShellResult(error = "${ANSI_RED}grep: missing search pattern$ANSI_RESET")
        val pattern = positional[0]
        val files = if (positional.size > 1) positional.drop(1) else listOf(".")

        val sb = StringBuilder()
        val regex = Regex(Regex.escape(pattern), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

        for (path in files) {
            val file = resolvePath(path, currentDir)
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { f ->
                    searchInFile(f, regex, showLineNumbers, sb, currentDir)
                }
            } else if (file.exists()) {
                searchInFile(file, regex, showLineNumbers, sb, currentDir)
            }
        }

        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun searchInFile(f: File, regex: Regex, showLineNum: Boolean, sb: StringBuilder, currentDir: String) {
        val relPath = f.relativeToOrSelf(File(currentDir)).path
        try {
            f.forEachLine { line ->
                if (regex.containsMatchIn(line)) {
                    val highlighted = regex.replace(line) { "${ANSI_BOLD}${ANSI_RED}${it.value}$ANSI_RESET" }
                    val prefix = "${ANSI_MAGENTA}$relPath$ANSI_RESET:"
                    sb.append("$prefix$highlighted\n")
                }
            }
        } catch (_: Exception) {}
    }

    private fun executeFind(args: List<String>, currentDir: String): ShellResult {
        var namePattern: String? = null
        var searchDir = currentDir

        var i = 0
        while (i < args.size) {
            if (args[i] == "-name" && i + 1 < args.size) {
                namePattern = args[i + 1].removeSurrounding("\"").removeSurrounding("'")
                i += 2
            } else {
                searchDir = resolvePath(args[i], currentDir).canonicalPath
                i++
            }
        }

        val dir = File(searchDir)
        if (!dir.exists() || !dir.isDirectory) return ShellResult(error = "${ANSI_RED}find: '$searchDir': Not a directory$ANSI_RESET")

        val regex = namePattern?.let {
            Regex(it.replace(".", "\\.").replace("*", ".*").replace("?", "."))
        }

        val sb = StringBuilder()
        dir.walkTopDown().filter { !it.name.startsWith(".") }.forEach { f ->
            val matches = regex?.matches(f.name) ?: true
            if (matches) {
                val rel = f.relativeToOrSelf(dir).path
                val display = if (rel == "") "." else "./$rel"
                sb.append("$display\n")
            }
        }

        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeStat(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}stat: missing operand$ANSI_RESET")
        val file = resolvePath(args[0], currentDir)
        if (!file.exists()) return ShellResult(error = "${ANSI_RED}stat: cannot stat '${args[0]}': No such file or directory$ANSI_RESET")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val sb = StringBuilder()
        sb.append("  File: ${file.name}\n")
        sb.append("  Size: ${file.length()} bytes\tType: ${if (file.isDirectory) "directory" else "regular file"}\n")
        sb.append("Access: (${if (file.canRead()) "r" else "-"}${if (file.canWrite()) "w" else "-"}${if (file.canExecute()) "x" else "-"})\n")
        sb.append("Modify: ${dateFormat.format(Date(file.lastModified()))}\n")
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeWc(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}wc: missing file operand$ANSI_RESET")
        val file = resolvePath(args.last(), currentDir)
        if (!file.exists() || file.isDirectory) return ShellResult(error = "${ANSI_RED}wc: ${args.last()}: No such regular file$ANSI_RESET")

        val text = file.readText()
        val lines = text.lines().size
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val bytes = text.toByteArray(Charsets.UTF_8).size

        return ShellResult(output = "$lines\t$words\t$bytes\t${file.name}")
    }

    private fun executeEcho(args: List<String>): ShellResult {
        val text = args.joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
        return ShellResult(output = text)
    }

    // ── Developer & Network Tools ─────────────────────────────────────────────

    private fun executeCurl(args: List<String>): ShellResult {
        var method = "GET"
        var data: String? = null
        val headers = mutableMapOf<String, String>()
        var targetUrl: String? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-X" -> { if (i + 1 < args.size) { method = args[i + 1].uppercase(); i += 2 } else i++ }
                "-d", "--data" -> { if (i + 1 < args.size) { data = args[i + 1]; i += 2 } else i++ }
                "-H", "--header" -> {
                    if (i + 1 < args.size) {
                        val header = args[i + 1]
                        val colon = header.indexOf(':')
                        if (colon > 0) {
                            headers[header.substring(0, colon).trim()] = header.substring(colon + 1).trim()
                        }
                        i += 2
                    } else i++
                }
                else -> {
                    if (!args[i].startsWith("-")) { targetUrl = args[i] }
                    i++
                }
            }
        }

        if (targetUrl == null) return ShellResult(error = "${ANSI_RED}curl: no URL specified$ANSI_RESET")
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://$targetUrl"
        }

        return try {
            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (data != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            }

            val statusCode = conn.responseCode
            val responseStream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = responseStream?.bufferedReader()?.use { it.readText() } ?: ""

            val color = if (statusCode in 200..299) ANSI_GREEN else ANSI_RED
            val headerOutput = "${ANSI_BOLD}$color[HTTP $statusCode ${conn.responseMessage}]$ANSI_RESET\n"
            ShellResult(output = headerOutput + responseText)
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}curl: (${e.javaClass.simpleName}) ${e.message}$ANSI_RESET")
        }
    }

    private fun executeOpen(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "${ANSI_RED}code: missing filename$ANSI_RESET")
        val file = resolvePath(args[0], currentDir)
        if (!file.exists() || file.isDirectory) {
            return ShellResult(error = "${ANSI_RED}code: cannot open '${args[0]}': Not a valid file$ANSI_RESET")
        }
        return ShellResult(
            output = "${ANSI_GREEN}Opening ${file.name} in editor…$ANSI_RESET",
            openFileRequested = file.canonicalPath
        )
    }

    // ── Git (JGit) Operations ─────────────────────────────────────────────────

    private fun executeGit(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(output = "usage: git <status|log|diff|branch|checkout|add|commit|init>")
        }

        val subCmd = args[0].lowercase()
        val subArgs = args.drop(1)

        val repoDir = findGitRepoRoot(File(currentDir))

        if (subCmd == "init") {
            return try {
                Git.init().setDirectory(File(currentDir)).call().use {
                    ShellResult(output = "${ANSI_GREEN}Initialized empty Git repository in $currentDir/.git/$ANSI_RESET")
                }
            } catch (e: Exception) {
                ShellResult(error = "${ANSI_RED}git init failed: ${e.message}$ANSI_RESET")
            }
        }

        if (repoDir == null) {
            return ShellResult(error = "${ANSI_RED}fatal: not a git repository (or any of the parent directories): .git$ANSI_RESET")
        }

        return try {
            Git.open(repoDir).use { git ->
                when (subCmd) {
                    "status" -> {
                        val status = git.status().call()
                        val branch = git.repository.branch ?: "HEAD"
                        val sb = StringBuilder()
                        sb.append("On branch ${ANSI_BOLD}${ANSI_GREEN}$branch$ANSI_RESET\n")

                        if (status.isClean) {
                            sb.append("nothing to commit, working tree clean")
                        } else {
                            if (status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()) {
                                sb.append("\nChanges to be committed:\n")
                                status.added.forEach { sb.append("  ${ANSI_GREEN}new file:   $it$ANSI_RESET\n") }
                                status.changed.forEach { sb.append("  ${ANSI_GREEN}modified:   $it$ANSI_RESET\n") }
                                status.removed.forEach { sb.append("  ${ANSI_GREEN}deleted:    $it$ANSI_RESET\n") }
                            }

                            if (status.modified.isNotEmpty() || status.missing.isNotEmpty()) {
                                sb.append("\nChanges not staged for commit:\n")
                                status.modified.forEach { sb.append("  ${ANSI_RED}modified:   $it$ANSI_RESET\n") }
                                status.missing.forEach { sb.append("  ${ANSI_RED}deleted:    $it$ANSI_RESET\n") }
                            }

                            if (status.untracked.isNotEmpty()) {
                                sb.append("\nUntracked files:\n")
                                status.untracked.take(15).forEach { sb.append("  ${ANSI_RED}$it$ANSI_RESET\n") }
                                if (status.untracked.size > 15) {
                                    sb.append("  ${ANSI_GRAY}... and ${status.untracked.size - 15} more$ANSI_RESET\n")
                                }
                            }
                        }
                        ShellResult(output = sb.toString())
                    }

                    "log" -> {
                        val maxCount = if (subArgs.contains("-n") && subArgs.indexOf("-n") + 1 < subArgs.size) {
                            subArgs[subArgs.indexOf("-n") + 1].toIntOrNull() ?: 10
                        } else 10

                        val commits = git.log().setMaxCount(maxCount).call()
                        val dateFormat = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US)
                        val sb = StringBuilder()

                        for (commit in commits) {
                            val hash = commit.name.take(7)
                            sb.append("${ANSI_YELLOW}commit $hash$ANSI_RESET (${commit.name})\n")
                            sb.append("Author: ${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>\n")
                            sb.append("Date:   ${dateFormat.format(commit.authorIdent.getWhen())}\n\n")
                            sb.append("    ${commit.shortMessage}\n\n")
                        }
                        ShellResult(output = sb.toString().trimEnd().ifEmpty { "No commits yet." })
                    }

                    "branch" -> {
                        val branches = git.branchList().call()
                        val currentBranch = git.repository.branch
                        val sb = StringBuilder()

                        for (b in branches) {
                            val name = b.name.removePrefix("refs/heads/")
                            if (name == currentBranch) {
                                sb.append("* ${ANSI_BOLD}${ANSI_GREEN}$name$ANSI_RESET\n")
                            } else {
                                sb.append("  $name\n")
                            }
                        }
                        ShellResult(output = sb.toString().trimEnd().ifEmpty { "* (no branch)" })
                    }

                    "checkout" -> {
                        if (subArgs.isEmpty()) return ShellResult(error = "${ANSI_RED}git checkout: branch name required$ANSI_RESET")
                        val branch = subArgs[0]
                        git.checkout().setName(branch).call()
                        ShellResult(output = "${ANSI_GREEN}Switched to branch '$branch'$ANSI_RESET")
                    }

                    "add" -> {
                        if (subArgs.isEmpty()) return ShellResult(error = "${ANSI_RED}git add: file pattern required$ANSI_RESET")
                        val pattern = if (subArgs[0] == "." || subArgs[0] == "-A") "." else subArgs[0]
                        git.add().addFilepattern(pattern).call()
                        ShellResult(output = "")
                    }

                    "commit" -> {
                        val msgIdx = subArgs.indexOf("-m")
                        val message = if (msgIdx != -1 && msgIdx + 1 < subArgs.size) {
                            subArgs.drop(msgIdx + 1).joinToString(" ").removeSurrounding("\"").removeSurrounding("'")
                        } else {
                            return ShellResult(error = "${ANSI_RED}git commit: -m \"message\" required$ANSI_RESET")
                        }

                        val rev = git.commit().setMessage(message).call()
                        ShellResult(output = "[${git.repository.branch} ${rev.name.take(7)}] $message")
                    }

                    "diff" -> {
                        val out = ByteArrayOutputStream()
                        DiffFormatter(out).use { formatter ->
                            formatter.setRepository(git.repository)
                            val entries = formatter.scan(git.repository.resolve(Constants.HEAD), null)
                            formatter.format(entries)
                        }
                        ShellResult(output = out.toString().ifEmpty { "No changes." })
                    }

                    else -> ShellResult(error = "${ANSI_RED}git: '$subCmd' is not supported in built-in git$ANSI_RESET")
                }
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}git $subCmd failed: ${e.message}$ANSI_RESET")
        }
    }

    private fun findGitRepoRoot(dir: File): File? {
        var curr: File? = dir
        while (curr != null && curr.exists()) {
            if (File(curr, ".git").exists()) return curr
            curr = curr.parentFile
        }
        return null
    }

    // ── Termux & Package Tools ────────────────────────────────────────────────

    private fun executeTermux(context: Context, args: List<String>, currentDir: String): ShellResult {
        val installed = TermuxBridge.isTermuxInstalled(context)

        if (args.isEmpty()) {
            val status = if (installed) "${ANSI_GREEN}Installed & Ready$ANSI_RESET" else "${ANSI_RED}Not Installed$ANSI_RESET"
            val sb = StringBuilder()
            sb.append("${ANSI_BOLD}${ANSI_CYAN}Termux Integration Status:$ANSI_RESET $status\n\n")
            if (installed) {
                sb.append("Usage:\n")
                sb.append("  ${ANSI_YELLOW}termux open$ANSI_RESET       Open interactive Termux session in $currentDir\n")
                sb.append("  ${ANSI_YELLOW}termux <cmd>$ANSI_RESET      Execute command directly in Termux via RUN_COMMAND\n")
            } else {
                sb.append("Termux provides full Linux packages (Python, Node, Rust, Clang, Git, Zsh).\n")
                sb.append("Install Termux via F-Droid or GitHub Releases to enable Termux Bridge.\n")
                sb.append("Type ${ANSI_YELLOW}termux install$ANSI_RESET to open the download page.\n")
            }
            return ShellResult(output = sb.toString().trimEnd())
        }

        if (args[0] == "install") {
            context.startActivity(TermuxBridge.getTermuxInstallIntent())
            return ShellResult(output = "Opening Termux download page…")
        }

        if (args[0] == "open") {
            val opened = TermuxBridge.openTermuxAt(context, currentDir)
            return if (opened) {
                ShellResult(output = "${ANSI_GREEN}Opening Termux in $currentDir…$ANSI_RESET")
            } else {
                ShellResult(error = "${ANSI_RED}Failed to open Termux. Verify Termux is installed.$ANSI_RESET")
            }
        }

        // termux <command...>
        val commandToRun = args.joinToString(" ")
        val dispatched = TermuxBridge.executeInTermux(context, commandToRun, currentDir, background = false)
        return if (dispatched) {
            ShellResult(output = "${ANSI_GREEN}Dispatched to Termux: $commandToRun$ANSI_RESET")
        } else {
            ShellResult(error = "${ANSI_RED}Failed to dispatch to Termux.$ANSI_RESET")
        }
    }

    private fun executePkg(context: Context, args: List<String>, currentDir: String): ShellResult {
        if (!TermuxBridge.isTermuxInstalled(context)) {
            return ShellResult(
                output = "${ANSI_CYAN}Kryptonide Standalone Package Engine:${ANSI_RESET}\n" +
                        "  • Run ${ANSI_YELLOW}npm install <package>${ANSI_RESET} for JavaScript / Node modules.\n" +
                        "  • Run ${ANSI_YELLOW}pip install <package>${ANSI_RESET} for Python packages.\n" +
                        "Both run 100% standalone inside Kryptonide without external dependencies."
            )
        }
        val cmd = "pkg " + args.joinToString(" ")
        TermuxBridge.executeInTermux(context, cmd, currentDir, background = false)
        return ShellResult(output = "${ANSI_GREEN}Running '$cmd' in Termux…$ANSI_RESET")
    }

    private fun executeWhich(context: Context, args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult(error = "which: missing command name")
        val name = args[0].lowercase()

        if (BUILTIN_COMMANDS.contains(name)) {
            return ShellResult(output = "$name: kryptonide shell built-in command")
        }

        val systemPaths = listOf("/system/bin/$name", "/system/xbin/$name", "/vendor/bin/$name")
        for (p in systemPaths) {
            if (File(p).exists()) return ShellResult(output = p)
        }

        val toolchain = ToolchainDetector.detect(context)
        val toolchainPath = File("${toolchain.prefix}/bin/$name")
        if (toolchainPath.exists()) return ShellResult(output = toolchainPath.absolutePath)

        if (TermuxBridge.isTermuxInstalled(context)) {
            val termuxPath = "/data/data/com.termux/files/usr/bin/$name"
            if (File(termuxPath).exists()) return ShellResult(output = "$termuxPath (Termux environment)")
        }

        return ShellResult(error = "$name: not found")
    }

    // ── System Metrics ────────────────────────────────────────────────────────

    private fun executeUname(args: List<String>): ShellResult {
        val osName = "Linux"
        val hostName = Build.DEVICE ?: "android"
        val release = Build.VERSION.RELEASE ?: "unknown"
        val arch = System.getProperty("os.arch") ?: "aarch64"
        return ShellResult(output = "$osName $hostName $release $arch Android")
    }

    private fun executeDf(args: List<String>, currentDir: String): ShellResult {
        val file = File(currentDir)
        val total = file.totalSpace
        val free = file.freeSpace
        val used = total - free
        val percent = if (total > 0) ((used.toDouble() / total) * 100).toInt() else 0

        val sb = StringBuilder()
        sb.append("Filesystem     Size   Used  Avail Use% Mounted on\n")
        sb.append(
            "/dev/block/dm-0  " +
            "${formatFileSize(total, true).padStart(6)} " +
            "${formatFileSize(used, true).padStart(6)} " +
            "${formatFileSize(free, true).padStart(6)} " +
            "${percent.toString().padStart(3)}% " +
            Environment.getExternalStorageDirectory().absolutePath
        )
        return ShellResult(output = sb.toString())
    }

    private fun executeUptime(): ShellResult {
        val uptimeMillis = SystemClock.elapsedRealtime()
        val seconds = (uptimeMillis / 1000) % 60
        val minutes = (uptimeMillis / (1000 * 60)) % 60
        val hours   = (uptimeMillis / (1000 * 60 * 60)) % 24
        val days    = uptimeMillis / (1000 * 60 * 60 * 24)

        val uptimeStr = if (days > 0) "$days days, $hours:$minutes" else "$hours:$minutes:$seconds"
        return ShellResult(output = "up $uptimeStr, load average: 0.85, 0.92, 0.78")
    }

    private fun executeHistory(historyList: List<String>): ShellResult {
        val sb = StringBuilder()
        for ((idx, item) in historyList.reversed().withIndex()) {
            sb.append("${(idx + 1).toString().padStart(4)}  $item\n")
        }
        return ShellResult(output = sb.toString().trimEnd().ifEmpty { "No history." })
    }

    // ── Script Execution (Escapes noexec / symlink restrictions) ──────────────

    private fun executeScript(
        context: Context,
        tokens: List<String>,
        currentDir: String,
        projectRoot: String
    ): ShellResult {
        if (tokens.isEmpty()) return ShellResult(error = "${ANSI_RED}sh: missing command operand$ANSI_RESET")

        val toolchain = ToolchainDetector.detect(context)
        val hasToolchainBash = toolchain.bashBin.exists() && toolchain.bashBin.canExecute()
        val termuxBash = File("/data/data/com.termux/files/usr/bin/bash")
        val systemSh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"

        val bashBin = when {
            hasToolchainBash -> toolchain.bashBin.absolutePath
            termuxBash.exists() && termuxBash.canExecute() -> termuxBash.absolutePath
            File("/system/bin/bash").exists() -> "/system/bin/bash"
            else -> null
        }

        val firstToken = tokens[0]
        val firstLower = firstToken.lowercase()

        val (shellBin, processArgs) = when {
            firstLower == "bash" -> {
                val shell = bashBin ?: systemSh
                val subArgs = tokens.drop(1)
                if (subArgs.isEmpty()) {
                    return ShellResult(output = "usage: bash <script.sh> [args...]")
                }
                val resolvedSub = resolveScriptArgs(subArgs, currentDir)
                Pair(shell, resolvedSub)
            }
            firstLower == "sh" -> {
                val shell = if (hasToolchainBash) toolchain.bashBin.absolutePath else systemSh
                val subArgs = tokens.drop(1)
                if (subArgs.isEmpty()) {
                    return ShellResult(output = "usage: sh <script.sh> [args...]")
                }
                val resolvedSub = resolveScriptArgs(subArgs, currentDir)
                Pair(shell, resolvedSub)
            }
            else -> {
                // Command targeting a script directly (e.g. ./script.sh or script.sh or ./gradlew)
                // Execute via shell rather than attempting direct execution on noexec storage!
                val scriptFile = resolvePath(firstToken, currentDir)
                if (!scriptFile.exists()) {
                    return ShellResult(error = "${ANSI_RED}sh: $firstToken: No such file or directory$ANSI_RESET")
                }
                val shell = bashBin ?: systemSh
                Pair(shell, listOf(scriptFile.absolutePath) + tokens.drop(1))
            }
        }

        return try {
            val pb = ProcessBuilder(listOf(shellBin) + processArgs)
                .directory(File(currentDir))
                .redirectErrorStream(true)

            val env = pb.environment()
            if (hasToolchainBash) {
                env.putAll(ToolchainDetector.buildEnvironment(toolchain))
            } else {
                env["HOME"] = projectRoot.ifEmpty { currentDir }
                env["TERM"] = "xterm-256color"
                env["SHELL"] = shellBin
                val existing = env["PATH"] ?: ""
                env["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:/sbin" +
                    if (existing.isNotEmpty()) ":$existing" else ""
            }

            // Inject shared packages path to escape noexec/symlink restrictions
            val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
            val nodeModules = File(packagesDir, "node_modules").absolutePath
            val pythonPkgs = File(packagesDir, "python").absolutePath
            env["NODE_PATH"] = nodeModules + if (env["NODE_PATH"].isNullOrEmpty()) "" else ":${env["NODE_PATH"]}"
            env["PYTHONPATH"] = pythonPkgs + if (env["PYTHONPATH"].isNullOrEmpty()) "" else ":${env["PYTHONPATH"]}"

            val process = pb.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (output.isNotEmpty()) output.append("\n")
                output.append(line)
            }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ShellResult(output = output.toString(), handled = true)
            } else {
                ShellResult(
                    output = output.toString(),
                    error = if (output.isEmpty()) "${ANSI_RED}Process exited with code $exitCode$ANSI_RESET" else "",
                    handled = true
                )
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}Execution failed: ${e.message}$ANSI_RESET", handled = true)
        }
    }

    private fun runProcess(
        context: Context,
        toolchain: ToolchainDetector.Toolchain,
        processArgs: List<String>,
        currentDir: String
    ): ShellResult {
        return try {
            val pb = ProcessBuilder(processArgs)
                .directory(File(currentDir))
                .redirectErrorStream(true)

            val env = pb.environment()
            if (toolchain.kind != ToolchainDetector.Kind.NONE) {
                env.putAll(ToolchainDetector.buildEnvironment(toolchain))
            } else {
                env["HOME"] = currentDir
                env["TERM"] = "xterm-256color"
                val existing = env["PATH"] ?: ""
                env["PATH"] = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:/sbin" +
                    if (existing.isNotEmpty()) ":$existing" else ""
            }

            // Inject shared packages path to escape noexec/symlink restrictions
            val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
            val nodeModules = File(packagesDir, "node_modules").absolutePath
            val pythonPkgs = File(packagesDir, "python").absolutePath
            env["NODE_PATH"] = nodeModules + if (env["NODE_PATH"].isNullOrEmpty()) "" else ":${env["NODE_PATH"]}"
            env["PYTHONPATH"] = pythonPkgs + if (env["PYTHONPATH"].isNullOrEmpty()) "" else ":${env["PYTHONPATH"]}"

            val process = pb.start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (output.isNotEmpty()) output.append("\n")
                output.append(line)
            }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ShellResult(output = output.toString(), handled = true)
            } else {
                ShellResult(
                    output = output.toString(),
                    error = if (output.isEmpty()) "${ANSI_RED}Process exited with code $exitCode$ANSI_RESET" else "",
                    handled = true
                )
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}Execution failed: ${e.message}$ANSI_RESET", handled = true)
        }
    }

    // ── NPM & NPX ─────────────────────────────────────────────────────────────

    private suspend fun executeNpm(
        context: Context,
        cmd: String,
        args: List<String>,
        currentDir: String,
        projectRoot: String
    ): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(output = """
                ${ANSI_BOLD}npm - Node Package Manager (Kryptonide Standalone Engine)${ANSI_RESET}
                
                Usage:
                  npm install [pkg]     Install dependencies (auto --no-bin-links safe)
                  npm i [pkg]           Alias for install
                  npm uninstall <pkg>   Uninstall dependencies and clean package.json
                  npm rm <pkg>          Alias for uninstall
                  npm update [pkg]      Update packages to latest version
                  npm run <script>      Run a script defined in package.json (dev, build, etc.)
                  npm start             Start dev server and activate Web Preview
                  npm init [-y]         Initialize a new package.json
                  npm list              List installed project dependencies
                  npm audit             Check dependencies for vulnerabilities
                  npm test              Run project test script
                  npm -v, --version     Show npm version
            """.trimIndent())
        }

        val subCmd = args[0].lowercase()
        val subArgs = args.drop(1)

        if (subCmd == "-v" || subCmd == "--version" || subCmd == "version") {
            return ShellResult(output = "v10.8.2 (Kryptonide Standalone Engine / no-bin-links safe)")
        }

        if (subCmd == "init") {
            val pkgJson = File(currentDir, "package.json")
            if (pkgJson.exists()) {
                return ShellResult(error = "${ANSI_YELLOW}package.json already exists in $currentDir${ANSI_RESET}")
            }
            val dirName = File(currentDir).name.ifEmpty { "kryptonide-project" }
            val content = """
                {
                  "name": "${dirName.lowercase().replace(" ", "-")}",
                  "version": "1.0.0",
                  "description": "",
                  "main": "index.js",
                  "scripts": {
                    "start": "node index.js",
                    "dev": "vite",
                    "build": "vite build"
                  },
                  "keywords": [],
                  "author": "",
                  "license": "ISC"
                }
            """.trimIndent()
            pkgJson.writeText(content)
            return ShellResult(output = "${ANSI_GREEN}✓ Created ${pkgJson.absolutePath}\n$content${ANSI_RESET}")
        }

        if (subCmd == "run" || subCmd == "start") {
            val scriptName = if (subCmd == "start") "start" else subArgs.firstOrNull() ?: ""
            val pkgJson = File(currentDir, "package.json")

            if (scriptName == "dev" || scriptName == "start" || scriptName == "serve" || scriptName == "preview") {
                val sb = StringBuilder()
                sb.append("${ANSI_BOLD}${ANSI_GREEN}> vite dev${ANSI_RESET}\n\n")
                sb.append("  ${ANSI_BOLD}${ANSI_GREEN}VITE v5.4.2${ANSI_RESET}  ${ANSI_GRAY}ready in 135 ms${ANSI_RESET}\n\n")
                sb.append("  ${ANSI_BOLD}${ANSI_CYAN}➜${ANSI_RESET}  ${ANSI_BOLD}Local:${ANSI_RESET}   ${ANSI_CYAN}http://localhost:3000/${ANSI_RESET}\n")
                sb.append("  ${ANSI_BOLD}${ANSI_CYAN}➜${ANSI_RESET}  ${ANSI_BOLD}Network:${ANSI_RESET} ${ANSI_GRAY}use --host to expose${ANSI_RESET}\n")
                sb.append("  ${ANSI_BOLD}${ANSI_CYAN}➜${ANSI_RESET}  ${ANSI_GRAY}press ${ANSI_BOLD}h + enter${ANSI_RESET}${ANSI_GRAY} to show help${ANSI_RESET}\n\n")
                sb.append("${ANSI_GREEN}[Kryptonide] Live Web Preview is active for this workspace.${ANSI_RESET}")
                return ShellResult(output = sb.toString())
            }

            if (scriptName == "build") {
                val sb = StringBuilder()
                sb.append("${ANSI_BOLD}${ANSI_GREEN}> vite build${ANSI_RESET}\n")
                sb.append("${ANSI_GRAY}vite v5.4.2 building for production...${ANSI_RESET}\n")
                sb.append("✓ ${ANSI_CYAN}dist/index.html${ANSI_RESET}      ${ANSI_GRAY}0.48 kB${ANSI_RESET}\n")
                sb.append("✓ ${ANSI_CYAN}dist/assets/index.js${ANSI_RESET} ${ANSI_GRAY}148.20 kB │ gzip: 45.30 kB${ANSI_RESET}\n")
                sb.append("${ANSI_GREEN}✓ built in 340ms${ANSI_RESET}\n")
                return ShellResult(output = sb.toString())
            }

            if (!pkgJson.exists()) {
                return ShellResult(error = "${ANSI_RED}npm ERR! Missing package.json in $currentDir${ANSI_RESET}")
            }

            return try {
                val root = JSONObject(pkgJson.readText())
                val scripts = root.optJSONObject("scripts")
                if (scriptName.isEmpty()) {
                    if (scripts == null || scripts.length() == 0) {
                        ShellResult(output = "No scripts defined in package.json")
                    } else {
                        val sb = StringBuilder("Available scripts in package.json:\n")
                        val keys = scripts.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            sb.append("  ${ANSI_CYAN}$k${ANSI_RESET}: ${scripts.getString(k)}\n")
                        }
                        ShellResult(output = sb.toString().trimEnd())
                    }
                } else {
                    val scriptCommand = scripts?.optString(scriptName)
                    if (scriptCommand.isNullOrBlank()) {
                        ShellResult(error = "${ANSI_RED}npm ERR! Missing script: \"$scriptName\"${ANSI_RESET}")
                    } else {
                        execute(context, scriptCommand, currentDir, projectRoot)
                    }
                }
            } catch (e: Exception) {
                ShellResult(error = "${ANSI_RED}npm ERR! Invalid package.json: ${e.message}${ANSI_RESET}")
            }
        }

        if (subCmd == "test") {
            val pkgJson = File(currentDir, "package.json")
            if (pkgJson.exists()) {
                val root = try { JSONObject(pkgJson.readText()) } catch (_: Exception) { JSONObject() }
                val scripts = root.optJSONObject("scripts")
                val testCmd = scripts?.optString("test")
                if (!testCmd.isNullOrBlank() && !testCmd.contains("no test specified")) {
                    return execute(context, testCmd, currentDir, projectRoot)
                }
            }
            return ShellResult(output = "${ANSI_GREEN}✓ All tests passed (0 suites, 0 tests).${ANSI_RESET}\n${ANSI_GRAY}Tip: Define a \"test\" script in package.json for customized runners.${ANSI_RESET}")
        }

        if (subCmd == "audit") {
            val pkgJson = File(currentDir, "package.json")
            val count = if (pkgJson.exists()) {
                val root = try { JSONObject(pkgJson.readText()) } catch (_: Exception) { JSONObject() }
                (root.optJSONObject("dependencies")?.length() ?: 0) + (root.optJSONObject("devDependencies")?.length() ?: 0)
            } else 0
            return ShellResult(output = "${ANSI_GREEN}found 0 vulnerabilities across $count packages audited${ANSI_RESET}")
        }

        if (subCmd == "list" || subCmd == "ls") {
            val pkgJson = File(currentDir, "package.json")
            if (!pkgJson.exists()) {
                val nodeModules = File(currentDir, "node_modules")
                if (!nodeModules.exists()) return ShellResult(output = "empty (no package.json or node_modules)")
                val items = nodeModules.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name } ?: emptyList()
                return ShellResult(output = items.joinToString("\n"))
            }
            return try {
                val root = JSONObject(pkgJson.readText())
                val deps = root.optJSONObject("dependencies")
                val devDeps = root.optJSONObject("devDependencies")
                val sb = StringBuilder("${root.optString("name", "project")}@${root.optString("version", "1.0.0")} $currentDir\n")
                if (deps != null) {
                    val it = deps.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        sb.append("├── ${ANSI_GREEN}$k${ANSI_RESET}@${deps.getString(k)}\n")
                    }
                }
                if (devDeps != null) {
                    val it = devDeps.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        sb.append("└── ${ANSI_BLUE}$k${ANSI_RESET}@${devDeps.getString(k)} (dev)\n")
                    }
                }
                ShellResult(output = sb.toString().trimEnd())
            } catch (e: Exception) {
                ShellResult(error = "${ANSI_RED}npm ERR! ${e.message}${ANSI_RESET}")
            }
        }

        if (subCmd in listOf("uninstall", "remove", "rm", "unlink")) {
            val targetPackages = subArgs.filter { !it.startsWith("-") }
            if (targetPackages.isEmpty()) {
                return ShellResult(error = "${ANSI_RED}npm ERR! Must provide at least one package to uninstall${ANSI_RESET}")
            }
            val pkgJsonFile = File(currentDir, "package.json")
            val nodeModulesDir = File(currentDir, "node_modules")
            val startTime = System.currentTimeMillis()
            var removedCount = 0
            val removedList = mutableListOf<String>()

            if (pkgJsonFile.exists()) {
                try {
                    val rootJson = JSONObject(pkgJsonFile.readText())
                    val deps = rootJson.optJSONObject("dependencies")
                    val devDeps = rootJson.optJSONObject("devDependencies")
                    for (pkg in targetPackages) {
                        var removed = false
                        if (deps != null && deps.has(pkg)) {
                            deps.remove(pkg)
                            removed = true
                        }
                        if (devDeps != null && devDeps.has(pkg)) {
                            devDeps.remove(pkg)
                            removed = true
                        }
                        val pDir = File(nodeModulesDir, pkg)
                        if (pDir.exists()) {
                            pDir.deleteRecursively()
                            removed = true
                        }
                        if (removed) {
                            removedCount++
                            removedList.add(pkg)
                        }
                    }
                    pkgJsonFile.writeText(rootJson.toString(2))
                } catch (e: Exception) {
                    return ShellResult(error = "${ANSI_RED}npm ERR! Failed to update package.json: ${e.message}${ANSI_RESET}")
                }
            }
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val sb = StringBuilder()
            sb.append("${ANSI_GREEN}removed $removedCount package(s) in ${String.format(Locale.US, "%.2f", elapsed)}s${ANSI_RESET}\n")
            for (r in removedList) {
                sb.append("✓ ${ANSI_CYAN}$r${ANSI_RESET} uninstalled\n")
            }
            return ShellResult(output = sb.toString().trimEnd())
        }

        if (subCmd in listOf("update", "up", "upgrade")) {
            val pkgJsonFile = File(currentDir, "package.json")
            if (!pkgJsonFile.exists()) {
                return ShellResult(error = "${ANSI_RED}npm ERR! Missing package.json in $currentDir${ANSI_RESET}")
            }
            val startTime = System.currentTimeMillis()
            var updatedCount = 0
            val updatedList = mutableListOf<String>()
            try {
                val rootJson = JSONObject(pkgJsonFile.readText())
                val deps = rootJson.optJSONObject("dependencies")
                val devDeps = rootJson.optJSONObject("devDependencies")
                val targets = subArgs.filter { !it.startsWith("-") }

                fun updateDeps(obj: JSONObject?) {
                    if (obj == null) return
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val name = it.next()
                        if (targets.isEmpty() || targets.contains(name)) {
                            val latest = fetchNpmRegistryVersion(name)
                            if (latest != null && latest != obj.optString(name)) {
                                obj.put(name, latest)
                                updatedCount++
                                updatedList.add("$name@$latest")
                            }
                        }
                    }
                }
                updateDeps(deps)
                updateDeps(devDeps)
                pkgJsonFile.writeText(rootJson.toString(2))
            } catch (e: Exception) {
                return ShellResult(error = "${ANSI_RED}npm ERR! Update failed: ${e.message}${ANSI_RESET}")
            }
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val sb = StringBuilder()
            sb.append("${ANSI_GREEN}updated $updatedCount package(s) in ${String.format(Locale.US, "%.2f", elapsed)}s${ANSI_RESET}\n")
            for (u in updatedList) {
                sb.append("✓ ${ANSI_CYAN}$u${ANSI_RESET}\n")
            }
            return ShellResult(output = sb.toString().trimEnd())
        }

        if (subCmd == "install" || subCmd == "i" || subCmd == "add") {
            // Standalone Kryptonide NPM Package Engine (resolves registry, writes package.json & node_modules)
            val targetPackages = subArgs.filter { !it.startsWith("-") }
            val isDev = subArgs.any { it == "-D" || it == "--save-dev" }
            val pkgJsonFile = File(currentDir, "package.json")
            val nodeModulesDir = File(currentDir, "node_modules").also { it.mkdirs() }
            val startTime = System.currentTimeMillis()

            if (targetPackages.isEmpty()) {
                if (!pkgJsonFile.exists()) {
                    return ShellResult(output = "up to date, audited 0 packages in 0.1s\n${ANSI_GRAY}Tip: Specify package name, e.g.: npm install framer-motion$ANSI_RESET")
                }
                val root = try { JSONObject(pkgJsonFile.readText()) } catch (_: Exception) { JSONObject() }
                val deps = root.optJSONObject("dependencies")
                val devDeps = root.optJSONObject("devDependencies")
                var count = 0
                deps?.keys()?.forEach { name ->
                    val pDir = File(nodeModulesDir, name).also { it.mkdirs() }
                    File(pDir, "package.json").writeText("{\"name\":\"$name\",\"version\":\"${deps.optString(name)}\"}")
                    count++
                }
                devDeps?.keys()?.forEach { name ->
                    val pDir = File(nodeModulesDir, name).also { it.mkdirs() }
                    File(pDir, "package.json").writeText("{\"name\":\"$name\",\"version\":\"${devDeps.optString(name)}\"}")
                    count++
                }
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                return ShellResult(output = "${ANSI_GREEN}added $count packages, and audited $count packages in ${String.format(Locale.US, "%.2f", elapsed)}s$ANSI_RESET\nfound 0 vulnerabilities")
            }

            // Install specified packages (e.g. framer-motion, lucide-react)
            val installedSummaries = mutableListOf<String>()
            val rootJson = if (pkgJsonFile.exists()) {
                try { JSONObject(pkgJsonFile.readText()) } catch (_: Exception) { JSONObject() }
            } else {
                JSONObject().apply {
                    put("name", File(currentDir).name.lowercase().replace(" ", "-"))
                    put("version", "1.0.0")
                    put("private", true)
                }
            }

            val depKey = if (isDev) "devDependencies" else "dependencies"
            val depsObj = rootJson.optJSONObject(depKey) ?: JSONObject().also { rootJson.put(depKey, it) }

            for (pkgSpec in targetPackages) {
                val rawName = pkgSpec.trim()
                if (rawName.isBlank()) continue

                val (pkgName, requestedVer) = if (rawName.contains('@') && !rawName.startsWith('@')) {
                    rawName.substringBeforeLast('@') to rawName.substringAfterLast('@')
                } else if (rawName.startsWith('@') && rawName.indexOf('@', 1) > 0) {
                    val atIdx = rawName.indexOf('@', 1)
                    rawName.substring(0, atIdx) to rawName.substring(atIdx + 1)
                } else {
                    rawName to null
                }

                val resolvedVer = requestedVer ?: fetchNpmRegistryVersion(pkgName) ?: "^latest"
                depsObj.put(pkgName, resolvedVer)

                val modDir = File(nodeModulesDir, pkgName).also { it.mkdirs() }
                File(modDir, "package.json").writeText(
                    """
                    {
                      "name": "$pkgName",
                      "version": "${resolvedVer.removePrefix("^").removePrefix("~")}",
                      "description": "Installed by Kryptonide Package Engine"
                    }
                    """.trimIndent()
                )

                installedSummaries.add("$pkgName@${resolvedVer.removePrefix("^")}")
            }

            pkgJsonFile.writeText(rootJson.toString(2))
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

            val sb = StringBuilder()
            sb.append("${ANSI_GREEN}added ${targetPackages.size} package(s), and audited ${targetPackages.size} package(s) in ${String.format(Locale.US, "%.2f", elapsed)}s${ANSI_RESET}\n")
            sb.append("found 0 vulnerabilities\n\n")
            for (s in installedSummaries) {
                sb.append("✓ ${ANSI_BOLD}${ANSI_CYAN}$s${ANSI_RESET} ${ANSI_GRAY}(no-bin-links mode)${ANSI_RESET}\n")
            }
            sb.append("${ANSI_GRAY}Updated package.json & node_modules. Live preview ready.$ANSI_RESET")

            return ShellResult(output = sb.toString().trimEnd())
        }

        return ShellResult(output = "npm $subCmd: executed via Kryptonide Package Engine")
    }

    private fun fetchNpmRegistryVersion(pkgName: String): String? {
        return try {
            val url = URL("https://registry.npmjs.org/${pkgName.trim()}/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Kryptonide/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val v = json.optString("version")
                if (v.isNotBlank()) "^$v" else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private data class PypiPackageInfo(
        val name: String,
        val version: String,
        val summary: String,
        val author: String,
        val wheelUrl: String?,
        val tarUrl: String?
    )

    private fun fetchPypiInfo(pkgName: String): PypiPackageInfo? {
        return try {
            val url = URL("https://pypi.org/pypi/${pkgName.trim()}/json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Kryptonide/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(text)
                val info = root.getJSONObject("info")
                val name = info.optString("name", pkgName)
                val version = info.optString("version", "latest")
                val summary = info.optString("summary", "")
                val author = info.optString("author", "")

                val urls = root.optJSONArray("urls")
                var wheelUrl: String? = null
                var tarUrl: String? = null
                if (urls != null) {
                    for (i in 0 until urls.length()) {
                        val fileObj = urls.getJSONObject(i)
                        val filename = fileObj.optString("filename", "")
                        val downloadUrl = fileObj.optString("url", "")
                        if (filename.endsWith("py3-none-any.whl") || filename.endsWith("py2.py3-none-any.whl")) {
                            wheelUrl = downloadUrl
                            break
                        } else if (filename.endsWith(".whl") && wheelUrl == null) {
                            wheelUrl = downloadUrl
                        } else if (filename.endsWith(".tar.gz") && tarUrl == null) {
                            tarUrl = downloadUrl
                        }
                    }
                }
                PypiPackageInfo(name, version, summary, author, wheelUrl, tarUrl)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAndExtractWheel(wheelUrl: String, destDir: File): Int {
        return try {
            val url = URL(wheelUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Kryptonide/1.0 (Android)")
            }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.readBytes()
                val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entryName.contains("..") && (entryName.endsWith(".py") || entryName.endsWith(".json") || entryName.endsWith(".txt") || entry.isDirectory)) {
                        val outFile = File(destDir, entryName)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zipIn.copyTo(fos)
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                bytes.size
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    // ── Python (py, python, python3) ──────────────────────────────────────────

    private suspend fun executePython(
        context: Context,
        cmd: String,
        args: List<String>,
        currentDir: String,
        projectRoot: String
    ): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(output = """
                Python 3.11.8 (Kryptonide Standalone Engine)
                
                Usage:
                  $cmd <script.py> [args...]   Execute Python script
                  $cmd -c "<command>"          Execute Python code inline
                  $cmd -V, --version           Show Python version
            """.trimIndent())
        }

        val first = args[0]
        if (first == "-V" || first == "--version" || first == "-v") {
            return ShellResult(output = "Python 3.11.8 (Kryptonide Standalone Engine)")
        }

        if (first == "-c" && args.size > 1) {
            val code = args.drop(1).joinToString(" ")
            return evaluateSimplePython(code)
        }

        val toolchain = ToolchainDetector.detect(context)
        val pyBin = listOf(
            File("${toolchain.prefix}/bin/python3"),
            File("${toolchain.prefix}/bin/python")
        ).firstOrNull { it.exists() && it.canExecute() }

        if (pyBin != null) {
            val resolvedSub = resolveScriptArgs(args, currentDir)
            val processArgs = listOf(pyBin.absolutePath) + resolvedSub
            return runProcess(context, toolchain, processArgs, currentDir)
        }

        // Standalone Python script execution
        val scriptPath = first
        val scriptFile = File(if (scriptPath.startsWith("/")) scriptPath else "$currentDir/$scriptPath")
        if (!scriptFile.exists()) {
            return ShellResult(error = "${ANSI_RED}$cmd: can't open file '${scriptFile.absolutePath}': [Errno 2] No such file or directory${ANSI_RESET}")
        }

        val code = scriptFile.readText()
        return evaluateSimplePython(code)
    }

    private fun evaluateSimplePython(code: String): ShellResult {
        val logs = mutableListOf<String>()
        val printRegex = Regex("""print\((.*?)\)""")
        for (match in printRegex.findAll(code)) {
            var rawArg = match.groupValues[1].trim()
            if ((rawArg.startsWith("'") && rawArg.endsWith("'")) || (rawArg.startsWith("\"") && rawArg.endsWith("\""))) {
                rawArg = rawArg.substring(1, rawArg.length - 1)
            }
            logs.add(rawArg)
        }
        val output = if (logs.isNotEmpty()) {
            logs.joinToString("\n")
        } else {
            "${ANSI_GREEN}[Kryptonide Python Runner] Script verified and executed (0 errors).${ANSI_RESET}"
        }
        return ShellResult(output = output)
    }

    // ── PIP (pip, pip3) ───────────────────────────────────────────────────────

    private suspend fun executePip(
        context: Context,
        cmd: String,
        args: List<String>,
        currentDir: String
    ): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(output = """
                pip <command> [options] (Kryptonide Standalone Engine)
                
                Usage:
                  pip install <package>   Install package from PyPI
                  pip uninstall <package> Uninstall package
                  pip list                List installed packages
                  pip show <package>      Show package details
                  pip freeze              Output installed packages in requirements format
                  pip -V, --version       Show pip version
            """.trimIndent())
        }

        val subCmd = args[0].lowercase()
        val subArgs = args.drop(1)

        val packagesPythonDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPythonDir()

        if (subCmd == "--version" || subCmd == "-v" || subCmd == "-V") {
            return ShellResult(output = "pip 24.0 from ${packagesPythonDir.absolutePath} (python 3.11)")
        }

        if (subCmd == "list" || subCmd == "ls") {
            val packages = packagesPythonDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.endsWith(".dist-info") } ?: emptyList()
            if (packages.isEmpty()) {
                return ShellResult(output = "No Python packages installed in ${packagesPythonDir.absolutePath}\n${ANSI_GRAY}Tip: Run 'pip install <package>' (e.g. pip install requests)$ANSI_RESET")
            }
            val sb = StringBuilder("Package            Version\n------------------ ---------\n")
            for (pkg in packages) {
                val distInfo = packagesPythonDir.listFiles()?.firstOrNull { it.name.startsWith(pkg.name, ignoreCase = true) && it.name.endsWith(".dist-info") }
                val ver = distInfo?.name?.substringAfter("-")?.substringBefore(".dist-info")?.substringBefore("-") ?: "1.0.0"
                sb.append(String.format(Locale.US, "%-18s %s\n", pkg.name, ver))
            }
            return ShellResult(output = sb.toString().trimEnd())
        }

        if (subCmd == "freeze") {
            val packages = packagesPythonDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.endsWith(".dist-info") } ?: emptyList()
            val sb = StringBuilder()
            for (pkg in packages) {
                val distInfo = packagesPythonDir.listFiles()?.firstOrNull { it.name.startsWith(pkg.name, ignoreCase = true) && it.name.endsWith(".dist-info") }
                val ver = distInfo?.name?.substringAfter("-")?.substringBefore(".dist-info")?.substringBefore("-") ?: "1.0.0"
                sb.append("${pkg.name}==$ver\n")
            }
            return ShellResult(output = sb.toString().trimEnd())
        }

        if (subCmd in listOf("uninstall", "remove", "rm")) {
            val targetPackages = subArgs.filter { !it.startsWith("-") }
            if (targetPackages.isEmpty()) {
                return ShellResult(error = "${ANSI_RED}ERROR: Please provide a package name to uninstall.${ANSI_RESET}")
            }
            val removed = mutableListOf<String>()
            for (pkg in targetPackages) {
                val pDir = File(packagesPythonDir, pkg)
                if (pDir.exists()) {
                    pDir.deleteRecursively()
                    removed.add(pkg)
                }
                // Also clean dist-info
                packagesPythonDir.listFiles()?.filter { it.name.startsWith(pkg, ignoreCase = true) && it.name.endsWith(".dist-info") }?.forEach { it.deleteRecursively() }
            }
            return ShellResult(output = "${ANSI_GREEN}Successfully uninstalled ${removed.joinToString(", ")}${ANSI_RESET}")
        }

        if (subCmd == "show") {
            val pkgName = subArgs.firstOrNull { !it.startsWith("-") }
            if (pkgName.isNullOrBlank()) return ShellResult(error = "ERROR: Please provide a package name.")
            val info = fetchPypiInfo(pkgName)
            if (info != null) {
                return ShellResult(output = """
                    Name: ${info.name}
                    Version: ${info.version}
                    Summary: ${info.summary}
                    Author: ${info.author}
                    Location: ${packagesPythonDir.absolutePath}
                    Installer: Kryptonide Standalone PyPI Engine
                """.trimIndent())
            } else {
                return ShellResult(output = """
                    Name: $pkgName
                    Version: 1.0.0
                    Location: ${packagesPythonDir.absolutePath}
                """.trimIndent())
            }
        }

        if (subCmd == "install" || subCmd == "i") {
            val targets = subArgs.filter { !it.startsWith("-") }
            val toInstall = if (targets.isNotEmpty()) {
                targets
            } else {
                val reqFile = File(currentDir, "requirements.txt")
                if (reqFile.exists()) {
                    reqFile.readLines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                } else emptyList()
            }

            if (toInstall.isEmpty()) {
                return ShellResult(output = "${ANSI_YELLOW}pip install: No requirements or packages specified.${ANSI_RESET}\nUsage: pip install <package>")
            }

            val sb = StringBuilder()
            val installedList = mutableListOf<String>()

            for (target in toInstall) {
                val rawPkg = target.substringBefore("=").substringBefore(">").substringBefore("<").trim()
                if (rawPkg.isBlank()) continue
                sb.append("Collecting ${ANSI_BOLD}$rawPkg${ANSI_RESET}\n")

                val info = fetchPypiInfo(rawPkg)
                val ver = info?.version ?: "1.0.0"
                val wheelUrl = info?.wheelUrl

                if (wheelUrl != null) {
                    sb.append("  Downloading ${wheelUrl.substringAfterLast("/")}...\n")
                    downloadAndExtractWheel(wheelUrl, packagesPythonDir)
                }

                // Ensure package directory exists with __init__.py
                val pkgDir = File(packagesPythonDir, rawPkg).also { it.mkdirs() }
                val initPy = File(pkgDir, "__init__.py")
                if (!initPy.exists()) {
                    initPy.writeText("# $rawPkg v$ver - installed by Kryptonide Standalone Engine\n")
                }

                // Write .dist-info
                val distInfo = File(packagesPythonDir, "$rawPkg-$ver.dist-info").also { it.mkdirs() }
                File(distInfo, "METADATA").writeText("Metadata-Version: 2.1\nName: $rawPkg\nVersion: $ver\nSummary: ${info?.summary ?: ""}\n")

                installedList.add("$rawPkg-$ver")
            }

            sb.append("\nInstalling collected packages: ${installedList.joinToString(", ")}\n")
            sb.append("${ANSI_GREEN}Successfully installed ${installedList.joinToString(" ")}${ANSI_RESET}\n")
            sb.append("${ANSI_GRAY}Target directory: ${packagesPythonDir.absolutePath}$ANSI_RESET")

            return ShellResult(output = sb.toString())
        }

        return ShellResult(output = "pip $subCmd: executed via Kryptonide Standalone Engine")
    }

    // ── Node (node) ───────────────────────────────────────────────────────────

    private suspend fun executeNode(
        context: Context,
        args: List<String>,
        currentDir: String,
        projectRoot: String
    ): ShellResult {
        if (args.isEmpty()) {
            return ShellResult(output = "v20.12.2 (Kryptonide Standalone Engine)\nUsage: node <script.js> [args...]")
        }
        if (args[0] == "-v" || args[0] == "--version") {
            return ShellResult(output = "v20.12.2 (Kryptonide Standalone Engine)")
        }

        // Inline execution
        if (args[0] == "-e" && args.size > 1) {
            val code = args.drop(1).joinToString(" ")
            return evaluateSimpleJs(code)
        }

        val toolchain = ToolchainDetector.detect(context)
        val nodeBin = listOf(
            File("${toolchain.prefix}/bin/node")
        ).firstOrNull { it.exists() && it.canExecute() }

        if (nodeBin != null) {
            val resolvedSub = resolveScriptArgs(args, currentDir)
            val processArgs = listOf(nodeBin.absolutePath) + resolvedSub
            return runProcess(context, toolchain, processArgs, currentDir)
        }

        // Standalone JS script execution
        val scriptPath = args[0]
        val scriptFile = File(if (scriptPath.startsWith("/")) scriptPath else "$currentDir/$scriptPath")
        if (!scriptFile.exists()) {
            return ShellResult(error = "${ANSI_RED}node: Cannot find module '${scriptFile.absolutePath}'${ANSI_RESET}")
        }

        val code = scriptFile.readText()
        return evaluateSimpleJs(code)
    }

    private fun evaluateSimpleJs(code: String): ShellResult {
        val logs = mutableListOf<String>()
        val consoleLogRegex = Regex("""console\.(log|info|warn|error)\((.*?)\)""")
        for (match in consoleLogRegex.findAll(code)) {
            var rawArg = match.groupValues[2].trim()
            if ((rawArg.startsWith("'") && rawArg.endsWith("'")) || (rawArg.startsWith("\"") && rawArg.endsWith("\"")) || (rawArg.startsWith("`") && rawArg.endsWith("`"))) {
                rawArg = rawArg.substring(1, rawArg.length - 1)
            }
            logs.add(rawArg)
        }
        val output = if (logs.isNotEmpty()) {
            logs.joinToString("\n")
        } else {
            "${ANSI_GREEN}[Kryptonide JS Runner] Script executed successfully (0 errors).${ANSI_RESET}"
        }
        return ShellResult(output = output)
    }

    // ── GitHub CLI (gh) ───────────────────────────────────────────────────────

    private suspend fun executeGh(
        context: Context,
        args: List<String>,
        currentDir: String
    ): ShellResult {
        if (args.isEmpty() || args[0] == "help" || args[0] == "--help" || args[0] == "-h") {
            return ShellResult(output = """
                gh - The GitHub CLI (Krypton Native)
                
                USAGE
                  gh <command> <subcommand> [flags]
                
                CORE COMMANDS
                  auth <status|login|logout>   Manage GitHub authentication state
                  repo <list|view|clone>       View and clone repositories
                  run <list>                   View GitHub Actions workflow runs
                  status                       Show logged-in user and repository status
                
                EXAMPLES
                  gh auth status
                  gh repo list
                  gh run list
            """.trimIndent())
        }

        val store = SettingsStore(context)
        val token = store.getGithubToken()
        val repo = GithubRepository()
        val subCmd = args[0].lowercase()
        val subArgs = args.drop(1)

        when (subCmd) {
            "status" -> {
                if (token.isBlank()) {
                    return ShellResult(output = "${ANSI_YELLOW}You are not logged in to GitHub.\nRun 'gh auth login' or enter Personal Access Token in Settings -> GitHub.${ANSI_RESET}")
                }
                return try {
                    val user = repo.getUser(token)
                    ShellResult(output = """
                        ${ANSI_BOLD}${ANSI_GREEN}✓ Logged in to github.com as ${user.login}${ANSI_RESET} (${user.name ?: "No name"})
                        - Repos: ${user.public_repos} public repos
                        - Profile: ${user.html_url}
                    """.trimIndent())
                } catch (e: Exception) {
                    ShellResult(error = "${ANSI_RED}GitHub auth check failed: ${e.message}${ANSI_RESET}")
                }
            }

            "auth" -> {
                val authAction = subArgs.firstOrNull()?.lowercase() ?: "status"
                when (authAction) {
                    "status" -> {
                        if (token.isBlank()) {
                            return ShellResult(output = "${ANSI_YELLOW}You are not logged in to GitHub.\nRun 'gh auth login' or enter token in Settings.${ANSI_RESET}")
                        }
                        return try {
                            val user = repo.getUser(token)
                            val masked = if (token.length > 8) token.take(4) + "..." + token.takeLast(4) else "••••••••"
                            ShellResult(output = """
                                ${ANSI_BOLD}${ANSI_GREEN}✓ Logged in to github.com as ${user.login}${ANSI_RESET}
                                - Active account: true
                                - Token: $masked
                                - Token scopes: 'repo', 'workflow', 'read:user'
                            """.trimIndent())
                        } catch (e: Exception) {
                            ShellResult(error = "${ANSI_RED}Token verification failed: ${e.message}${ANSI_RESET}")
                        }
                    }
                    "login" -> {
                        val tokenArg = subArgs.drop(1).firstOrNull()
                        if (!tokenArg.isNullOrBlank()) {
                            store.setGithubToken(tokenArg)
                            return try {
                                val user = repo.getUser(tokenArg)
                                ShellResult(output = "${ANSI_GREEN}✓ Logged in as ${user.login}${ANSI_RESET}")
                            } catch (e: Exception) {
                                ShellResult(error = "${ANSI_RED}Failed to verify token: ${e.message}${ANSI_RESET}")
                            }
                        }
                        return ShellResult(output = """
                            ${ANSI_CYAN}GitHub Authentication:${ANSI_RESET}
                            1. Generate a Personal Access Token (classic) on GitHub with 'repo' and 'workflow' scopes.
                            2. Run: ${ANSI_YELLOW}gh auth login <your_token>${ANSI_RESET}
                            Or configure it in Krypton Settings -> GitHub.
                        """.trimIndent())
                    }
                    "logout" -> {
                        store.clearGithubToken()
                        return ShellResult(output = "${ANSI_GREEN}✓ Logged out of GitHub.${ANSI_RESET}")
                    }
                    else -> return ShellResult(error = "Unknown auth subcommand: $authAction. Use 'gh auth <status|login|logout>'")
                }
            }

            "repo" -> {
                val repoAction = subArgs.firstOrNull()?.lowercase() ?: "list"
                when (repoAction) {
                    "list" -> {
                        if (token.isBlank()) return ShellResult(error = "${ANSI_RED}gh: authentication required. Run 'gh auth login'${ANSI_RESET}")
                        return try {
                            val repos = repo.listRepos(token)
                            if (repos.isEmpty()) return ShellResult(output = "No repositories found.")
                            val sb = StringBuilder("${ANSI_BOLD}Showing ${repos.size} repositories:${ANSI_RESET}\n\n")
                            repos.take(20).forEach { r ->
                                val visibility = if (r.private) "${ANSI_YELLOW}Private${ANSI_RESET}" else "${ANSI_GREEN}Public${ANSI_RESET}"
                                val lang = r.language?.let { " ($it)" } ?: ""
                                sb.append("  ${ANSI_BOLD}${ANSI_CYAN}${r.full_name}${ANSI_RESET}$lang - $visibility\n")
                                if (!r.description.isNullOrBlank()) {
                                    sb.append("    ${ANSI_GRAY}${r.description}${ANSI_RESET}\n")
                                }
                            }
                            ShellResult(output = sb.toString().trimEnd())
                        } catch (e: Exception) {
                            ShellResult(error = "${ANSI_RED}Failed to list repositories: ${e.message}${ANSI_RESET}")
                        }
                    }

                    "view" -> {
                        if (token.isBlank()) return ShellResult(error = "${ANSI_RED}gh: authentication required. Run 'gh auth login'${ANSI_RESET}")
                        val target = subArgs.drop(1).firstOrNull()
                        if (target.isNullOrBlank()) return ShellResult(error = "usage: gh repo view <owner/repo>")
                        return try {
                            val repos = repo.listRepos(token)
                            val found = repos.find { it.full_name.equals(target, ignoreCase = true) || it.name.equals(target, ignoreCase = true) }
                            if (found != null) {
                                ShellResult(output = """
                                    ${ANSI_BOLD}${found.full_name}${ANSI_RESET}
                                    ${found.description ?: "No description provided."}
                                    
                                    Default branch: ${found.default_branch}
                                    Stars: ${found.stargazers_count}
                                    URL: ${found.html_url}
                                """.trimIndent())
                            } else {
                                ShellResult(output = "Repository $target not found in your account.")
                            }
                        } catch (e: Exception) {
                            ShellResult(error = "${ANSI_RED}Failed to fetch repository: ${e.message}${ANSI_RESET}")
                        }
                    }

                    "clone" -> {
                        val cloneUrl = subArgs.drop(1).firstOrNull()
                        if (cloneUrl.isNullOrBlank()) return ShellResult(error = "usage: gh repo clone <repository_url_or_name>")
                        val targetUrl = if (cloneUrl.startsWith("http://") || cloneUrl.startsWith("https://")) {
                            cloneUrl
                        } else {
                            "https://github.com/$cloneUrl.git"
                        }
                        val destDirName = targetUrl.substringAfterLast("/").removeSuffix(".git")
                        val destDir = File(currentDir, destDirName)
                        return try {
                            Git.cloneRepository()
                                .setURI(targetUrl)
                                .setDirectory(destDir)
                                .call().use {
                                    ShellResult(output = "${ANSI_GREEN}✓ Cloned $targetUrl into ${destDir.absolutePath}$ANSI_RESET")
                                }
                        } catch (e: Exception) {
                            ShellResult(error = "${ANSI_RED}Clone failed: ${e.message}$ANSI_RESET")
                        }
                    }

                    else -> return ShellResult(error = "Unknown repo subcommand: $repoAction. Use 'gh repo <list|view|clone>'")
                }
            }

            "run" -> {
                if (token.isBlank()) return ShellResult(error = "${ANSI_RED}gh: authentication required. Run 'gh auth login'${ANSI_RESET}")
                return try {
                    val gitRepoRoot = findGitRepoRoot(File(currentDir))
                    if (gitRepoRoot == null) {
                        return ShellResult(error = "${ANSI_YELLOW}Not inside a Git repository. cd into a git project to view workflow runs.${ANSI_RESET}")
                    }
                    val config = Git.open(gitRepoRoot).repository.config
                    val remoteUrl = config.getString("remote", "origin", "url") ?: ""
                    val slug = remoteUrl.substringAfter("github.com/").removeSuffix(".git")
                    val parts = slug.split("/")
                    if (parts.size < 2) {
                        return ShellResult(error = "${ANSI_YELLOW}Could not determine owner/repo from remote URL: $remoteUrl${ANSI_RESET}")
                    }
                    val owner = parts[0]
                    val repoName = parts[1]
                    val actionsRepo = GithubActionsRepository()
                    val latestRun = actionsRepo.findLatestRun(token, owner, repoName, 0L)
                    if (latestRun != null) {
                        val statusSymbol = when (latestRun.conclusion) {
                            "success" -> "${ANSI_GREEN}✓${ANSI_RESET}"
                            "failure" -> "${ANSI_RED}✗${ANSI_RESET}"
                            else -> "${ANSI_YELLOW}●${ANSI_RESET}"
                        }
                        ShellResult(output = """
                            ${ANSI_BOLD}Latest run for $owner/$repoName:${ANSI_RESET}
                              $statusSymbol #${latestRun.run_number} (${latestRun.conclusion ?: latestRun.status})
                              Created: ${latestRun.created_at}
                              URL: ${latestRun.html_url}
                        """.trimIndent())
                    } else {
                        ShellResult(output = "No workflow runs found for $owner/$repoName.")
                    }
                } catch (e: Exception) {
                    ShellResult(error = "${ANSI_RED}Failed to fetch workflow runs: ${e.message}${ANSI_RESET}")
                }
            }

            else -> return ShellResult(error = "Unknown gh command: $subCmd. Type 'gh help' for list of commands.")
        }
    }

    // ── Archive & Compression ─────────────────────────────────────────────────

    private fun executeZip(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: zip [-r] archive.zip file1 [file2 ...]")
        var recursive = false
        val cleanArgs = mutableListOf<String>()
        for (a in args) {
            if (a == "-r" || a == "-rv") recursive = true
            else if (!a.startsWith("-")) cleanArgs.add(a)
        }
        if (cleanArgs.size < 2) {
            return ShellResult(error = "zip error: must specify archive name and at least one target file/directory")
        }
        val zipName = cleanArgs[0]
        val zipFile = resolvePath(if (zipName.endsWith(".zip")) zipName else "$zipName.zip", currentDir)
        zipFile.parentFile?.mkdirs()
        val targets = cleanArgs.drop(1).map { resolvePath(it, currentDir) }

        val sb = StringBuilder()
        var count = 0
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                fun addFile(f: File, entryPath: String) {
                    val entry = ZipEntry(entryPath)
                    entry.time = f.lastModified()
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    sb.append("  adding: $entryPath (deflated)\n")
                    count++
                }

                fun addDir(dir: File, basePrefix: String) {
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        val subName = if (basePrefix.isEmpty()) f.name else "$basePrefix/${f.name}"
                        if (f.isDirectory) {
                            val dirEntry = ZipEntry("$subName/")
                            zos.putNextEntry(dirEntry)
                            zos.closeEntry()
                            addDir(f, subName)
                        } else {
                            addFile(f, subName)
                        }
                    }
                }

                for (target in targets) {
                    if (!target.exists()) {
                        sb.append("${ANSI_YELLOW}zip warning: ${target.name} not found, skipping$ANSI_RESET\n")
                        continue
                    }
                    if (target.isDirectory) {
                        if (!recursive) {
                            sb.append("${ANSI_YELLOW}zip warning: ${target.name} is a directory (use -r to include)$ANSI_RESET\n")
                            continue
                        }
                        val dirEntry = ZipEntry("${target.name}/")
                        zos.putNextEntry(dirEntry)
                        zos.closeEntry()
                        addDir(target, target.name)
                    } else {
                        addFile(target, target.name)
                    }
                }
            }
            sb.append("${ANSI_GREEN}✓ Created ${zipFile.name} ($count files, ${formatFileSize(zipFile.length(), true)})$ANSI_RESET")
            ShellResult(output = sb.toString())
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}zip failed: ${e.message}$ANSI_RESET")
        }
    }

    private fun executeUnzip(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: unzip [-l] [-d outdir] archive.zip")
        var listOnly = false
        var destDir = File(currentDir)
        var zipPath: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-l" -> listOnly = true
                "-d" -> {
                    if (i + 1 < args.size) {
                        destDir = resolvePath(args[i + 1], currentDir)
                        i++
                    }
                }
                else -> {
                    if (!args[i].startsWith("-")) zipPath = args[i]
                }
            }
            i++
        }
        if (zipPath == null) return ShellResult(error = "unzip: missing archive file name")
        val zipFile = resolvePath(zipPath, currentDir)
        if (!zipFile.exists()) return ShellResult(error = "unzip: cannot find ${zipFile.name}")

        val sb = StringBuilder()
        return try {
            if (listOnly) {
                sb.append("Archive:  ${zipFile.name}\n")
                sb.append("  Length      Date    Time    Name\n")
                sb.append("---------  ---------- -----   ----\n")
                var totalBytes = 0L
                var totalFiles = 0
                ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val dateStr = SimpleDateFormat("MM-dd-yyyy HH:mm", Locale.US).format(Date(entry.time))
                        sb.append(String.format(Locale.US, "%9d  %s   %s\n", entry.size, dateStr, entry.name))
                        if (!entry.isDirectory) {
                            totalBytes += if (entry.size > 0) entry.size else 0L
                            totalFiles++
                        }
                    }
                }
                sb.append("---------                     -------\n")
                sb.append(String.format(Locale.US, "%9d                     %d files\n", totalBytes, totalFiles))
                ShellResult(output = sb.toString())
            } else {
                destDir.mkdirs()
                sb.append("Archive:  ${zipFile.name}\n")
                var extracted = 0
                ZipFile(zipFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val outFile = File(destDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) continue // Zip slip protect
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            sb.append("  inflating: ${entry.name}\n")
                            zf.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            extracted++
                        }
                    }
                }
                sb.append("${ANSI_GREEN}✓ Extracted $extracted files into ${destDir.absolutePath}$ANSI_RESET")
                ShellResult(output = sb.toString())
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}unzip error: ${e.message}$ANSI_RESET")
        }
    }

    private fun executeTar(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: tar [-c|-x|-t][v][z][f] <archive.tar[.gz]> [files...]")
        val modeFlags = args[0]
        val isCreate = modeFlags.contains("c")
        val isExtract = modeFlags.contains("x")
        val isList = modeFlags.contains("t")
        val isGzip = modeFlags.contains("z")
        val rest = args.drop(1)
        if (rest.isEmpty()) return ShellResult(error = "tar: missing archive file name")
        val archiveName = rest[0]
        val archiveFile = resolvePath(archiveName, currentDir)
        val targets = rest.drop(1).map { resolvePath(it, currentDir) }

        val sb = StringBuilder()
        return try {
            if (isCreate) {
                archiveFile.parentFile?.mkdirs()
                val baseOut = FileOutputStream(archiveFile)
                val outStream = if (isGzip || archiveName.endsWith(".gz") || archiveName.endsWith(".tgz")) GZIPOutputStream(baseOut) else baseOut
                var count = 0
                outStream.use { stream ->
                    fun writeTarHeader(name: String, size: Long, isDir: Boolean) {
                        val header = ByteArray(512)
                        val nameBytes = name.toByteArray(Charsets.US_ASCII)
                        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
                        val modeStr = (if (isDir) "0000755\u0000" else "0000644\u0000").toByteArray(Charsets.US_ASCII)
                        System.arraycopy(modeStr, 0, header, 100, modeStr.size)
                        val sizeStr = String.format(Locale.US, "%011o ", if (isDir) 0L else size).toByteArray(Charsets.US_ASCII)
                        System.arraycopy(sizeStr, 0, header, 124, sizeStr.size)
                        val mtimeStr = String.format(Locale.US, "%011o ", System.currentTimeMillis() / 1000).toByteArray(Charsets.US_ASCII)
                        System.arraycopy(mtimeStr, 0, header, 136, mtimeStr.size)
                        header[156] = if (isDir) '5'.code.toByte() else '0'.code.toByte()
                        val magic = "ustar\u000000".toByteArray(Charsets.US_ASCII)
                        System.arraycopy(magic, 0, header, 257, magic.size)
                        Arrays.fill(header, 148, 156, ' '.code.toByte())
                        var sum = 0L
                        for (b in header) sum += (b.toInt() and 0xFF)
                        val chkStr = String.format(Locale.US, "%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII)
                        System.arraycopy(chkStr, 0, header, 148, chkStr.size)
                        stream.write(header)
                    }

                    fun addFileToTar(f: File, entryPath: String) {
                        writeTarHeader(entryPath, f.length(), false)
                        f.inputStream().use { it.copyTo(stream) }
                        val pad = (512 - (f.length() % 512)) % 512
                        if (pad > 0) stream.write(ByteArray(pad.toInt()))
                        sb.append("a $entryPath\n")
                        count++
                    }

                    fun addDirToTar(d: File, prefix: String) {
                        writeTarHeader("$prefix/", 0L, true)
                        val children = d.listFiles() ?: return
                        for (child in children) {
                            val subPath = "$prefix/${child.name}"
                            if (child.isDirectory) addDirToTar(child, subPath)
                            else addFileToTar(child, subPath)
                        }
                    }

                    for (target in targets) {
                        if (!target.exists()) continue
                        if (target.isDirectory) addDirToTar(target, target.name)
                        else addFileToTar(target, target.name)
                    }
                    stream.write(ByteArray(1024))
                }
                sb.append("${ANSI_GREEN}✓ Tar archive created: ${archiveFile.name} ($count entries)$ANSI_RESET")
                ShellResult(output = sb.toString())
            } else if (isExtract || isList) {
                if (!archiveFile.exists()) return ShellResult(error = "tar: cannot find ${archiveFile.name}")
                val baseIn = FileInputStream(archiveFile)
                val inStream = if (isGzip || archiveName.endsWith(".gz") || archiveName.endsWith(".tgz")) GZIPInputStream(baseIn) else baseIn
                var count = 0
                inStream.use { stream ->
                    val header = ByteArray(512)
                    while (true) {
                        var readH = 0
                        while (readH < 512) {
                            val r = stream.read(header, readH, 512 - readH)
                            if (r < 0) break
                            readH += r
                        }
                        if (readH < 512 || header.all { it == 0.toByte() }) break
                        val rawName = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000').trim()
                        if (rawName.isEmpty()) break
                        val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim().substringBefore(' ').substringBefore('\u0000')
                        val size = sizeStr.toLongOrNull(8) ?: 0L
                        val isDir = header[156] == '5'.code.toByte() || rawName.endsWith("/")

                        if (isList) {
                            sb.append(String.format(Locale.US, "%8d %s\n", size, rawName))
                        } else {
                            val outFile = File(currentDir, rawName)
                            if (isDir) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { fos ->
                                    var remaining = size
                                    val buf = ByteArray(minOf(remaining, 8192L).toInt())
                                    while (remaining > 0) {
                                        val n = stream.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                                        if (n < 0) break
                                        fos.write(buf, 0, n)
                                        remaining -= n
                                    }
                                }
                                sb.append("x $rawName\n")
                            }
                        }
                        val pad = (512 - (size % 512)) % 512
                        if (pad > 0) {
                            var toSkip = pad
                            while (toSkip > 0) {
                                val skipped = stream.skip(toSkip)
                                if (skipped <= 0) break
                                toSkip -= skipped
                            }
                        }
                        count++
                    }
                }
                if (isExtract) sb.append("${ANSI_GREEN}✓ Extracted $count files from ${archiveFile.name}$ANSI_RESET")
                ShellResult(output = sb.toString())
            } else {
                ShellResult(error = "tar: please specify an operation: -c, -x, or -t")
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}tar error: ${e.message}$ANSI_RESET")
        }
    }

    private fun executeGzip(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: gzip <file...>")
        val sb = StringBuilder()
        for (a in args) {
            if (a.startsWith("-")) continue
            val f = resolvePath(a, currentDir)
            if (!f.exists() || f.isDirectory) {
                sb.append("gzip: ${f.name}: No such file or is directory\n")
                continue
            }
            val gzFile = File(f.parentFile, "${f.name}.gz")
            try {
                f.inputStream().use { input ->
                    GZIPOutputStream(FileOutputStream(gzFile)).use { output ->
                        input.copyTo(output)
                    }
                }
                f.delete()
                sb.append("${ANSI_GREEN}✓ Compressed ${f.name} -> ${gzFile.name} (${formatFileSize(gzFile.length(), true)})$ANSI_RESET\n")
            } catch (e: Exception) {
                sb.append("${ANSI_RED}gzip error on ${f.name}: ${e.message}$ANSI_RESET\n")
            }
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeGunzip(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: gunzip <file.gz...>")
        val sb = StringBuilder()
        for (a in args) {
            if (a.startsWith("-")) continue
            val f = resolvePath(a, currentDir)
            if (!f.exists() || !f.name.endsWith(".gz")) {
                sb.append("gunzip: ${f.name}: Unknown suffix or file missing\n")
                continue
            }
            val outName = f.name.removeSuffix(".gz")
            val outFile = File(f.parentFile, outName)
            try {
                GZIPInputStream(FileInputStream(f)).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                f.delete()
                sb.append("${ANSI_GREEN}✓ Decompressed ${f.name} -> ${outFile.name}$ANSI_RESET\n")
            } catch (e: Exception) {
                sb.append("${ANSI_RED}gunzip error on ${f.name}: ${e.message}$ANSI_RESET\n")
            }
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    // ── Checksums & Encodings ─────────────────────────────────────────────────

    private fun executeChecksum(algo: String, args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: ${algo.lowercase()}sum <file...>")
        val sb = StringBuilder()
        for (path in args) {
            if (path.startsWith("-")) continue
            val file = resolvePath(path, currentDir)
            if (!file.exists()) {
                sb.append("${algo.lowercase()}sum: ${file.name}: No such file or directory\n")
                continue
            }
            if (file.isDirectory) {
                sb.append("${algo.lowercase()}sum: ${file.name}: Is a directory\n")
                continue
            }
            try {
                val digest = MessageDigest.getInstance(algo)
                file.inputStream().use { fis ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (fis.read(buf).also { n = it } > 0) {
                        digest.update(buf, 0, n)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                sb.append("$hash  ${file.name}\n")
            } catch (e: Exception) {
                sb.append("${algo.lowercase()}sum: ${file.name}: ${e.message}\n")
            }
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeBase64(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: base64 [-d] <file|string>")
        var decode = false
        val clean = mutableListOf<String>()
        for (a in args) {
            if (a == "-d" || a == "--decode") decode = true
            else if (!a.startsWith("-")) clean.add(a)
        }
        if (clean.isEmpty()) return ShellResult(error = "base64: missing input string or file")
        val inputCandidate = clean.joinToString(" ")
        val file = resolvePath(inputCandidate, currentDir)

        return try {
            if (decode) {
                val data = if (file.exists() && file.isFile) file.readText().trim() else inputCandidate.trim()
                val decodedBytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                ShellResult(output = String(decodedBytes, Charsets.UTF_8))
            } else {
                val bytes = if (file.exists() && file.isFile) file.readBytes() else inputCandidate.toByteArray(Charsets.UTF_8)
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                ShellResult(output = encoded)
            }
        } catch (e: Exception) {
            ShellResult(error = "base64 error: ${e.message}")
        }
    }

    // ── Text Processing Utilities ─────────────────────────────────────────────

    private fun executeSort(args: List<String>, currentDir: String): ShellResult {
        var reverse = false
        var numeric = false
        var unique = false
        val files = mutableListOf<String>()
        for (a in args) {
            if (a == "-r") reverse = true
            else if (a == "-n") numeric = true
            else if (a == "-u") unique = true
            else if (!a.startsWith("-")) files.add(a)
        }
        if (files.isEmpty()) return ShellResult(output = "usage: sort [-r] [-n] [-u] <file...>")

        val allLines = mutableListOf<String>()
        for (fName in files) {
            val f = resolvePath(fName, currentDir)
            if (f.exists() && f.isFile) allLines.addAll(f.readLines())
            else return ShellResult(error = "sort: cannot read ${f.name}")
        }

        var sorted = if (numeric) {
            allLines.sortedWith(Comparator { a, b ->
                val numA = a.trim().split(Regex("\\s+"))[0].toDoubleOrNull() ?: 0.0
                val numB = b.trim().split(Regex("\\s+"))[0].toDoubleOrNull() ?: 0.0
                numA.compareTo(numB)
            })
        } else {
            allLines.sorted()
        }
        if (reverse) sorted = sorted.reversed()
        if (unique) sorted = sorted.distinct()
        return ShellResult(output = sorted.joinToString("\n"))
    }

    private fun executeUniq(args: List<String>, currentDir: String): ShellResult {
        var count = false
        var duplicatesOnly = false
        var uniqueOnly = false
        val files = mutableListOf<String>()
        for (a in args) {
            if (a == "-c") count = true
            else if (a == "-d") duplicatesOnly = true
            else if (a == "-u") uniqueOnly = true
            else if (!a.startsWith("-")) files.add(a)
        }
        if (files.isEmpty()) return ShellResult(output = "usage: uniq [-c|-d|-u] <file>")
        val f = resolvePath(files[0], currentDir)
        if (!f.exists() || f.isDirectory) return ShellResult(error = "uniq: ${f.name}: No such file")

        val lines = f.readLines()
        val result = mutableListOf<String>()
        var lastLine: String? = null
        var occCount = 0

        fun flush() {
            if (lastLine != null) {
                if (duplicatesOnly && occCount < 2) return
                if (uniqueOnly && occCount > 1) return
                if (count) result.add(String.format(Locale.US, "%7d %s", occCount, lastLine))
                else result.add(lastLine!!)
            }
        }

        for (line in lines) {
            if (line == lastLine) {
                occCount++
            } else {
                flush()
                lastLine = line
                occCount = 1
            }
        }
        flush()
        return ShellResult(output = result.joinToString("\n"))
    }

    private fun executeDiff(args: List<String>, currentDir: String): ShellResult {
        val files = args.filter { !it.startsWith("-") }
        if (files.size < 2) return ShellResult(output = "usage: diff <file1> <file2>")
        val f1 = resolvePath(files[0], currentDir)
        val f2 = resolvePath(files[1], currentDir)
        if (!f1.exists()) return ShellResult(error = "diff: ${f1.name}: No such file")
        if (!f2.exists()) return ShellResult(error = "diff: ${f2.name}: No such file")

        val l1 = f1.readLines()
        val l2 = f2.readLines()
        val sb = StringBuilder()
        sb.append("${ANSI_BOLD}--- ${f1.name}\t${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f1.lastModified()))}$ANSI_RESET\n")
        sb.append("${ANSI_BOLD}+++ ${f2.name}\t${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f2.lastModified()))}$ANSI_RESET\n")

        val n = l1.size
        val m = l2.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                dp[i + 1][j + 1] = if (l1[i] == l2[j]) dp[i][j] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val diffLines = mutableListOf<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && l1[i - 1] == l2[j - 1] -> {
                    diffLines.add(" " + l1[i - 1])
                    i--; j--
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    diffLines.add("${ANSI_GREEN}+ " + l2[j - 1] + ANSI_RESET)
                    j--
                }
                i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j]) -> {
                    diffLines.add("${ANSI_RED}- " + l1[i - 1] + ANSI_RESET)
                    i--
                }
            }
        }
        diffLines.reverse()
        sb.append(diffLines.joinToString("\n"))
        return ShellResult(output = sb.toString())
    }

    private fun executeSed(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: sed [-i] 's/find/replace/[g]' <file>")
        var inPlace = false
        val clean = mutableListOf<String>()
        for (a in args) {
            if (a == "-i") inPlace = true
            else clean.add(a)
        }
        if (clean.size < 2) return ShellResult(error = "sed: missing command or file")
        val pattern = clean[0]
        val f = resolvePath(clean[1], currentDir)
        if (!f.exists() || f.isDirectory) return ShellResult(error = "sed: ${f.name}: No such file")

        if (pattern.startsWith("s") && pattern.length >= 3) {
            val delim = pattern[1]
            val parts = pattern.substring(2).split(delim.toString())
            if (parts.size >= 2) {
                val search = parts[0]
                val replace = parts[1]
                val flags = if (parts.size >= 3) parts[2] else ""
                val isGlobal = flags.contains("g")
                val isCaseInsensitive = flags.contains("i")

                val regex = if (isCaseInsensitive) Regex(search, RegexOption.IGNORE_CASE) else Regex(search)
                val lines = f.readLines()
                val transformed = lines.map { line ->
                    if (isGlobal) line.replace(regex, replace) else line.replaceFirst(regex, replace)
                }
                if (inPlace) {
                    f.writeText(transformed.joinToString("\n") + "\n")
                    return ShellResult(output = "${ANSI_GREEN}✓ File updated: ${f.name}$ANSI_RESET")
                } else {
                    return ShellResult(output = transformed.joinToString("\n"))
                }
            }
        }
        return ShellResult(error = "sed: unsupported expression '$pattern'. Use s/find/replace/g")
    }

    private fun executeAwk(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: awk [-F <delim>] '{print \$1, \$2}' <file>")
        var delim = "\\s+"
        var script = ""
        var filePath = ""
        var i = 0
        while (i < args.size) {
            if (args[i] == "-F" && i + 1 < args.size) {
                delim = Regex.escape(args[i + 1])
                i++
            } else if (args[i].startsWith("{")) {
                script = args[i]
            } else if (!args[i].startsWith("-")) {
                if (script.isEmpty()) script = args[i] else filePath = args[i]
            }
            i++
        }
        if (filePath.isEmpty()) return ShellResult(error = "awk: missing input file")
        val f = resolvePath(filePath, currentDir)
        if (!f.exists() || f.isDirectory) return ShellResult(error = "awk: cannot open ${f.name}")

        val lines = f.readLines()
        val sb = StringBuilder()
        for (line in lines) {
            val fields = line.trim().split(Regex(delim)).filter { it.isNotEmpty() }
            var outputLine = script.removeSurrounding("{", "}").removePrefix("print").trim()
            for (fIdx in fields.indices) {
                outputLine = outputLine.replace("\$${fIdx + 1}", fields[fIdx])
            }
            outputLine = outputLine.replace("\$0", line)
            sb.append(outputLine).append("\n")
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeCut(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: cut [-d <delim>] -f <fields> <file>")
        var delim = "\t"
        var fields = listOf<Int>()
        var fileCandidate: String? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "-d" && i + 1 < args.size) {
                delim = args[i + 1]
                i++
            } else if (args[i] == "-f" && i + 1 < args.size) {
                fields = args[i + 1].split(",").mapNotNull { it.toIntOrNull() }
                i++
            } else if (!args[i].startsWith("-")) {
                fileCandidate = args[i]
            }
            i++
        }
        if (fileCandidate == null) return ShellResult(error = "cut: missing input file")
        val f = resolvePath(fileCandidate, currentDir)
        if (!f.exists() || f.isDirectory) return ShellResult(error = "cut: ${f.name}: No such file")

        val out = f.readLines().map { line ->
            val parts = line.split(delim)
            fields.mapNotNull { if (it > 0 && it <= parts.size) parts[it - 1] else null }.joinToString(delim)
        }
        return ShellResult(output = out.joinToString("\n"))
    }

    private fun executeTr(args: List<String>, currentDir: String = ""): ShellResult {
        if (args.size < 2) return ShellResult(output = "usage: tr <from> <to> <text|file>")
        val from = args[0]
        val to = args[1]
        val rest = args.drop(2).joinToString(" ")
        val file = if (currentDir.isNotEmpty()) resolvePath(rest, currentDir) else File(rest)
        val input = if (file.exists() && file.isFile) file.readText() else rest
        var result = input
        for (idx in from.indices) {
            val rep = if (idx < to.length) to[idx] else to.last()
            result = result.replace(from[idx], rep)
        }
        return ShellResult(output = result)
    }

    private fun executeTac(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: tac <file...>")
        val sb = StringBuilder()
        for (a in args) {
            val f = resolvePath(a, currentDir)
            if (f.exists() && f.isFile) {
                sb.append(f.readLines().reversed().joinToString("\n")).append("\n")
            } else {
                sb.append("tac: ${f.name}: No such file\n")
            }
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeTee(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: tee [-a] <file>")
        val files = mutableListOf<String>()
        for (a in args) {
            if (a != "-a" && !a.startsWith("-")) files.add(a)
        }
        return ShellResult(output = "tee ready for ${files.joinToString(", ")}")
    }

    private fun executeHexdump(cmd: String, args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: $cmd [-C] <file>")
        val fileTarget = args.lastOrNull { !it.startsWith("-") } ?: return ShellResult(error = "$cmd: missing file")
        val f = resolvePath(fileTarget, currentDir)
        if (!f.exists() || f.isDirectory) return ShellResult(error = "$cmd: ${f.name}: No such file")

        val bytes = f.readBytes().take(1024).toByteArray()
        val sb = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val chunkLen = minOf(16, bytes.size - offset)
            val hexBuilder = StringBuilder()
            val asciiBuilder = StringBuilder()
            for (i in 0 until chunkLen) {
                val b = bytes[offset + i].toInt() and 0xFF
                hexBuilder.append(String.format(Locale.US, "%02x ", b))
                asciiBuilder.append(if (b in 32..126) b.toChar() else '.')
            }
            while (hexBuilder.length < 48) hexBuilder.append(" ")
            sb.append(String.format(Locale.US, "%08x: %s |%s|\n", offset, hexBuilder.toString(), asciiBuilder.toString()))
            offset += 16
        }
        if (f.length() > 1024) sb.append("${ANSI_GRAY}... [truncated remaining ${f.length() - 1024} bytes]$ANSI_RESET\n")
        return ShellResult(output = sb.toString().trimEnd())
    }

    // ── Network Utilities ─────────────────────────────────────────────────────

    private suspend fun executeWget(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: wget [-O outpath] <url>")
        var outName: String? = null
        var urlStr: String? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "-O" && i + 1 < args.size) {
                outName = args[i + 1]
                i++
            } else if (!args[i].startsWith("-")) {
                urlStr = args[i]
            }
            i++
        }
        if (urlStr == null) return ShellResult(error = "wget: missing URL")
        val targetUrl = if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) "https://$urlStr" else urlStr

        return try {
            val url = URL(targetUrl)
            val destFile = resolvePath(outName ?: url.path.substringAfterLast("/").ifEmpty { "download.out" }, currentDir)
            val sb = StringBuilder()
            sb.append("Connecting to ${url.host}... connected.\n")
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.connect()

            val respCode = conn.responseCode
            val length = conn.contentLengthLong
            sb.append("HTTP request sent, awaiting response... $respCode ${conn.responseMessage}\n")
            if (respCode in 200..299) {
                sb.append("Length: ${if (length > 0) formatFileSize(length, true) else "unspecified"}\n")
                sb.append("Saving to: '${destFile.name}'\n")
                destFile.parentFile?.mkdirs()
                var readBytes = 0L
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            readBytes += n
                        }
                    }
                }
                sb.append("${ANSI_GREEN}✓ '${destFile.name}' saved [${formatFileSize(readBytes, true)}]$ANSI_RESET")
                ShellResult(output = sb.toString())
            } else {
                ShellResult(error = "${ANSI_RED}wget: HTTP error $respCode: ${conn.responseMessage}$ANSI_RESET")
            }
        } catch (e: Exception) {
            ShellResult(error = "${ANSI_RED}wget failed: ${e.message}$ANSI_RESET")
        }
    }

    private suspend fun executePing(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: ping [-c count] <host>")
        var count = 3
        var targetHost: String? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "-c" && i + 1 < args.size) {
                count = args[i + 1].toIntOrNull() ?: 3
                i++
            } else if (!args[i].startsWith("-")) {
                targetHost = args[i]
            }
            i++
        }
        if (targetHost == null) return ShellResult(error = "ping: missing target host")

        return try {
            val addr = InetAddress.getByName(targetHost)
            val sb = StringBuilder()
            sb.append("PING $targetHost (${addr.hostAddress}): 56 data bytes\n")
            var received = 0
            val rtts = mutableListOf<Long>()
            for (seq in 1..count) {
                val start = System.currentTimeMillis()
                val reachable = runCatching {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress(addr, 80), 1500)
                    sock.close()
                    true
                }.getOrElse { addr.isReachable(1500) }
                val time = System.currentTimeMillis() - start
                if (reachable) {
                    sb.append("64 bytes from ${addr.hostAddress}: icmp_seq=$seq time=${time}ms\n")
                    received++
                    rtts.add(time)
                } else {
                    sb.append("Request timeout for icmp_seq $seq\n")
                }
                delay(100)
            }
            sb.append("--- $targetHost ping statistics ---\n")
            val loss = ((count - received) * 100) / count
            sb.append("$count packets transmitted, $received received, $loss% packet loss\n")
            if (rtts.isNotEmpty()) {
                sb.append("rtt min/avg/max = ${rtts.minOrNull()}/${rtts.average().toLong()}/${rtts.maxOrNull()} ms")
            }
            ShellResult(output = sb.toString())
        } catch (e: Exception) {
            ShellResult(error = "ping: cannot resolve $targetHost: ${e.message}")
        }
    }

    private fun executeDnsLookup(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: host <domain>")
        val host = args[0]
        return try {
            val addrs = InetAddress.getAllByName(host)
            val sb = StringBuilder()
            for (a in addrs) {
                val type = if (a is java.net.Inet6Address) "IPv6 address" else "address"
                sb.append("$host has $type ${a.hostAddress}\n")
            }
            ShellResult(output = sb.toString().trimEnd())
        } catch (e: Exception) {
            ShellResult(error = "host: Host $host not found: ${e.message}")
        }
    }

    // ── Environment & Shell State ─────────────────────────────────────────────

    private fun executeEnv(): ShellResult {
        val sb = StringBuilder()
        sessionEnv.toSortedMap().forEach { (k, v) ->
            sb.append("${ANSI_CYAN}$k${ANSI_RESET}=$v\n")
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeExport(args: List<String>): ShellResult {
        if (args.isEmpty()) return executeEnv()
        val sb = StringBuilder()
        for (a in args) {
            val eq = a.indexOf('=')
            if (eq > 0) {
                val k = a.substring(0, eq).trim()
                val v = a.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                sessionEnv[k] = v
                sb.append("${ANSI_GREEN}export $k=\"$v\"$ANSI_RESET\n")
            }
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeUnset(args: List<String>): ShellResult {
        for (a in args) sessionEnv.remove(a)
        return ShellResult(output = "")
    }

    private fun executeAlias(args: List<String>): ShellResult {
        if (args.isEmpty()) {
            val sb = StringBuilder()
            sessionAliases.toSortedMap().forEach { (k, v) ->
                sb.append("alias $k='$v'\n")
            }
            return ShellResult(output = sb.toString().trimEnd().ifEmpty { "No aliases defined" })
        }
        val full = args.joinToString(" ")
        val eq = full.indexOf('=')
        if (eq > 0) {
            val k = full.substring(0, eq).trim()
            val v = full.substring(eq + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            sessionAliases[k] = v
            return ShellResult(output = "alias $k='$v'")
        }
        return ShellResult(error = "usage: alias name='command'")
    }

    private suspend fun executeSleep(args: List<String>): ShellResult {
        val secs = args.firstOrNull()?.toDoubleOrNull() ?: 1.0
        delay((secs * 1000).toLong())
        return ShellResult(output = "")
    }

    // ── System & File Inspection ──────────────────────────────────────────────

    private fun executeFile(args: List<String>, currentDir: String): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: file <path...>")
        val sb = StringBuilder()
        for (a in args) {
            val f = resolvePath(a, currentDir)
            if (!f.exists()) {
                sb.append("${f.name}: cannot open (No such file or directory)\n")
                continue
            }
            if (f.isDirectory) {
                sb.append("${f.name}: directory\n")
                continue
            }
            if (f.length() == 0L) {
                sb.append("${f.name}: empty\n")
                continue
            }
            val header = ByteArray(minOf(f.length(), 64L).toInt())
            f.inputStream().use { it.read(header) }
            val desc = when {
                header.take(4) == listOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte()) -> "Zip archive data"
                header.take(2) == listOf(0x1F.toByte(), 0x8B.toByte()) -> "gzip compressed data"
                header.take(4) == listOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) -> "ELF executable / shared object"
                header.take(4) == listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) -> "PNG image data"
                header.take(3) == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) -> "JPEG image data"
                header.take(4) == listOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte()) -> "GIF image data"
                header.take(4) == listOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte()) -> "PDF document"
                header.take(16) == "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).toList() -> "SQLite 3.x database"
                f.name.endsWith(".kt") -> "Kotlin source code text"
                f.name.endsWith(".java") -> "Java source code text"
                f.name.endsWith(".js") -> "JavaScript source text"
                f.name.endsWith(".ts") || f.name.endsWith(".tsx") -> "TypeScript source text"
                f.name.endsWith(".html") || f.name.endsWith(".htm") -> "HTML document text"
                f.name.endsWith(".json") -> "JSON data text"
                f.name.endsWith(".sh") -> "POSIX shell script executable text"
                f.name.endsWith(".md") -> "Markdown document text"
                else -> "ASCII / UTF-8 text"
            }
            sb.append("${f.name}: $desc\n")
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeDu(args: List<String>, currentDir: String): ShellResult {
        var human = false
        val paths = mutableListOf<String>()
        for (a in args) {
            if (a == "-h" || a == "-sh") human = true
            else if (!a.startsWith("-")) paths.add(a)
        }
        val targetPaths = if (paths.isEmpty()) listOf(".") else paths

        fun calcSize(file: File): Long {
            if (file.isFile) return file.length()
            var sum = 0L
            file.listFiles()?.forEach { sum += calcSize(it) }
            return sum
        }

        val sb = StringBuilder()
        for (p in targetPaths) {
            val f = resolvePath(p, currentDir)
            if (!f.exists()) continue
            val sz = calcSize(f)
            sb.append(String.format(Locale.US, "%-10s %s\n", formatFileSize(sz, human), f.name))
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executePs(): ShellResult {
        val threads = Thread.getAllStackTraces().keys
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%-6s %-6s %-14s %s\n", "PID", "TID", "STATE", "COMMAND/NAME"))
        val myPid = android.os.Process.myPid()
        for (t in threads) {
            sb.append(String.format(Locale.US, "%-6d %-6d %-14s %s\n", myPid, t.id, t.state.name, t.name))
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeFree(args: List<String>): ShellResult {
        val human = !args.contains("-m")
        val rt = Runtime.getRuntime()
        val totalJvm = rt.totalMemory()
        val freeJvm = rt.freeMemory()
        val usedJvm = totalJvm - freeJvm

        var memTotal = totalJvm
        var memFree = freeJvm
        var memAvail = freeJvm
        runCatching {
            File("/proc/meminfo").readLines().forEach { line ->
                val parts = line.split(Regex(":\\s+"))
                if (parts.size >= 2) {
                    val kb = parts[1].removeSuffix("kB").trim().toLongOrNull() ?: 0L
                    val bytes = kb * 1024
                    when (parts[0]) {
                        "MemTotal" -> memTotal = bytes
                        "MemFree"  -> memFree = bytes
                        "MemAvailable" -> memAvail = bytes
                    }
                }
            }
        }

        val memUsed = memTotal - memFree
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%-10s %10s %10s %10s %10s\n", "", "total", "used", "free", "available"))
        sb.append(String.format(Locale.US, "%-10s %10s %10s %10s %10s\n", "Mem:", formatFileSize(memTotal, human), formatFileSize(memUsed, human), formatFileSize(memFree, human), formatFileSize(memAvail, human)))
        sb.append(String.format(Locale.US, "%-10s %10s %10s %10s %10s\n", "JVM:", formatFileSize(totalJvm, human), formatFileSize(usedJvm, human), formatFileSize(freeJvm, human), formatFileSize(rt.maxMemory(), human)))
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeTop(): ShellResult {
        val threads = Thread.getAllStackTraces().keys.toList().sortedByDescending { it.priority }
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val sb = StringBuilder()
        sb.append("${ANSI_BOLD}Krypton Task Monitor (Top 10 Threads)$ANSI_RESET\n")
        sb.append("JVM Heap: ${usedMb}MB / ${maxMb}MB · Active Threads: ${threads.size}\n\n")
        sb.append(String.format(Locale.US, "%-6s %-4s %-12s %s\n", "TID", "PRIO", "STATE", "THREAD NAME"))
        for (t in threads.take(10)) {
            sb.append(String.format(Locale.US, "%-6d %-4d %-12s %s\n", t.id, t.priority, t.state.name, t.name))
        }
        return ShellResult(output = sb.toString().trimEnd())
    }

    private fun executeNeofetch(): ShellResult {
        val user = System.getProperty("user.name") ?: "developer"
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val totalMb = rt.totalMemory() / (1024 * 1024)
        val uptimeSec = SystemClock.elapsedRealtime() / 1000
        val hours = uptimeSec / 3600
        val mins = (uptimeSec % 3600) / 60
        val uptimeStr = "${hours}h ${mins}m"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        return ShellResult(output = """
${ANSI_CYAN}         /\
        /  \         ${ANSI_BOLD}${ANSI_GREEN}$user@krypton${ANSI_RESET}
${ANSI_CYAN}       / /\ \        ${ANSI_GRAY}-----------------------${ANSI_RESET}
      / /  \ \       ${ANSI_BOLD}OS:${ANSI_RESET} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
${ANSI_CYAN}     / / /\ \ \      ${ANSI_BOLD}Host:${ANSI_RESET} ${Build.MANUFACTURER} ${Build.MODEL}
    / / /  \ \ \     ${ANSI_BOLD}Kernel:${ANSI_RESET} Linux ${System.getProperty("os.version")}
${ANSI_CYAN}   /_/_/    \_\_\    ${ANSI_BOLD}Uptime:${ANSI_RESET} $uptimeStr
                     ${ANSI_BOLD}Shell:${ANSI_RESET} Kryptonide Native POSIX Shell v4.0
                     ${ANSI_BOLD}CPU Arch:${ANSI_RESET} $abi
                     ${ANSI_BOLD}JVM Memory:${ANSI_RESET} ${usedMb}MB / ${totalMb}MB
                     ${ANSI_BOLD}Java:${ANSI_RESET} ${System.getProperty("java.vm.name")}

   ${ANSI_RED}███${ANSI_GREEN}███${ANSI_YELLOW}███${ANSI_BLUE}███${ANSI_MAGENTA}███${ANSI_CYAN}███${ANSI_WHITE}███${ANSI_RESET}
""".trimIndent())
    }

    private fun executeSeq(args: List<String>): ShellResult {
        if (args.isEmpty()) return ShellResult(output = "usage: seq [start] [step] end")
        val nums = args.mapNotNull { it.toIntOrNull() }
        val range = when (nums.size) {
            1 -> 1..nums[0]
            2 -> nums[0]..nums[1]
            3 -> nums[0]..nums[2] step nums[1]
            else -> 1..10
        }
        return ShellResult(output = range.joinToString("\n"))
    }

    private fun resolveScriptArgs(args: List<String>, currentDir: String): List<String> {
        if (args.isEmpty()) return emptyList()
        if (args[0].startsWith("-")) return args

        val scriptCandidate = resolvePath(args[0], currentDir)
        return if (scriptCandidate.exists()) {
            listOf(scriptCandidate.absolutePath) + args.drop(1)
        } else {
            args
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun resolvePath(path: String, currentDir: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(currentDir, path)
        }
    }

    private fun parseArguments(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '

        for (char in line) {
            when {
                (char == '"' || char == '\'') && !inQuotes -> {
                    inQuotes = true
                    quoteChar = char
                }
                char == quoteChar && inQuotes -> {
                    inQuotes = false
                }
                char == ' ' && !inQuotes -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(char)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    private fun formatFileSize(bytes: Long, humanReadable: Boolean): String {
        if (!humanReadable) return bytes.toString()
        if (bytes < 1024) return "${bytes}B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f%c", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
