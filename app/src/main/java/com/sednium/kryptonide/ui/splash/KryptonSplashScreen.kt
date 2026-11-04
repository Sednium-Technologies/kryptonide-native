package com.sednium.kryptonide.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sednium.kryptonide.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHOW_MS = 2000L
private const val FADE_MS = 500L

/**
 * Kryptonide Boot & Route Splash Screen.
 * 120Hz Hardware-Accelerated, zero-recomposition fluid implementation:
 * - Dual stroke-animated circular rings (#7C9B79, #7391AD) with continuous GPU-composited orbital flow
 * - Soft luminous ambient glow halo with cached radial gradient
 * - Centered app icon with smooth spring entrance and breathing transform
 * - Monospace letter-spaced wordmark "KRYPTONIDE" with GPU translation fade
 * - 60/120fps hardware-accelerated exit transition
 */
@Composable
fun KryptonSplashScreen(
    isDark: Boolean = true,
    statusText: String? = null,
    minDurationMs: Long = SHOW_MS,
    onFinished: () -> Unit
) {
    var phase by remember { mutableStateOf("on") } // "on" -> "out" -> "off"

    // Colors matching CSS theme:
    // Dark:  background: #161315, text: #F3F0E7
    // Light: background: #F3F0E7, text: #2A2529
    val backgroundColor = if (isDark) Color(0xFF161315) else Color(0xFFF3F0E7)
    val textColor = if (isDark) Color(0xFFF3F0E7) else Color(0xFF2A2529)
    val ringOuterColor = Color(0xFF7C9B79)
    val ringInnerColor = Color(0xFF7391AD)

    // Master exit transition (read in graphicsLayer for 0 recomposition)
    val exitCubic = remember { CubicBezierEasing(0.16f, 1f, 0.3f, 1f) }
    val splashAlpha = animateFloatAsState(
        targetValue = if (phase == "out") 0f else 1f,
        animationSpec = tween(FADE_MS.toInt(), easing = exitCubic),
        label = "splashAlpha"
    )
    val splashScale = animateFloatAsState(
        targetValue = if (phase == "out") 1.03f else 1f,
        animationSpec = tween(FADE_MS.toInt(), easing = exitCubic),
        label = "splashScale"
    )

    // Ring stroke animations (0 -> 1 during entrance)
    val outerRingProgress = remember { Animatable(0f) }
    val innerRingProgress = remember { Animatable(0f) }

    // Icon entrance: scale from 0.45, rotation from -12deg, alpha from 0
    val iconAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.45f) }
    val iconRotation = remember { Animatable(-12f) }

    // Wordmark fade & drift
    val wordAlpha = remember { Animatable(0f) }

    // Pre-allocated static brushes (ZERO allocations on draw loop)
    val glowBrush = remember(ringOuterColor, ringInnerColor) {
        Brush.radialGradient(
            colors = listOf(
                ringOuterColor.copy(alpha = 0.30f),
                ringInnerColor.copy(alpha = 0.15f),
                Color.Transparent
            )
        )
    }

    val outerRingBrush = remember(ringOuterColor) {
        Brush.sweepGradient(
            colors = listOf(
                ringOuterColor.copy(alpha = 0.20f),
                ringOuterColor.copy(alpha = 0.90f),
                ringOuterColor,
                ringOuterColor.copy(alpha = 0.35f)
            )
        )
    }

    val innerRingBrush = remember(ringInnerColor) {
        Brush.sweepGradient(
            colors = listOf(
                ringInnerColor.copy(alpha = 0.85f),
                ringInnerColor.copy(alpha = 0.25f),
                ringInnerColor.copy(alpha = 0.75f),
                ringInnerColor.copy(alpha = 0.20f)
            )
        )
    }

    // Continuous GPU-rendered ambient motion:
    val infiniteTransition = rememberInfiniteTransition(label = "ambientMotion")

    val outerOrbitRotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerOrbit"
    )

    val innerOrbitRotation = infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "innerOrbit"
    )

    val breathScale = infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    val glowAlpha = infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    var canBreathe by remember { mutableStateOf(false) }

    // Orchestrate entrance and lifecycle
    LaunchedEffect(Unit) {
        // Outer ring: starts at 100ms, duration 1100ms
        launch {
            delay(100L)
            outerRingProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(1100, easing = exitCubic)
            )
        }

        // Inner ring: starts at 280ms, duration 1100ms
        launch {
            delay(280L)
            innerRingProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(1100, easing = exitCubic)
            )
        }

        // Center icon entrance
        launch {
            iconAlpha.animateTo(1f, tween(300, easing = LinearOutSlowInEasing))
        }
        launch {
            iconScale.animateTo(1f, tween(900, easing = exitCubic))
            canBreathe = true
        }
        launch {
            iconRotation.animateTo(0f, tween(900, easing = exitCubic))
        }

        // Wordmark entrance: starts at 450ms, duration 750ms
        launch {
            delay(450L)
            wordAlpha.animateTo(0.9f, tween(750, easing = exitCubic))
        }

        // Minimum duration before fade-out
        delay(minDurationMs)
        phase = "out"
        delay(FADE_MS)
        phase = "off"
        onFinished()
    }

    if (phase == "off") return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = splashAlpha.value
                scaleX = splashScale.value
                scaleY = splashScale.value
            }
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Mark (124dp x 124dp) containing ambient glow, dual orbital rings, and center icon
            Box(
                modifier = Modifier.size(124.dp),
                contentAlignment = Alignment.Center
            ) {
                // 1. Soft Ambient Luminous Halo Glow (GPU scaled and faded)
                Canvas(
                    modifier = Modifier
                        .size(124.dp)
                        .graphicsLayer {
                            alpha = glowAlpha.value
                            val b = if (canBreathe) breathScale.value else 1f
                            scaleX = b
                            scaleY = b
                        }
                ) {
                    drawCircle(brush = glowBrush)
                }

                // 2. Outer Ring: GPU hardware rotated with luminous sweep gradient
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = outerOrbitRotation.value
                        }
                ) {
                    val progress = outerRingProgress.value
                    if (progress > 0f) {
                        val strokeOuter = 2.4.dp.toPx()
                        val rOuter = size.minDimension * (56f / 120f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val outerTopLeft = Offset(center.x - rOuter, center.y - rOuter)
                        val outerSize = Size(rOuter * 2f, rOuter * 2f)

                        drawArc(
                            brush = outerRingBrush,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = outerTopLeft,
                            size = outerSize,
                            style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
                        )
                    }
                }

                // 3. Inner Ring: GPU hardware counter-rotated
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = innerOrbitRotation.value
                        }
                ) {
                    val progress = innerRingProgress.value
                    if (progress > 0f) {
                        val strokeInner = 1.6.dp.toPx()
                        val rInner = size.minDimension * (48f / 120f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val innerTopLeft = Offset(center.x - rInner, center.y - rInner)
                        val innerSize = Size(rInner * 2f, rInner * 2f)

                        drawArc(
                            brush = innerRingBrush,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = innerTopLeft,
                            size = innerSize,
                            style = Stroke(width = strokeInner, cap = StrokeCap.Round)
                        )
                    }
                }

                // 4. Center App Icon (68dp x 68dp circle) with smooth breathing & entrance
                Image(
                    painter = painterResource(id = R.drawable.icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .graphicsLayer {
                            alpha = iconAlpha.value
                            val b = if (canBreathe) breathScale.value else 1f
                            scaleX = iconScale.value * b
                            scaleY = iconScale.value * b
                            rotationZ = iconRotation.value
                        }
                )
            }

            Spacer(Modifier.height(26.dp))

            // 5. Monospace Wordmark "KRYPTONIDE" with GPU drift and fade
            Text(
                text = "KRYPTONIDE",
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.6.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = wordAlpha.value
                    translationY = (1f - wordAlpha.value) * 6.dp.toPx()
                    scaleX = 0.95f + (0.05f * wordAlpha.value)
                    scaleY = 0.95f + (0.05f * wordAlpha.value)
                }
            )

            // 6. Optional Status Subtitle
            if (!statusText.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = wordAlpha.value * 0.7f
                        translationY = (1f - wordAlpha.value) * 4.dp.toPx()
                    }
                )
            }
        }
    }
}
