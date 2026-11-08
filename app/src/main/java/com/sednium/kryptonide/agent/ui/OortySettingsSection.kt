package com.sednium.kryptonide.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Sparkles
import com.sednium.kryptonide.agent.oorty.OortyLiteEmblemCanvas
import com.sednium.kryptonide.agent.oorty.SystemPrompt
import com.sednium.kryptonide.agent.pocket.ExecutionTier
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

@Composable
fun OortySettingsSection(
    currentTier: ExecutionTier,
    onTierChanged: (ExecutionTier) -> Unit,
    onOpenPocketServer: () -> Unit
) {
    val colors = LocalKryptonColors.current
    val context = LocalContext.current
    var showPromptModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 1. Upstream Attribution Card ───────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SystemPrompt.OORTY_UPSTREAM_URL))
                    context.startActivity(intent)
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = colors.Surface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.Primary.copy(alpha = 0.4f)))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OortyLiteEmblemCanvas(modifier = Modifier.size(42.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Oorty Lite",
                            color = colors.OnBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.Primary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("v3.14", color = colors.Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "Distilled from Oorty v3.14 for Kryptonide",
                        color = colors.Muted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Sednium-Technologies/OORTY",
                        color = colors.Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    Lucide.ExternalLink,
                    contentDescription = "Open GitHub",
                    tint = colors.Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── 2. Pocket Server Quick Access ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.Surface)
                .border(0.8.dp, colors.Border, RoundedCornerShape(12.dp))
                .clickable { onOpenPocketServer() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.Primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Server, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text("Pocket Server & Local Models", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Run GGUF, LiteRT, and Laya System 1 locally", color = colors.Muted, fontSize = 11.sp)
                }
            }
            Button(
                onClick = onOpenPocketServer,
                colors = ButtonDefaults.buttonColors(containerColor = colors.SurfaceVar),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Manage", color = colors.OnBackground, fontSize = 11.sp)
            }
        }

        // ── 3. Execution Tier Selector ─────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.Surface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.Border))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Lucide.Shield, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(16.dp))
                    Text("Agent Execution Tier", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExecutionTier.values().forEach { tier ->
                        val isSelected = currentTier == tier
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) colors.Primary else colors.SurfaceVar)
                                .clickable { onTierChanged(tier) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tier.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = if (isSelected) colors.OnPrimary else colors.OnBackground,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // ── 4. Immutable System Prompt Viewer ──────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.Surface)
                .border(0.8.dp, colors.Border, RoundedCornerShape(12.dp))
                .clickable { showPromptModal = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.SurfaceVar),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Lock, contentDescription = null, tint = colors.Muted, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text("Oorty Lite System Directives", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Immutable deterministic protocol (.ai, AIF, tags)", color = colors.Muted, fontSize = 11.sp)
                }
            }
            Text("View", color = colors.Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (showPromptModal) {
        AlertDialog(
            onDismissRequest = { showPromptModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Lucide.Lock, null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                    Text("Locked System Directives", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "The system prompt is locked to ensure deterministic tag protocols (<thinking>, <plan>, <ans>, <edit>, <aif_note>) and safe workspace invariants.",
                        fontSize = 11.sp,
                        color = colors.Muted
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.Background)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = SystemPrompt.buildImmutablePrompt(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = colors.OnBackground,
                            lineHeight = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPromptModal = false }) {
                    Text("Close", color = colors.Primary)
                }
            },
            containerColor = colors.Surface
        )
    }
}
