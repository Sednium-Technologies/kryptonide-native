package com.sednium.kryptonide.ui.editor

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

@Composable
fun CodeEditorView(
    file: FileNode,
    theme: AppTheme,
    onContentChange: (String) -> Unit,
    onCursorChange: (line: Int, col: Int) -> Unit,
) {
    val bgColor   = LocalKryptonColors.current.Background.toArgb()
    val fgColor   = LocalKryptonColors.current.OnBackground.toArgb()
    val muteColor = LocalKryptonColors.current.Muted.toArgb()

    key(file.id) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKryptonColors.current.Background),
            factory = { context ->
                val vScroll = ScrollView(context).apply {
                    isFillViewport = true
                    isVerticalScrollBarEnabled = true
                }
                val wrapper = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val editor = EditText(context).apply {
                    setText(file.content ?: "")
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    setTextColor(fgColor)
                    setBackgroundColor(bgColor)
                    setHintTextColor(muteColor)
                    setPadding(32, 32, 32, 32)
                    isSingleLine = false
                    maxLines = Int.MAX_VALUE
                    tag = "code_editor"
                }
                editor.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        onContentChange(s?.toString() ?: "")
                        val selStart = editor.selectionStart
                        val text = editor.text.toString()
                        val textBefore = text.substring(0, minOf(selStart, text.length))
                        val line = textBefore.count { it == '\n' } + 1
                        val lastNewline = textBefore.lastIndexOf('\n')
                        val col = if (lastNewline < 0) selStart + 1 else selStart - lastNewline
                        onCursorChange(line, col)
                    }
                })
                wrapper.addView(editor, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                vScroll.addView(wrapper, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ))
                vScroll
            },
            update = { vScroll ->
                val editor = vScroll.findViewWithTag<EditText>("code_editor") ?: return@AndroidView
                editor.setTextColor(fgColor)
                editor.setBackgroundColor(bgColor)
                val current = editor.text.toString()
                if (current != (file.content ?: "")) {
                    editor.setText(file.content ?: "")
                }
            },
        )
    }
}

@Composable
fun WelcomeScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(LocalKryptonColors.current.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("⚡", fontSize = 48.sp)
            Text("Krypton IDE", color = LocalKryptonColors.current.OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Open a file from the explorer", color = LocalKryptonColors.current.Muted, fontSize = 14.sp)
        }
    }
}
