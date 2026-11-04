package com.sednium.kryptonide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sednium.kryptonide.R

// ─── Color tokens — DARK theme ────────────────────────────────────────────────
// Strict two-colour system: Charcoal (#2A2529) / Pale Ivory (#F3F0E7) — nothing
// else. Every other chrome role below is a precise alpha-composite of Ivory
// over the Charcoal base (same technique Material's own dark-theme elevation
// system uses — white-overlay-at-increasing-opacity instead of a separate
// gray ramp), not a hand-picked third color. Primary/Secondary intentionally
// reuse OnBackground rather than being their own hue — accent surfaces are an
// inverted pill (solid Ivory fill) rather than a third color; OnPrimary/
// OnSecondary (= Background) is what goes on top of them.
// Error/Success/Warning are UNCHANGED — confirmed as functional/status
// colors, exempt from the two-colour rule (terminal output, git diffs, and
// error/success states keep their own color).
object KryptonDarkColors {
    val Background   = Color(0xFF2A2529)
    val Surface      = Color(0xFF363134) // Ivory @ 6%  over Charcoal
    val SurfaceVar   = Color(0xFF403B3E) // Ivory @ 11% over Charcoal
    val Border       = Color(0xFF4E4A4B) // Ivory @ 18% over Charcoal
    val OnBackground = Color(0xFFF3F0E7)
    val OnSurface    = Color(0xFFF3F0E7)
    val Primary      = Color(0xFFF3F0E7) // = OnBackground (inverted-pill fill)
    val Secondary    = Color(0xFFD5D2CB) // Ivory @ 85% over Charcoal
    val OnPrimary    = Color(0xFF2A2529) // = Background (content atop Primary/Secondary fills)
    val OnSecondary  = Color(0xFF2A2529)
    val Muted        = Color(0xFFA39F9B) // Ivory @ 60% over Charcoal
    val Error        = Color(0xFFEF4444)
    val Success      = Color(0xFF10B981)
    val Warning      = Color(0xFFF59E0B)
}

// ─── Color tokens — LIGHT theme ───────────────────────────────────────────────
object KryptonLightColors {
    val Background   = Color(0xFFF3F0E7)
    val Surface      = Color(0xFFEBE8DF) // Charcoal @ 4%  over Ivory
    val SurfaceVar   = Color(0xFFE3E0D8) // Charcoal @ 8%  over Ivory
    val Border       = Color(0xFFD3D0C9) // Charcoal @ 16% over Ivory
    val OnBackground = Color(0xFF2A2529)
    val OnSurface    = Color(0xFF2A2529)
    val Primary      = Color(0xFF2A2529) // = OnBackground (inverted-pill fill)
    val Secondary    = Color(0xFF484346) // Charcoal @ 85% over Ivory
    val OnPrimary    = Color(0xFFF3F0E7) // = Background (content atop Primary/Secondary fills)
    val OnSecondary  = Color(0xFFF3F0E7)
    val Muted        = Color(0xFF84807F) // Charcoal @ 55% over Ivory
    val Error        = Color(0xFFCF222E)
    val Success      = Color(0xFF1A7F37)
    val Warning      = Color(0xFF9A6700)
}

// ─── Resolved palette (provided via CompositionLocal) ─────────────────────────
// Composables read from LocalKryptonColors — automatically updates on toggle.

data class KryptonColorPalette(
    val Background:   Color,
    val Surface:      Color,
    val SurfaceVar:   Color,
    val Border:       Color,
    val OnBackground: Color,
    val OnSurface:    Color,
    val Primary:      Color,
    val Secondary:    Color,
    val OnPrimary:    Color,
    val OnSecondary:  Color,
    val Muted:        Color,
    val Error:        Color,
    val Success:      Color,
    val Warning:      Color,
    val isDark:       Boolean,
)

