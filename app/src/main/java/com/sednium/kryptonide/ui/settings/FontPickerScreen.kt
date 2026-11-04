package com.sednium.kryptonide.ui.settings

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sednium.kryptonide.ui.editor.*
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FontPickerScreen(
    fontManager: FontManager = viewModel(),
    onImportClick: () -> Unit,
    onBack: () -> Unit
) {
    val fonts by fontManager.fonts.collectAsState()
    val selectedFont by fontManager.selectedFont.collectAsState()
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showDeleteDialog by remember { mutableStateOf<FontItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Compact Sleek Header matching other panels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ArrowLeft, "Back to Settings", tint = TextPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.Type, contentDescription = null, tint = AccentLight, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Editor Font", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                val bundledFonts = fonts.filter { !it.isCustom }
                val customFonts = fonts.filter { it.isCustom }

                if (bundledFonts.isNotEmpty()) {
                    item { SectionHeader("BUNDLED") }
                    items(bundledFonts) { font ->
                        FontRow(
                            font = font,
                            isSelected = font.name == selectedFont?.font?.name,
                            onClick = { fontManager.selectFont(font) },
                            onLongClick = { }
                        )
                    }
                }

                item { SectionHeader("CUSTOM") }
                
                items(customFonts) { font ->
                    FontRow(
                        font = font,
                        isSelected = font.name == selectedFont?.font?.name,
                        onClick = { fontManager.selectFont(font) },
                        onLongClick = { showDeleteDialog = font }
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = onImportClick)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.Plus, "Import", tint = AccentLight)
                        Spacer(Modifier.width(16.dp))
                        Text("Import Font", color = AccentLight, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = SurfaceDark,
            title = { Text("Delete Font", color = TextPrimary) },
            text = { Text("Are you sure you want to delete ${showDeleteDialog?.name}?", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog?.let { fontManager.deleteCustomFont(it) }
                    showDeleteDialog = null
                }) {
                    Text("Delete", color = LocalKryptonColors.current.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = AccentLight,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontRow(
    font: FontItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val previewFontFamily = remember(font.fileName, font.filePath, font.isCustom) {
        runCatching {
            val tf = if (font.isCustom && font.filePath != null) {
                Typeface.createFromFile(font.filePath)
            } else {
                Typeface.createFromAsset(context.assets, "fonts/${font.fileName}")
            }
            androidx.compose.ui.text.font.FontFamily(tf)
        }.getOrElse { androidx.compose.ui.text.font.FontFamily.Monospace }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = font.name,
                color = if (isSelected) AccentLight else TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "fn main() { } // 0Oo lI1 != == ->",
                color = TextMuted,
                fontSize = 13.sp,
                fontFamily = previewFontFamily
            )
        }
        if (font.isCustom) {
            IconButton(onClick = onLongClick) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "Delete custom font",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (isSelected) {
            Icon(Lucide.Check, "Selected", tint = AccentLight)
        }
    }
}
