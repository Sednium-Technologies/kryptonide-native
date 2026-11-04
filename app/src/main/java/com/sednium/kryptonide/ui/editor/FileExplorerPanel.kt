@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.sednium.kryptonide.ui.editor

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.composables.icons.lucide.*
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

data class FlatNode(
    val node: FileNode,
    val depth: Int,
    val isExpanded: Boolean = false,
    val isLastSibling: Boolean = false
)

fun flattenRealTree(
    allNodes: List<FileNode>,
    parentId: String?,
    expanded: Set<String>,
    depth: Int,
    searchQuery: String = ""
): List<FlatNode> {
    val result = mutableListOf<FlatNode>()
    val children = allNodes
        .filter { it.parentId == parentId && !it.name.startsWith(".metadata") && it.name != ".DS_Store" }
        .sortedWith(compareBy({ it.type == FileType.FILE }, { it.name.lowercase() }))

    val count = children.size
    for ((index, child) in children.withIndex()) {
        val matchesSearch = searchQuery.isBlank() || child.name.contains(searchQuery, ignoreCase = true)
        val isExp = expanded.contains(child.id) || searchQuery.isNotBlank()
        val isLast = index == count - 1
        val childNodes = if (child.type == FileType.FOLDER && isExp) {
            flattenRealTree(allNodes, child.id, expanded, depth + 1, searchQuery)
        } else emptyList()

        if (matchesSearch || childNodes.isNotEmpty()) {
            result.add(FlatNode(child, depth, isExp, isLast))
            result.addAll(childNodes)
        }
    }
    return result
}