val DarkPalette = KryptonColorPalette(
    Background   = KryptonDarkColors.Background,
    Surface      = KryptonDarkColors.Surface,
    SurfaceVar   = KryptonDarkColors.SurfaceVar,
    Border       = KryptonDarkColors.Border,
    OnBackground = KryptonDarkColors.OnBackground,
    OnSurface    = KryptonDarkColors.OnSurface,
    Primary      = KryptonDarkColors.Primary,
    Secondary    = KryptonDarkColors.Secondary,
    OnPrimary    = KryptonDarkColors.OnPrimary,
    OnSecondary  = KryptonDarkColors.OnSecondary,
    Muted        = KryptonDarkColors.Muted,
    Error        = KryptonDarkColors.Error,
    Success      = KryptonDarkColors.Success,
    Warning      = KryptonDarkColors.Warning,
    isDark       = true,
)

val LightPalette = KryptonColorPalette(
    Background   = KryptonLightColors.Background,
    Surface      = KryptonLightColors.Surface,
    SurfaceVar   = KryptonLightColors.SurfaceVar,
    Border       = KryptonLightColors.Border,
    OnBackground = KryptonLightColors.OnBackground,
    OnSurface    = KryptonLightColors.OnSurface,
    Primary      = KryptonLightColors.Primary,
    Secondary    = KryptonLightColors.Secondary,
    OnPrimary    = KryptonLightColors.OnPrimary,
    OnSecondary  = KryptonLightColors.OnSecondary,
    Muted        = KryptonLightColors.Muted,
    Error        = KryptonLightColors.Error,
    Success      = KryptonLightColors.Success,
    Warning      = KryptonLightColors.Warning,
    isDark       = false,
)

// Keep this name so existing code `KryptonColors.X` still compiles.
// Redirects to the CompositionLocal at runtime.
// Usage in composables: val colors = LocalKryptonColors.current
val LocalKryptonColors = compositionLocalOf { LightPalette }

// Legacy static alias so files using KryptonColors.Surface etc. still compile.
// Remove once you migrate all usages to LocalKryptonColors.current.
object KryptonColors {
    val Background  get() = KryptonLightColors.Background
    val Surface     get() = KryptonLightColors.Surface
    val SurfaceVar  get() = KryptonLightColors.SurfaceVar
    val Border      get() = KryptonLightColors.Border
    val OnBackground get() = KryptonLightColors.OnBackground
    val OnSurface   get() = KryptonLightColors.OnSurface
    val Primary     get() = KryptonLightColors.Primary
    val Secondary   get() = KryptonLightColors.Secondary
    val OnPrimary   get() = KryptonLightColors.OnPrimary
    val OnSecondary get() = KryptonLightColors.OnSecondary
    val Muted       get() = KryptonLightColors.Muted
    val Error       get() = KryptonLightColors.Error
    val Success     get() = KryptonLightColors.Success
    val Warning     get() = KryptonLightColors.Warning
}

// ─── Material3 color schemes ──────────────────────────────────────────────────

private val M3DarkScheme = darkColorScheme(
    primary       = KryptonDarkColors.Primary,
    secondary     = KryptonDarkColors.Secondary,
    background    = KryptonDarkColors.Background,
    surface       = KryptonDarkColors.Surface,
    onPrimary     = KryptonDarkColors.OnPrimary,
    onSecondary   = KryptonDarkColors.OnSecondary,
    onBackground  = KryptonDarkColors.OnBackground,
    onSurface     = KryptonDarkColors.OnSurface,
    error         = KryptonDarkColors.Error,
    onError       = Color.White,
)

private val M3LightScheme = lightColorScheme(
    primary       = KryptonLightColors.Primary,
    secondary     = KryptonLightColors.Secondary,
    background    = KryptonLightColors.Background,
    surface       = KryptonLightColors.Surface,
    onPrimary     = KryptonLightColors.OnPrimary,
    onSecondary   = KryptonLightColors.OnSecondary,
    onBackground  = KryptonLightColors.OnBackground,
    onSurface     = KryptonLightColors.OnSurface,
    error         = KryptonLightColors.Error,
    onError       = Color.White,
)

val UrbanistFontFamily = FontFamily(
    Font(R.font.urbanist_regular, FontWeight.Normal),
    Font(R.font.urbanist_medium, FontWeight.Medium),
    Font(R.font.urbanist_semibold, FontWeight.SemiBold),
    Font(R.font.urbanist_bold, FontWeight.Bold)
)

// ─── KryptonTheme composable ──────────────────────────────────────────────────
// isDark = false → Light theme (default as requested)
// isDark = true  → Dark theme

