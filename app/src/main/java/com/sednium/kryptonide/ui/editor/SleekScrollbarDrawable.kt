package com.sednium.kryptonide.ui.editor

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * High-performance, sleek modern scrollbar thumb drawable for Rosemoe Sora-Editor.
 * Replaces the default clunky, square 10dp block with an elegant 3dp rounded capsule
 * floating gracefully inside the editor gutter with subtle translucency.
 */
class SleekScrollbarDrawable(
    private val editor: CodeEditor,
    val isVertical: Boolean
) : Drawable() {

    internal val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal var isPressedState = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        var pressed = false
        for (s in state) {
            if (s == android.R.attr.state_pressed) {
                pressed = true
                break
            }
        }
        if (isPressedState != pressed) {
            isPressedState = pressed
            invalidateSelf()
            return true
        }
        return super.onStateChange(state)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        val density = runCatching {
            editor.context.resources.displayMetrics.density
        }.getOrDefault(2.5f)

        // Determine background and calculate light vs dark mode
        val bg = runCatching {
            editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        }.getOrDefault(0xFF1E1E1E.toInt())
        val isDark = ColorUtils.calculateLuminance(bg) < 0.5

        val themeThumb = runCatching {
            editor.colorScheme.getColor(EditorColorScheme.SCROLL_BAR_THUMB)
        }.getOrDefault(0)

        val themePressed = runCatching {
            editor.colorScheme.getColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED)
        }.getOrDefault(0)

        // Resolve normal and pressed colors with subtle alpha
        val normalColor = resolveColor(themeThumb, isDark, pressed = false)
        val pressedColor = resolveColor(themePressed, isDark, pressed = true)

        paint.color = if (isPressedState) pressedColor else normalColor

        if (isVertical) {
            // Elegant slim vertical pill (2.5dp idle, expands slightly to 3.5dp when held for tactile feedback)
            val thumbWidth = (if (isPressedState) 3.5f else 2.5f) * density
            val rightMargin = 2f * density
            val verticalMargin = 1.5f * density

            val left = b.right.toFloat() - rightMargin - thumbWidth
            val right = b.right.toFloat() - rightMargin
            val top = b.top.toFloat() + verticalMargin
            val bottom = b.bottom.toFloat() - verticalMargin

            if (bottom > top && right > left) {
                val radius = thumbWidth / 2f
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            }
        } else {
            // Elegant slim horizontal pill
            val thumbHeight = (if (isPressedState) 3.5f else 2.5f) * density
            val bottomMargin = 2f * density
            val horizontalMargin = 1.5f * density

            val left = b.left.toFloat() + horizontalMargin
            val right = b.right.toFloat() - horizontalMargin
            val top = b.bottom.toFloat() - bottomMargin - thumbHeight
            val bottom = b.bottom.toFloat() - bottomMargin

            if (right > left && bottom > top) {
                val radius = thumbHeight / 2f
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            }
        }
    }

    internal fun resolveColor(themeColor: Int, isDark: Boolean, pressed: Boolean): Int {
        // Sora's default unconfigured colors in EditorColorScheme:
        // SCROLL_BAR_THUMB = -2565928 (0xFFD8D8D8)
        // SCROLL_BAR_THUMB_PRESSED = -14210774 (0xFF27272A)
        val isDefaultSoraColor = themeColor == -2565928 || themeColor == -14210774 || themeColor == 0

        return if (isDefaultSoraColor) {
            if (isDark) {
                if (pressed) 0xA0FFFFFF.toInt() else 0x4DFFFFFF.toInt()
            } else {
                if (pressed) 0x80000000.toInt() else 0x40000000.toInt()
            }
        } else {
            // Theme explicitly provided a custom color; ensure it has good translucency
            val alpha = (themeColor ushr 24) and 0xFF
            if (alpha == 255) {
                val targetAlpha = if (pressed) 0xCC else 0x66
                (targetAlpha shl 24) or (themeColor and 0x00FFFFFF)
            } else {
                themeColor
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
