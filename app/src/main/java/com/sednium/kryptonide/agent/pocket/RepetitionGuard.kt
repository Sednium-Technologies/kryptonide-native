package com.sednium.kryptonide.agent.pocket

/**
 * Guard against degenerative autoregressive repetition loops in small language models.
 * Detects repeating lines or phrases and truncates the generation before it spirals into
 * infinite repetition.
 */
object RepetitionGuard {

    /**
     * Truncates [text] if any line or phrase repeats [maxRepeats] or more times consecutively.
     * Retains at most [maxRepeats] - 1 occurrences of the repeating line/phrase.
     */
    fun detectAndTrimRepetition(text: String, maxRepeats: Int = 3): String {
        if (text.length < 24) return text

        // 1. Line-by-line repetition check
        val lines = text.lines()
        val keptLines = mutableListOf<String>()
        var repeatCount = 0
        var lastNormalizedLine = ""
        var lineLoopDetected = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 6..120) {
                if (trimmed == lastNormalizedLine) {
                    repeatCount++
                    if (repeatCount >= maxRepeats) {
                        lineLoopDetected = true
                        break
                    }
                } else {
                    repeatCount = 1
                    lastNormalizedLine = trimmed
                }
            } else {
                repeatCount = 0
                lastNormalizedLine = ""
            }
            keptLines.add(line)
        }

        val checkedText = if (lineLoopDetected) keptLines.joinToString("\n").trimEnd() else text

        // 2. Phrase-level repetition check across arbitrary substrings (8 to 60 chars)
        return trimPhraseRepetition(checkedText, minLen = 8, maxLen = 60, maxRepeats = maxRepeats)
    }

    private fun trimPhraseRepetition(text: String, minLen: Int = 8, maxLen: Int = 60, maxRepeats: Int = 3): String {
        for (len in minLen..maxLen) {
            if (len * maxRepeats > text.length) break
            var i = 0
            while (i + len * maxRepeats <= text.length) {
                val candidate = text.substring(i, i + len)
                if (candidate.isBlank() || candidate.all { !it.isLetterOrDigit() }) {
                    i++
                    continue
                }
                var matches = 1
                while (i + (matches + 1) * len <= text.length &&
                    text.substring(i + matches * len, i + (matches + 1) * len) == candidate) {
                    matches++
                    if (matches >= maxRepeats) {
                        return text.substring(0, i + (maxRepeats - 1) * len).trimEnd()
                    }
                }
                i++
            }
        }
        return text
    }
}
