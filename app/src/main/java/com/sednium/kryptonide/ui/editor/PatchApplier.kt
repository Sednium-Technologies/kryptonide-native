package com.sednium.kryptonide.ui.editor

/**
 * Intelligent patch applier for KryptonIDE.
 * Supports:
 * 1. Surgical SEARCH/REPLACE blocks (<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE)
 *    with exact substring matching and fuzzy trimmed-line indentation matching.
 * 2. Small snippet replacements for full HTML files (e.g. <meta charset="...">, <title>...</title>).
 * 3. Full file replacements when no patch or snippet pattern is detected.
 */
object PatchApplier {

    private val SEARCH_REPLACE_REGEX = Regex(
        """<<<<<<< SEARCH[^\r\n]*\r?\n([\s\S]*?)\r?\n?=======[^\r\n]*\r?\n([\s\S]*?)\r?\n?>>>>>>> REPLACE""",
        RegexOption.MULTILINE
    )

    fun isSurgicalPatch(code: String): Boolean {
        return code.contains("<<<<<<< SEARCH") && code.contains("=======") && code.contains(">>>>>>> REPLACE")
    }

    fun applyCodeOrPatch(existingFileContent: String, incomingCode: String): String {
        if (existingFileContent.isBlank()) return incomingCode

        val cleanIncoming = incomingCode.trim()

        // 1. Check for surgical SEARCH / REPLACE blocks
        if (isSurgicalPatch(cleanIncoming)) {
            val matches = SEARCH_REPLACE_REGEX.findAll(cleanIncoming).toList()
            if (matches.isNotEmpty()) {
                var current = existingFileContent
                for (m in matches) {
                    val search = m.groupValues[1]
                    val replace = m.groupValues[2]

                    // A. Exact substring replacement
                    if (current.contains(search)) {
                        current = current.replace(search, replace)
                        continue
                    }

                    // B. Normalized line-endings replacement (\r\n -> \n)
                    val normCurrent = current.replace("\r\n", "\n")
                    val normSearch = search.replace("\r\n", "\n")
                    val normReplace = replace.replace("\r\n", "\n")
                    if (normCurrent.contains(normSearch)) {
                        current = normCurrent.replace(normSearch, normReplace)
                        continue
                    }

                    // C. Fallback: trimmed-line matching (accommodates indentation differences)
                    val searchLines = normSearch.lines()
                    val fileLines = normCurrent.lines()
                    val trimmedSearch = searchLines.map { it.trim() }.filter { it.isNotEmpty() }
                    if (trimmedSearch.isEmpty()) continue

                    var matchStartIndex = -1
                    var matchEndIndex = -1

                    for (i in 0 until fileLines.size) {
                        if (fileLines[i].trim() == trimmedSearch.first()) {
                            var matchedAll = true
                            var curFileIdx = i
                            for (s in trimmedSearch) {
                                while (curFileIdx < fileLines.size && fileLines[curFileIdx].trim().isEmpty()) {
                                    curFileIdx++
                                }
                                if (curFileIdx >= fileLines.size || fileLines[curFileIdx].trim() != s) {
                                    matchedAll = false
                                    break
                                }
                                curFileIdx++
                            }
                            if (matchedAll) {
                                matchStartIndex = i
                                matchEndIndex = curFileIdx
                                break
                            }
                        }
                    }

                    if (matchStartIndex != -1 && matchEndIndex != -1) {
                        val baseIndent = fileLines[matchStartIndex].takeWhile { it.isWhitespace() }
                        val replaceLines = normReplace.lines().map { line ->
                            if (line.isBlank()) line else {
                                val lineIndent = line.takeWhile { it.isWhitespace() }
                                if (lineIndent.isEmpty()) "$baseIndent$line" else line
                            }
                        }

                        val newLines = fileLines.toMutableList()
                        for (k in matchStartIndex until matchEndIndex) {
                            newLines.removeAt(matchStartIndex)
                        }
                        newLines.addAll(matchStartIndex, replaceLines)
                        current = newLines.joinToString("\n")
                    }
                }
                return current
            }
        }

        // 2. Small snippet fallback on full HTML files
        val isFullExistingHtml = existingFileContent.contains("<!DOCTYPE", ignoreCase = true) ||
                existingFileContent.contains("<html", ignoreCase = true)
        val incomingHasHtmlSkeleton = cleanIncoming.contains("<!DOCTYPE", ignoreCase = true) ||
                cleanIncoming.contains("<html", ignoreCase = true)

        if (isFullExistingHtml && !incomingHasHtmlSkeleton && cleanIncoming.lines().size <= 8) {
            // Check for meta charset tag (e.g. <meta charset="UTF-16">)
            val metaCharsetRegex = Regex("""<meta\s+charset=["'][^"']*["']\s*\/?>""", RegexOption.IGNORE_CASE)
            if (cleanIncoming.contains("charset", ignoreCase = true) && metaCharsetRegex.containsMatchIn(existingFileContent)) {
                return existingFileContent.replace(metaCharsetRegex, cleanIncoming)
            }

            // Check for title tag (e.g. <title>New Title</title>)
            val titleRegex = Regex("""<title>[\s\S]*?<\/title>""", RegexOption.IGNORE_CASE)
            if (cleanIncoming.startsWith("<title", ignoreCase = true) && titleRegex.containsMatchIn(existingFileContent)) {
                return existingFileContent.replace(titleRegex, cleanIncoming)
            }
        }

        // 3. Complete file replacement
        return incomingCode
    }
}
