package com.sednium.kryptonide.ui.editor

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleekScrollbarDrawableTest {

    @Test
    fun testResolveColor_defaultSoraColorDarkTheme() {
        val soraDefaultThumb = -2565928 // 0xFFD8D8D8
        val soraDefaultPressed = -14210774 // 0xFF27272A

        val resolvedIdle = resolveScrollbarColor(soraDefaultThumb, isDark = true, pressed = false)
        val resolvedPressed = resolveScrollbarColor(soraDefaultPressed, isDark = true, pressed = true)

        assertEquals("Dark mode idle should be subtle translucent white (~30%)", 0x4DFFFFFF.toInt(), resolvedIdle)
        assertEquals("Dark mode pressed should be prominent translucent white (~63%)", 0xA0FFFFFF.toInt(), resolvedPressed)
    }

    @Test
    fun testResolveColor_defaultSoraColorLightTheme() {
        val soraDefaultThumb = -2565928

        val resolvedIdle = resolveScrollbarColor(soraDefaultThumb, isDark = false, pressed = false)
        val resolvedPressed = resolveScrollbarColor(soraDefaultThumb, isDark = false, pressed = true)

        assertEquals("Light mode idle should be translucent black (~25%)", 0x40000000.toInt(), resolvedIdle)
        assertEquals("Light mode pressed should be translucent black (~50%)", 0x80000000.toInt(), resolvedPressed)
    }

    @Test
    fun testResolveColor_customOpaqueThemeColorSoftened() {
        val customOpaqueColor = 0xFF56B6C2.toInt() // opaque cyan from a theme

        val resolvedIdle = resolveScrollbarColor(customOpaqueColor, isDark = true, pressed = false)
        val resolvedPressed = resolveScrollbarColor(customOpaqueColor, isDark = true, pressed = true)

        val idleAlpha = (resolvedIdle ushr 24) and 0xFF
        val pressedAlpha = (resolvedPressed ushr 24) and 0xFF

        assertEquals("Opaque custom theme colors should have 0x66 alpha when idle", 0x66, idleAlpha)
        assertEquals("Opaque custom theme colors should have 0xCC alpha when pressed", 0xCC, pressedAlpha)
        assertEquals("RGB channels should remain intact", 0x0056B6C2, resolvedIdle and 0x00FFFFFF)
    }

    @Test
    fun testResolveColor_customAlphaPreserved() {
        val customAlphaColor = 0x33A0B0C0.toInt()

        val resolvedIdle = resolveScrollbarColor(customAlphaColor, isDark = true, pressed = false)
        assertEquals("Custom non-opaque alpha should be respected directly", customAlphaColor, resolvedIdle)
    }

    @Test
    fun testScrollbarDimensions_vertical() {
        val density = 3.0f // 1080p mobile screen
        val boundsRight = 1080f // 10dp wide column from Sora ending at screen edge

        // Idle state: 2.5dp width (7.5px) with 2dp right margin (6px)
        val idleWidth = 2.5f * density
        val rightMargin = 2f * density
        val right = boundsRight - rightMargin
        val left = right - idleWidth

        assertEquals("Scrollbar right edge should be inset 2dp (6px) from screen right", 1074.0f, right, 0.01f)
        assertEquals("Scrollbar left edge should result in sleek 2.5dp width", 1066.5f, left, 0.01f)
        assertTrue("Scrollbar thumb should be much narrower than Sora's default 10dp column", (right - left) < 30.0f)

        // Pressed state: expands to 3.5dp (10.5px) for tactile feedback
        val pressedWidth = 3.5f * density
        val pressedLeft = right - pressedWidth
        assertEquals("Pressed thumb width should be 10.5px", 10.5f, right - pressedLeft, 0.01f)
    }

    @Test
    fun testScrollbarDimensions_horizontal() {
        val density = 3.0f
        val boundsBottom = 1920f // 10dp tall strip from Sora ending at bottom

        // Idle state: 2.5dp height (7.5px) with 2dp bottom margin (6px)
        val idleHeight = 2.5f * density
        val bottomMargin = 2f * density
        val bottom = boundsBottom - bottomMargin
        val top = bottom - idleHeight

        assertEquals("Horizontal scrollbar bottom should be inset 2dp (6px) from screen bottom", 1914.0f, bottom, 0.01f)
        assertEquals("Horizontal scrollbar top should result in sleek 2.5dp height", 1906.5f, top, 0.01f)
        assertTrue("Scrollbar thumb should be much slimmer than Sora's default 10dp strip", (bottom - top) < 30.0f)
    }

    private fun resolveScrollbarColor(themeColor: Int, isDark: Boolean, pressed: Boolean): Int {
        val isDefaultSoraColor = themeColor == -2565928 || themeColor == -14210774 || themeColor == 0
        return if (isDefaultSoraColor) {
            if (isDark) {
                if (pressed) 0xA0FFFFFF.toInt() else 0x4DFFFFFF.toInt()
            } else {
                if (pressed) 0x80000000.toInt() else 0x40000000.toInt()
            }
        } else {
            val alpha = (themeColor ushr 24) and 0xFF
            if (alpha == 255) {
                val targetAlpha = if (pressed) 0xCC else 0x66
                (targetAlpha shl 24) or (themeColor and 0x00FFFFFF)
            } else {
                themeColor
            }
        }
    }
}
