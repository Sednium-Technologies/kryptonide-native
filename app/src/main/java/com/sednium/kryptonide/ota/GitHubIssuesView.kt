package com.sednium.kryptonide.ota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.launch

/**
 * Live GitHub Issues list view for Sednium-Technologies/kryptonide-native.
 * Allows filtering, searching, tapping to view on GitHub, and raising new issues.
 */
@Composable
fun GitHubIssuesView(
    githubToken: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalKryptonColors.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var issues by remember { mutableStateOf<List<IssueItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var filterState by remember { mutableStateOf("ALL") } // "ALL", "OPEN", "CLOSED"
    var showRaiseSheet by remember { mutableStateOf(false) }

    fun refreshIssues() {
        isLoading = true
        scope.launch {
            issues = GitHubIssuesManager.fetchIssues(githubToken)
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshIssues()
    }

    val filteredIssues = remember(issues, searchQuery, filterState) {
        issues.filter { issue ->
            val matchesFilter = when (filterState) {
                "OPEN" -> issue.state.equals("open", ignoreCase = true)
                "CLOSED" -> issue.state.equals("closed", ignoreCase = true)
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                    issue.title.contains(searchQuery, ignoreCase = true) ||
                    "#${issue.number}".contains(searchQuery) ||
                    issue.author.contains(searchQuery, ignoreCase = true)

            matchesFilter && matchesSearch
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Controls Row: Filter pills & + Raise Issue button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "OPEN", "CLOSED").forEach { f ->
                    val isSelected = filterState == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) colors.Primary else colors.SurfaceVar)
                            .clickable { filterState = f }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            f,
                            color = if (isSelected) colors.OnPrimary else colors.Muted,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Button(
                onClick = { showRaiseSheet = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.Primary,
                    contentColor = colors.OnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Icon(Lucide.Plus, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Raise Issue", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search issues by title or #number…", color = colors.Muted, fontSize = 12.sp) },
            leadingIcon = { Icon(Lucide.Search, null, tint = colors.Muted, modifier = Modifier.size(15.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                        Icon(Lucide.X, null, tint = colors.Muted, modifier = Modifier.size(13.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = colors.OnSurface,
                focusedBorderColor = colors.Primary,
                unfocusedBorderColor = colors.Border
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )

        // Issue List
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.Primary, modifier = Modifier.size(28.dp))
            }
        } else if (filteredIssues.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Lucide.CircleDot, null, tint = colors.Muted, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    if (searchQuery.isNotBlank()) {
                        Text(
                            "No issues match \"$searchQuery\"",
                            color = colors.Muted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { refreshIssues() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Lucide.RefreshCw, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            text = "No open issues yet! 🎉",
                            color = colors.OnSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Found a bug or have a feature request for KryptonIDE v1.0? Tap '+ Raise Issue' above to submit one directly.",
                            color = colors.Muted,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { refreshIssues() },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Lucide.RefreshCw, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showRaiseSheet = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Lucide.Plus, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("+ Raise First Issue", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredIssues, key = { it.number }) { issue ->
                    IssueCard(
                        issue = issue,
                        onClick = { uriHandler.openUri(issue.htmlUrl) }
                    )
                }
            }
        }
    }

    if (showRaiseSheet) {
        RaiseIssueSheet(
            githubToken = githubToken,
            onDismiss = { showRaiseSheet = false },
            onIssueCreated = { newIssue ->
                issues = listOf(newIssue) + issues
            }
        )
    }
}

@Composable
private fun IssueCard(
    issue: IssueItem,
    onClick: () -> Unit
) {
    val colors = LocalKryptonColors.current
    val isOpen = issue.state.equals("open", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.Surface)
            .border(0.8.dp, colors.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            ) {
                // State pill (Open = green, Closed = purple)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isOpen) colors.Success.copy(alpha = 0.15f) else Color(0xFF8250DF).copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (isOpen) "OPEN" else "CLOSED",
                        color = if (isOpen) colors.Success else Color(0xFFA371F7),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    "#${issue.number}",
                    color = colors.Muted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    issue.title,
                    color = colors.OnSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(Lucide.ExternalLink, null, tint = colors.Muted, modifier = Modifier.size(13.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                issue.labels.take(3).forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.SurfaceVar)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(label, color = colors.Muted, fontSize = 9.sp)
                    }
                }
            }

            Text(
                "by ${issue.author} • ${if (issue.comments > 0) "${issue.comments} comments" else "no comments"}",
                color = colors.Muted,
                fontSize = 10.sp
            )
        }
    }
}
