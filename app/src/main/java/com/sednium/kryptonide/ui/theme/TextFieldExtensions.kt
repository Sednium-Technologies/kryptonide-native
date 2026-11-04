package androidx.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compatibility shim for Material3's new OutlinedTextFieldDefaults.colors() API.
 * Provides sensible defaults that work in both dark and light themes.
 * Callers should pass [textColor] explicitly to match the theme's OnBackground/OnSurface color.
 */
@Composable
fun TextFieldDefaults.outlinedTextFieldColors(
    containerColor: Color = Color.Transparent,
    focusedBorderColor: Color = Color(0xFFF3F0E7),
    unfocusedBorderColor: Color = Color.Gray,
    textColor: Color = Color.Unspecified,
    cursorColor: Color = Color(0xFFF3F0E7),
    disabledTextColor: Color = Color.Gray,
    disabledBorderColor: Color = Color.Gray,
    errorBorderColor: Color = Color.Red,
    errorCursorColor: Color = Color.Red,
    labelColor: Color = Color.Unspecified,
    disabledLabelColor: Color = Color.Gray,
    placeholderColor: Color = Color.Gray,
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor    = containerColor,
    unfocusedContainerColor  = containerColor,
    disabledContainerColor   = containerColor,
    errorContainerColor      = containerColor,
    focusedBorderColor       = focusedBorderColor,
    unfocusedBorderColor     = unfocusedBorderColor,
    focusedTextColor         = textColor,
    unfocusedTextColor       = textColor,
    cursorColor              = cursorColor,
    disabledTextColor        = disabledTextColor,
    disabledBorderColor      = disabledBorderColor,
    errorBorderColor         = errorBorderColor,
    errorCursorColor         = errorCursorColor,
    focusedLabelColor        = if (labelColor == Color.Unspecified) focusedBorderColor else labelColor,
    unfocusedLabelColor      = if (labelColor == Color.Unspecified) unfocusedBorderColor else labelColor,
    disabledLabelColor       = disabledLabelColor,
    focusedPlaceholderColor  = placeholderColor,
    unfocusedPlaceholderColor= placeholderColor,
)