@Composable
fun FileExplorerPanel(
    nodes: List<FileNode>,
    activeFileId: String?,
    onFileSelect: (FileNode) -> Unit,
    onCreateNode: (parentId: String?, name: String, isFolder: Boolean) -> Unit,
    onDeleteNode: (FileNode) -> Unit,
    onRenameNode: (FileNode, newName: String) -> Unit = { _, _ -> },
    onMoveNode: (FileNode, newParentId: String?) -> Unit = { _, _ -> },
    onCopyNode: (FileNode, newParentId: String?) -> Unit = { _, _ -> },
    pathFor: (FileNode) -> String = { it.name },
    onSendToAi: (FileNode) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val colors = LocalKryptonColors.current

    var expandedFolders by remember { mutableStateOf(setOf<String>()) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Dialog / Menu States
    var showCreateDialog by remember { mutableStateOf<String?>(null) } // "file" or "folder"
    var createName by remember { mutableStateOf("") }

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuNode by remember { mutableStateOf<FileNode?>(null) }

    var showRenameDialog by remember { mutableStateOf<FileNode?>(null) }
    var renameName by remember { mutableStateOf("") }

    var showSizeDialog by remember { mutableStateOf<Pair<FileNode, Long>?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<FileNode?>(null) }

    // Clipboard for Cut/Copy → Paste
    var clipboardNode by remember { mutableStateOf<FileNode?>(null) }
    var clipboardIsCut by remember { mutableStateOf(false) }

    val flatTree = remember(nodes, expandedFolders, searchQuery) {
        flattenRealTree(nodes, null, expandedFolders, 0, searchQuery)
    }

    // Auto-expand path leading to the active file
    LaunchedEffect(activeFileId, nodes) {
        if (activeFileId != null) {
            val toExpand = mutableSetOf<String>()
            val activeNode = nodes.find { it.id == activeFileId }
            var currId = activeNode?.parentId
            while (currId != null) {
                toExpand.add(currId)
                currId = nodes.find { it.id == currId }?.parentId
            }
            if (toExpand.isNotEmpty()) {
                expandedFolders = expandedFolders + toExpand
            }
        }
    }

    val totalFilesCount = remember(nodes) { nodes.count { it.type == FileType.FILE } }
    val allFolderIds = remember(nodes) { nodes.filter { it.type == FileType.FOLDER }.map { it.id }.toSet() }
    val isAllExpanded = remember(allFolderIds, expandedFolders) {
        allFolderIds.isNotEmpty() && expandedFolders.containsAll(allFolderIds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        // ─── TOP TOOLBAR ────────────────────────────────────────────────────────
        Surface(
            color = colors.Surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Header title & item count badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.Primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Lucide.FolderOpen,
                                contentDescription = null,
                                tint = colors.Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "EXPLORER",
                            color = colors.OnBackground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        // Pill badge with file count
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.SurfaceVar)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "$totalFilesCount",
                                color = colors.Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Toolbar Action Icons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Clipboard indicator / paste button
                        if (clipboardNode != null) {
                            IconButton(
                                onClick = {
                                    val toPaste = clipboardNode ?: return@IconButton
                                    val targetFolderId = selectedFolderId?.takeIf { id ->
                                        nodes.any { it.id == id && it.type == FileType.FOLDER }
                                    }
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (clipboardIsCut) {
                                        onMoveNode(toPaste, targetFolderId)
                                        clipboardNode = null
                                    } else {
                                        onCopyNode(toPaste, targetFolderId)
                                    }
                                    if (targetFolderId != null) {
                                        expandedFolders = expandedFolders + targetFolderId
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (clipboardIsCut) "Moved ${toPaste.name}" else "Copied ${toPaste.name}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Lucide.ClipboardPaste,
                                    contentDescription = "Paste",
                                    tint = colors.Primary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Expand / Collapse all toggle
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                expandedFolders = if (isAllExpanded) {
                                    emptySet()
                                } else {
                                    allFolderIds
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isAllExpanded) Lucide.ChevronsUpDown else Lucide.ChevronsUpDown,
                                contentDescription = if (isAllExpanded) "Collapse All" else "Expand All",
                                tint = colors.Muted,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Search Toggle
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Lucide.Search,
                                contentDescription = "Search Files",
                                tint = if (isSearchActive) colors.Primary else colors.Muted,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // New File
                        IconButton(
                            onClick = {
                                showCreateDialog = "file"
                                createName = ""
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Lucide.FilePlus,
                                contentDescription = "New File",
                                tint = colors.Muted,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // New Folder
                        IconButton(
                            onClick = {
                                showCreateDialog = "folder"
                                createName = ""
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Lucide.FolderPlus,
                                contentDescription = "New Folder",
                                tint = colors.Muted,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Close Drawer
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Lucide.X,
                                contentDescription = "Close Explorer",
                                tint = colors.Muted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // ─── SEARCH INPUT (Animated) ──────────────────────────────────
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.Background,
                        border = BorderStroke(0.8.dp, colors.Border)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Lucide.Search,
                                contentDescription = "Search",
                                tint = colors.Muted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                textStyle = TextStyle(
                                    color = colors.OnBackground,
                                    fontSize = 13.sp
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(colors.Primary),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Filter files & folders...",
                                            color = colors.Muted,
                                            fontSize = 13.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Lucide.X,
                                        contentDescription = "Clear",
                                        tint = colors.Muted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
            }
        }

        // ─── CLIPBOARD STATUS BANNER (if node is cut/copied) ────────────────
        if (clipboardNode != null) {
            val clipNode = clipboardNode!!
            Surface(
                color = colors.Primary.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (clipboardIsCut) Lucide.Scissors else Lucide.Copy,
                        contentDescription = null,
                        tint = colors.Primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${if (clipboardIsCut) "Cut" else "Copied"}: ${clipNode.name}",
                        color = colors.OnBackground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    TextButton(
                        onClick = { clipboardNode = null },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Cancel", color = colors.Muted, fontSize = 11.sp)
                    }
                }
            }
            HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
        }

        // ─── DYNAMIC FILE TREE ───────────────────────────────────────────────
        if (flatTree.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colors.SurfaceVar),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (searchQuery.isNotEmpty()) Lucide.SearchX else Lucide.FolderOpen,
                            contentDescription = null,
                            tint = colors.Muted,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (searchQuery.isNotEmpty()) "No matching files" else "No files in project",
                        color = colors.OnBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (searchQuery.isNotEmpty())
                            "Try searching with a different term."
                        else
                            "Create your first file to get started.",
                        color = colors.Muted,
                        fontSize = 12.sp
                    )
                    if (searchQuery.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                showCreateDialog = "file"
                                createName = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Lucide.Plus, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Create File", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
            ) {
                itemsIndexed(flatTree, key = { _, flatNode -> flatNode.node.id }) { _, flatNode ->
                    val node = flatNode.node
                    val isActive = activeFileId == node.id
                    val isSelected = selectedFolderId == node.id
                    val isClipboardTarget = clipboardNode?.id == node.id

                    val indentDepth = flatNode.depth
                    val baseIndent = 12.dp
                    val stepIndent = 16.dp

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isActive -> colors.Primary.copy(alpha = 0.12f)
                                    isSelected -> colors.SurfaceVar.copy(alpha = 0.7f)
                                    isClipboardTarget -> colors.Primary.copy(alpha = 0.06f)
                                    else -> Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (node.type == FileType.FOLDER) {
                                        selectedFolderId = node.id
                                        expandedFolders = if (expandedFolders.contains(node.id)) {
                                            expandedFolders - node.id
                                        } else {
                                            expandedFolders + node.id
                                        }
                                    } else {
                                        selectedFolderId = node.parentId
                                        onFileSelect(node)
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    contextMenuNode = node
                                    showContextMenu = true
                                }
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tree indent guidelines
                            if (indentDepth > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    repeat(indentDepth) {
                                        Box(
                                            modifier = Modifier
                                                .width(stepIndent)
                                                .fillMaxHeight(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(20.dp)
                                                    .background(colors.Border.copy(alpha = 0.5f))
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.width(baseIndent))
                            }

                            // Active indicator pill on left edge
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colors.Primary)
                                )
                                Spacer(Modifier.width(6.dp))
                            }

                            // Folder Chevron or Spacer
                            if (node.type == FileType.FOLDER) {
                                val rotation by animateFloatAsState(
                                    targetValue = if (flatNode.isExpanded) 90f else 0f,
                                    label = "chevron_rotate"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            expandedFolders = if (expandedFolders.contains(node.id)) {
                                                expandedFolders - node.id
                                            } else {
                                                expandedFolders + node.id
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Lucide.ChevronRight,
                                        contentDescription = if (flatNode.isExpanded) "Collapse" else "Expand",
                                        tint = colors.Muted,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .rotate(rotation)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                // Folder icon
                                Icon(
                                    if (flatNode.isExpanded) Lucide.FolderOpen else Lucide.Folder,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA), // Crisp folder blue
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Spacer(Modifier.width(6.dp))
                                val (icon, fileColor) = getFileIconAndColor(node.name)
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = fileColor,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // File / Folder Name
                            Text(
                                text = node.name,
                                color = when {
                                    isActive -> colors.Primary
                                    isSelected -> colors.OnBackground
                                    else -> colors.OnBackground.copy(alpha = 0.9f)
                                },
                                fontSize = 13.5.sp,
                                fontWeight = if (isActive || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )

                            // Unsaved File Indicator (amber dot)
                            if (node.isUnsaved) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(colors.Warning)
                                )
                            }

                            // Folder child item count preview
                            if (node.type == FileType.FOLDER && node.childrenIds.isNotEmpty()) {
                                Text(
                                    text = "${node.childrenIds.size}",
                                    color = colors.Muted.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── CONTEXT MENU (Modern ModalBottomSheet) ──────────────────────────
    if (showContextMenu && contextMenuNode != null) {
        val targetNode = contextMenuNode!!
        val isFolder = targetNode.type == FileType.FOLDER
        val parentFolderForCreation = if (isFolder) targetNode.id else targetNode.parentId

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showContextMenu = false },
            sheetState = sheetState,
            containerColor = colors.Surface,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                // Drag handle bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.Border)
                    )
                }

                // Node Header preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isFolder) {
                            Icon(Lucide.Folder, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                        } else {
                            val (icon, color) = getFileIconAndColor(targetNode.name)
                            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = targetNode.name,
                            color = colors.OnBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isFolder) "Folder" else pathFor(targetNode),
                            color = colors.Muted,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = { showContextMenu = false },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Lucide.X, "Close", tint = colors.Muted, modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(color = colors.Border, thickness = 0.5.dp)

                Spacer(Modifier.height(6.dp))

                // Group 1: Creation
                ContextMenuItem(Lucide.FilePlus, "New File") {
                    showContextMenu = false
                    selectedFolderId = parentFolderForCreation
                    showCreateDialog = "file"
                }
                ContextMenuItem(Lucide.FolderPlus, "New Folder") {
                    showContextMenu = false
                    selectedFolderId = parentFolderForCreation
                    showCreateDialog = "folder"
                }

                // Paste action (if clipboard active)
                if (clipboardNode != null) {
                    ContextMenuItem(Lucide.ClipboardPaste, "Paste Here", tint = colors.Primary) {
                        showContextMenu = false
                        val toPaste = clipboardNode ?: return@ContextMenuItem
                        if (clipboardIsCut) {
                            onMoveNode(toPaste, parentFolderForCreation)
                            clipboardNode = null
                        } else {
                            onCopyNode(toPaste, parentFolderForCreation)
                        }
                        if (parentFolderForCreation != null) {
                            expandedFolders = expandedFolders + parentFolderForCreation
                        }
                        android.widget.Toast.makeText(
                            context,
                            if (clipboardIsCut) "Moved to ${targetNode.name}" else "Copied to ${targetNode.name}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // Group 2: Modifying
                ContextMenuItem(Lucide.Pencil, "Rename") {
                    showContextMenu = false
                    renameName = targetNode.name
                    showRenameDialog = targetNode
                }
                ContextMenuItem(Lucide.Scissors, "Cut") {
                    showContextMenu = false
                    clipboardNode = targetNode
                    clipboardIsCut = true
                    android.widget.Toast.makeText(context, "Cut ${targetNode.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
                ContextMenuItem(Lucide.Copy, "Copy") {
                    showContextMenu = false
                    clipboardNode = targetNode
                    clipboardIsCut = false
                    android.widget.Toast.makeText(context, "Copied ${targetNode.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
                ContextMenuItem(Lucide.Link, "Copy Relative Path") {
                    showContextMenu = false
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(pathFor(targetNode)))
                    android.widget.Toast.makeText(context, "Path copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                ContextMenuItem(Lucide.Info, "Inspect Size") {
                    showContextMenu = false
                    val nodeMap = nodes.associateBy { it.id }
                    showSizeDialog = targetNode to computeNodeSize(targetNode, nodeMap)
                }

                // Group 3: AI Assistant (for files)
                if (targetNode.type == FileType.FILE) {
                    HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    ContextMenuItem(Lucide.Sparkles, "Send to Kryptonide AI", tint = colors.Primary) {
                        showContextMenu = false
                        onSendToAi(targetNode)
                    }
                }

                HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // Group 4: Destructive
                ContextMenuItem(Lucide.Trash2, "Delete", tint = colors.Error) {
                    showContextMenu = false
                    showDeleteConfirmDialog = targetNode
                }
            }
        }
    }

    // ─── CREATE FILE / FOLDER DIALOG ──────────────────────────────────────
    if (showCreateDialog != null) {
        val isFolder = showCreateDialog == "folder"
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = null
                createName = ""
            },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isFolder) Lucide.FolderPlus else Lucide.FilePlus,
                        contentDescription = null,
                        tint = colors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isFolder) "New Folder" else "New File",
                        color = colors.OnBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        if (isFolder) "Enter directory name:" else "Enter file name (e.g. Main.kt, styles.css):",
                        color = colors.Muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        placeholder = {
                            Text(if (isFolder) "components" else "App.kt", color = colors.Muted)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = colors.Background,
                            focusedBorderColor = colors.Primary,
                            unfocusedBorderColor = colors.Border,
                            cursorColor = colors.Primary,
                            textColor = colors.OnBackground
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (createName.isNotBlank()) {
                            val targetParentId = selectedFolderId?.takeIf { id ->
                                nodes.any { it.id == id && it.type == FileType.FOLDER }
                            }
                            onCreateNode(targetParentId, createName.trim(), isFolder)
                            targetParentId?.let { expandedFolders = expandedFolders + it }
                            showCreateDialog = null
                            createName = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.Primary,
                        contentColor = colors.OnPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Create", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = null
                    createName = ""
                }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ─── RENAME DIALOG ────────────────────────────────────────────────────
    if (showRenameDialog != null) {
        val targetNode = showRenameDialog!!
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = null
                renameName = ""
            },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Pencil, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Rename", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                Column {
                    Text("Enter new name for \"${targetNode.name}\":", color = colors.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = renameName,
                        onValueChange = { renameName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = colors.Background,
                            focusedBorderColor = colors.Primary,
                            unfocusedBorderColor = colors.Border,
                            cursorColor = colors.Primary,
                            textColor = colors.OnBackground
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameName.trim()
                        if (trimmed.isNotBlank() && trimmed != targetNode.name) {
                            onRenameNode(targetNode, trimmed)
                        }
                        showRenameDialog = null
                        renameName = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.Primary,
                        contentColor = colors.OnPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Rename", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = null
                    renameName = ""
                }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ─── DELETE CONFIRMATION DIALOG ───────────────────────────────────────
    if (showDeleteConfirmDialog != null) {
        val targetNode = showDeleteConfirmDialog!!
        val isFolder = targetNode.type == FileType.FOLDER
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(16.dp),
            icon = {
                Icon(Lucide.Trash2, null, tint = colors.Error, modifier = Modifier.size(28.dp))
            },
            title = {
                Text(
                    if (isFolder) "Delete Folder?" else "Delete File?",
                    color = colors.OnBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    if (isFolder)
                        "Are you sure you want to delete \"${targetNode.name}\" and all of its contents? This cannot be undone."
                    else
                        "Are you sure you want to delete \"${targetNode.name}\"? This cannot be undone.",
                    color = colors.Muted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (clipboardNode?.id == targetNode.id) clipboardNode = null
                        onDeleteNode(targetNode)
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.Error,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ─── INSPECT SIZE DIALOG ──────────────────────────────────────────────
    if (showSizeDialog != null) {
        val (targetNode, sizeBytes) = showSizeDialog!!
        AlertDialog(
            onDismissRequest = { showSizeDialog = null },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.Info, null, tint = colors.Primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(targetNode.name, color = colors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                Column {
                    Text(
                        text = if (targetNode.type == FileType.FOLDER) "Total directory size:" else "File size:",
                        color = colors.Muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatByteSize(sizeBytes),
                        color = colors.OnBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSizeDialog = null }) {
                    Text("OK", color = colors.Primary, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
fun ContextMenuItem(
    icon: ImageVector,
    text: String,
    tint: Color = LocalKryptonColors.current.OnBackground,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = tint, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Recursively sums content byte-size for a file or a whole folder subtree. */
fun computeNodeSize(node: FileNode, nodeMap: Map<String, FileNode>): Long {
    return if (node.type == FileType.FILE) {
        (node.content ?: "").toByteArray(Charsets.UTF_8).size.toLong()
    } else {
        node.childrenIds.sumOf { childId ->
            nodeMap[childId]?.let { computeNodeSize(it, nodeMap) } ?: 0L
        }
    }
}

fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
fun getFileIconAndColor(fileName: String): Pair<ImageVector, Color> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "java" -> Lucide.FileCode to Color(0xFFA78BFA) // Kotlin/Java Violet
        "xml", "html", "htm" -> Lucide.Code to Color(0xFFFB923C) // HTML/XML Orange
        "css", "scss", "sass", "less" -> Lucide.Code to Color(0xFF38BDF8) // CSS Sky Blue
        "js", "jsx", "ts", "tsx" -> Lucide.FileCode to Color(0xFFFACC15) // JS/TS Amber
        "json" -> Lucide.Braces to Color(0xFFFBBF24) // JSON Gold
        "md", "txt", "rtf" -> Lucide.FileText to Color(0xFF60A5FA) // Markdown/Text Blue
        "py" -> Lucide.FileCode to Color(0xFF34D399) // Python Emerald
        "sh", "bash", "zsh" -> Lucide.Terminal to Color(0xFF4ADE80) // Shell Green
        "rs", "c", "cpp", "cs", "go" -> Lucide.Cpu to Color(0xFF2DD4BF) // Systems Teal
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico" -> Lucide.Image to Color(0xFFA855F7) // Images Purple
        "gradle", "properties", "yaml", "yml", "toml", "ini", "env" -> Lucide.Settings to Color(0xFF94A3B8) // Config Slate
        else -> Lucide.FileText to LocalKryptonColors.current.Muted
    }
}
