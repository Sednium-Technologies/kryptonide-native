package com.sednium.kryptonide.ui.editor

/**
 * The editor's base light/dark scheme, picked from Settings. This is
 * separate from the richer marketplace theme catalog in
 * [com.sednium.kryptonide.ui.plugins.BuiltInThemes] (One Dark Pro, Dracula,
 * etc.) — this one is the small built-in set, applied directly as a
 * hand-built [io.github.rosemoe.sora.widget.schemes.EditorColorScheme] in
 * [SoraEditorHost.applyBaseTheme].
 *
 * `id` is a stable internal identifier, kept in case something wants a
 * string form later — nothing currently reads it. It used to be a raw
 * Monaco theme string ("vs", "vs-dark", "hc-black") sent straight to
 * Monaco's `setTheme()`; renamed since that's meaningless now that the
 * editor is Sora, not Monaco. SettingsStore persists `theme.name` (the enum
 * constant), not `id`, and falls back safely if a stored name doesn't match.
 */
enum class EditorTheme(val id: String, val displayName: String) {
    KRYPTON_DARK ("krypton-dark",  "Krypton Dark"),
    DARK         ("dark",          "Dark"),
    LIGHT        ("light",         "Light"),
    HIGH_CONTRAST("high-contrast", "High Contrast"),
}
