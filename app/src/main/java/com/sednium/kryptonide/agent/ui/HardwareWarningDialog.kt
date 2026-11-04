package com.sednium.kryptonide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Thermometer
import com.sednium.kryptonide.agent.pocket.LocalModelSpec
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

@Composable
fun HardwareWarningDialog(
    modelSpec: LocalModelSpec,
    availableRamBytes: Long,
    isThermalThrottling: Boolean,
    batteryTempCelsius: Float,
    onDismiss: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    val colors = LocalKryptonColors.current
    val availMb = availableRamBytes / (1024 * 1024)
    val reqMb = modelSpec.minRamBytesRequired / (1024 * 1024)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.Surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.Warning.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.TriangleAlert, contentDescription = null, tint = colors.Warning, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(
                            text = "Hardware Capacity Advisory",
                            color = colors.OnBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Potential performance or OOM bottleneck",
                            color = colors.Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (availableRamBytes < modelSpec.minRamBytesRequired) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.SurfaceVar.copy(alpha = 0.6f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Lucide.Cpu, null, tint = colors.Error, modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                "Low Available RAM",
                                color = colors.OnBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Available: ${availMb}MB | Model Target: ${reqMb}MB.\nRunning without sufficient RAM may trigger Android Low Memory Killer (LMK).",
                                color = colors.Muted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (isThermalThrottling) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.SurfaceVar.copy(alpha = 0.6f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Lucide.Thermometer, null, tint = colors.Warning, modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                "Thermal Throttling Active",
                                color = colors.OnBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Battery/SoC temp is ${String.format("%.1f", batteryTempCelsius)}°C. High token loads may cause CPU core down-clocking.",
                                color = colors.Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    text = "Recommendation: Pin to Laya System 1 or Qwen 1.5B for optimal battery life and zero thermal lag on mobile.",
                    color = colors.Muted,
                    fontSize = 11.sp
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = colors.Muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onProceedAnyway,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Proceed Anyway", color = colors.OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
