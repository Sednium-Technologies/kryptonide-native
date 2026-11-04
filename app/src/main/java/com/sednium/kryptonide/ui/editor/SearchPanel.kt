package com.sednium.kryptonide.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPanel(editorVm: EditorViewModel) {
    val state by editorVm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(state.searchQuery) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("SEARCH", color = LocalKryptonColors.current.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; editorVm.search(it) },
            placeholder = { Text("Search in files…", color = LocalKryptonColors.current.Muted, fontSize = 13.sp) },
            leadingIcon = { Icon(Lucide.Search, null, tint = LocalKryptonColors.current.Muted, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = LocalKryptonColors.current.Primary,
                unfocusedBorderColor = LocalKryptonColors.current.Border,
                textColor = LocalKryptonColors.current.OnBackground,
                cursorColor = LocalKryptonColors.current.Primary,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        )
        Spacer(Modifier.height(8.dp))

        if (query.isNotBlank()) {
            Text("${state.searchResults.size} result${if (state.searchResults.size != 1) "s" else ""}", color = LocalKryptonColors.current.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.searchResults, key = { "${it.fileId}:${it.line}" }) { result ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { editorVm.openFile(result.fileId) }.padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(result.fileName, color = LocalKryptonColors.current.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text(":${result.line}", color = LocalKryptonColors.current.Muted, fontSize = 11.sp)
                    }
                    Text(result.snippet.take(80), color = LocalKryptonColors.current.Muted, fontSize = 11.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