@Composable
fun KryptonTheme(
    isDark: Boolean = false,          // DEFAULT: Light theme
    useDynamicColor: Boolean = true,
    fontFamily: FontFamily = UrbanistFontFamily,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haveDynamicColor = useDynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val colorScheme = when {
        haveDynamicColor && isDark -> androidx.compose.material3.dynamicDarkColorScheme(context)
        haveDynamicColor && !isDark -> androidx.compose.material3.dynamicLightColorScheme(context)
        isDark -> M3DarkScheme
        else -> M3LightScheme
    }

    val palette = if (isDark) DarkPalette.copy(
        Background = if (haveDynamicColor) colorScheme.background else DarkPalette.Background,
        Surface = if (haveDynamicColor) colorScheme.surface else DarkPalette.Surface,
        SurfaceVar = if (haveDynamicColor) colorScheme.surfaceVariant else DarkPalette.SurfaceVar,
        Primary = if (haveDynamicColor) colorScheme.primary else DarkPalette.Primary,
        Secondary = if (haveDynamicColor) colorScheme.secondary else DarkPalette.Secondary,
        Border = if (haveDynamicColor) colorScheme.outlineVariant else DarkPalette.Border,
        OnBackground = if (haveDynamicColor) colorScheme.onBackground else DarkPalette.OnBackground,
        OnSurface = if (haveDynamicColor) colorScheme.onSurface else DarkPalette.OnSurface,
    ) else LightPalette.copy(
        Background = if (haveDynamicColor) colorScheme.background else LightPalette.Background,
        Surface = if (haveDynamicColor) colorScheme.surface else LightPalette.Surface,
        SurfaceVar = if (haveDynamicColor) colorScheme.surfaceVariant else LightPalette.SurfaceVar,
        Primary = if (haveDynamicColor) colorScheme.primary else LightPalette.Primary,
        Secondary = if (haveDynamicColor) colorScheme.secondary else LightPalette.Secondary,
        Border = if (haveDynamicColor) colorScheme.outlineVariant else LightPalette.Border,
        OnBackground = if (haveDynamicColor) colorScheme.onBackground else LightPalette.OnBackground,
        OnSurface = if (haveDynamicColor) colorScheme.onSurface else LightPalette.OnSurface,
    )

    val activeFont = if (fontFamily == FontFamily.Default) UrbanistFontFamily else fontFamily

    val customTypography = androidx.compose.material3.Typography(
        displayLarge = androidx.compose.material3.Typography().displayLarge.copy(fontFamily = activeFont),
        displayMedium = androidx.compose.material3.Typography().displayMedium.copy(fontFamily = activeFont),
        displaySmall = androidx.compose.material3.Typography().displaySmall.copy(fontFamily = activeFont),
        headlineLarge = androidx.compose.material3.Typography().headlineLarge.copy(fontFamily = activeFont),
        headlineMedium = androidx.compose.material3.Typography().headlineMedium.copy(fontFamily = activeFont),
        headlineSmall = androidx.compose.material3.Typography().headlineSmall.copy(fontFamily = activeFont),
        titleLarge = androidx.compose.material3.Typography().titleLarge.copy(fontFamily = activeFont),
        titleMedium = androidx.compose.material3.Typography().titleMedium.copy(fontFamily = activeFont),
        titleSmall = androidx.compose.material3.Typography().titleSmall.copy(fontFamily = activeFont),
        bodyLarge = androidx.compose.material3.Typography().bodyLarge.copy(fontFamily = activeFont),
        bodyMedium = androidx.compose.material3.Typography().bodyMedium.copy(fontFamily = activeFont),
        bodySmall = androidx.compose.material3.Typography().bodySmall.copy(fontFamily = activeFont),
        labelLarge = androidx.compose.material3.Typography().labelLarge.copy(fontFamily = activeFont),
        labelMedium = androidx.compose.material3.Typography().labelMedium.copy(fontFamily = activeFont),
        labelSmall = androidx.compose.material3.Typography().labelSmall.copy(fontFamily = activeFont)
    )

    CompositionLocalProvider(LocalKryptonColors provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = customTypography,
            content     = content
        )
    }
}
