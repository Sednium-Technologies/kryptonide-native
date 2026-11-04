package com.sednium.kryptonide.agent.oorty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * High-definition vector emblem and raster loader for Oorty Lite.
 */
object OortyLiteIcons {

    val Emblem: ImageVector by lazy {
        ImageVector.Builder(
            name = "OortyLiteEmblem",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // Stylized central diamond nexus
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF6366F1)),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                lineTo(22f, 12f)
                lineTo(12f, 22f)
                lineTo(2f, 12f)
                close()
            }
            // Inner core ring
            path(
                fill = null,
                stroke = SolidColor(Color(0xFFA855F7)),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 7f)
                lineTo(17f, 12f)
                lineTo(12f, 17f)
                lineTo(7f, 12f)
                close()
            }
            // Central neural pulse dot
            path(
                fill = SolidColor(Color(0xFF38BDF8))
            ) {
                moveTo(12f, 10.5f)
                lineTo(13.5f, 12f)
                lineTo(12f, 13.5f)
                lineTo(10.5f, 12f)
                close()
            }
        }.build()
    }
}

@Composable
fun OortyLiteEmblemCanvas(
    modifier: Modifier = Modifier,
    primaryColor: Color = Color(0xFF6366F1),
    secondaryColor: Color = Color(0xFFA855F7),
    accentColor: Color = Color(0xFF38BDF8)
) {
    val gradient = remember(primaryColor, secondaryColor) {
        Brush.linearGradient(listOf(primaryColor, secondaryColor))
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val w = size.width
            val h = size.height

            // Outer polygon
            val outerPath = Path().apply {
                moveTo(w * 0.5f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.5f, h * 0.92f)
                lineTo(w * 0.08f, h * 0.5f)
                close()
            }
            drawPath(outerPath, brush = gradient, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

            // Inner nexus
            val innerPath = Path().apply {
                moveTo(w * 0.5f, h * 0.28f)
                lineTo(w * 0.72f, h * 0.5f)
                lineTo(w * 0.5f, h * 0.72f)
                lineTo(w * 0.28f, h * 0.5f)
                close()
            }
            drawPath(innerPath, color = secondaryColor, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))

            // Center pulse
            drawCircle(
                color = accentColor,
                radius = 2.dp.toPx(),
                center = Offset(w * 0.5f, h * 0.5f)
            )
        }
    }
}
