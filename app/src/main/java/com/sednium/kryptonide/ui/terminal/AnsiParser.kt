package com.sednium.kryptonide.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * ANSI Escape Code Parser for Jetpack Compose.
 *
 * Converts ANSI color and style escape sequences (e.g. \u001B[32m, \u001B[1;34m)
 * into rich Compose [AnnotatedString] with matching foreground colors and font weights.
 */
object AnsiParser {

    private val ANSI_REGEX = Regex("\u001B\\[([0-9;]*)m")
    private val ANSI_NON_COLOR_REGEX = Regex("\u001B\\[[0-9;?]*[a-zA-Z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Z0-9]")

    // Terminal standard color palette
    private val COLOR_BLACK        = Color(0xFF484F58)
    private val COLOR_RED          = Color(0xFFF85149)
    private val COLOR_GREEN        = Color(0xFF3FB950)
    private val COLOR_YELLOW       = Color(0xFFD29922)
    private val COLOR_BLUE         = Color(0xFF58A6FF)
    private val COLOR_MAGENTA      = Color(0xFFBC8CFF)
    private val COLOR_CYAN         = Color(0xFF39C5CF)
    private val COLOR_WHITE        = Color(0xFFE6EDF3)

    // Bright variants
    private val COLOR_BRIGHT_BLACK   = Color(0xFF6E7681)
    private val COLOR_BRIGHT_RED     = Color(0xFFFF7B72)
    private val COLOR_BRIGHT_GREEN   = Color(0xFF56D364)
    private val COLOR_BRIGHT_YELLOW  = Color(0xFFE3B341)
    private val COLOR_BRIGHT_BLUE    = Color(0xFF79C0FF)
    private val COLOR_BRIGHT_MAGENTA = Color(0xFFD2A8FF)
    private val COLOR_BRIGHT_CYAN    = Color(0xFF56D4DD)
    private val COLOR_BRIGHT_WHITE   = Color(0xFFF0F6FC)

    fun parse(raw: String, defaultColor: Color = COLOR_WHITE, isDark: Boolean = true): AnnotatedString {
        if (!raw.contains('\u001B')) {
            return AnnotatedString(raw)
        }

        // Clean out non-color ANSI codes first (cursor movements, screen clear, etc.)
        val cleaned = raw.replace(ANSI_NON_COLOR_REGEX) { match ->
            if (match.value.endsWith("m")) match.value else ""
        }

        val effectiveBlack = if (isDark) Color(0xFF8B949E) else Color(0xFF24292F)
        val effectiveWhite = if (isDark) Color(0xFFF3F0E7) else Color(0xFF57606A)

        return buildAnnotatedString {
            var currentColor: Color? = null
            var currentWeight: FontWeight? = null

            var lastIndex = 0
            val matches = ANSI_REGEX.findAll(cleaned)

            for (match in matches) {
                // Append text before this code
                if (match.range.first > lastIndex) {
                    val chunk = cleaned.substring(lastIndex, match.range.first)
                    if (currentColor != null || currentWeight != null) {
                        withStyle(SpanStyle(color = currentColor ?: defaultColor, fontWeight = currentWeight)) {
                            append(chunk)
                        }
                    } else {
                        append(chunk)
                    }
                }

                // Process ANSI codes
                val codesStr = match.groupValues[1]
                val codes = if (codesStr.isBlank()) listOf(0) else codesStr.split(";").mapNotNull { it.toIntOrNull() }

                for (code in codes) {
                    when (code) {
                        0 -> {
                            currentColor = null
                            currentWeight = null
                        }
                        1 -> currentWeight = FontWeight.Bold
                        2 -> currentWeight = FontWeight.Light
                        22 -> currentWeight = FontWeight.Normal

                        // Standard foreground
                        30 -> currentColor = effectiveBlack
                        31 -> currentColor = COLOR_RED
                        32 -> currentColor = COLOR_GREEN
                        33 -> currentColor = COLOR_YELLOW
                        34 -> currentColor = COLOR_BLUE
                        35 -> currentColor = COLOR_MAGENTA
                        36 -> currentColor = COLOR_CYAN
                        37 -> currentColor = effectiveWhite
                        39 -> currentColor = null // default

                        // High intensity foreground
                        90 -> currentColor = if (isDark) Color(0xFFA39F9B) else Color(0xFF6E7681)
                        91 -> currentColor = COLOR_BRIGHT_RED
                        92 -> currentColor = COLOR_BRIGHT_GREEN
                        93 -> currentColor = COLOR_BRIGHT_YELLOW
                        94 -> currentColor = COLOR_BRIGHT_BLUE
                        95 -> currentColor = COLOR_BRIGHT_MAGENTA
                        96 -> currentColor = COLOR_BRIGHT_CYAN
                        97 -> currentColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF2A2529)
                    }
                }

                lastIndex = match.range.last + 1
            }

            // Append remaining text
            if (lastIndex < cleaned.length) {
                val chunk = cleaned.substring(lastIndex)
                if (currentColor != null || currentWeight != null) {
                    withStyle(SpanStyle(color = currentColor ?: defaultColor, fontWeight = currentWeight)) {
                        append(chunk)
                    }
                } else {
                    append(chunk)
                }
            }
        }
    }

    /**
     * Strips all ANSI codes and terminal control characters for plain-text storage or clipboard copy.
     */
    fun stripAnsi(raw: String): String {
        return raw
            .replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
            .replace(Regex("\u001B[()][A-Z0-9]"), "")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
    }
}
