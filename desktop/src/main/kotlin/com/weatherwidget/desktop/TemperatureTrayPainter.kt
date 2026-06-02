package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders the current temperature as a system tray and window icon.
 * Prioritizes legibility and data over aesthetics.
 */
class TemperatureTrayPainter(
    private val temperature: Float?,
    private val textMeasurer: TextMeasurer,
) : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val tempText = temperature?.let { formatTrayTemperature(it) } ?: "--"
        
        val textColor = tempToColor(temperature ?: 70f)

        val fontSize = resolveFontSize(tempText, textColor)
        val textLayout = textMeasurer.measure(
            text = tempText,
            style = TextStyle(
                color = textColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Black // Extra bold for tray visibility
            )
        )

        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(
                x = center.x - textLayout.size.width / 2f,
                y = center.y - textLayout.size.height / 2f
            )
        )
    }

    private fun DrawScope.resolveFontSize(text: String, color: Color): androidx.compose.ui.unit.TextUnit {
        val maxWidth = size.width * 0.95f
        val maxHeight = size.height * 0.95f
        val candidates = listOf(42, 38, 34, 30, 26, 22, 18, 15, 12)
        for (candidate in candidates) {
            val layout = textMeasurer.measure(
                text = text,
                style = TextStyle(
                    color = color,
                    fontSize = candidate.sp,
                    fontWeight = FontWeight.Black
                )
            )
            if (layout.size.width <= maxWidth && layout.size.height <= maxHeight) {
                return candidate.sp
            }
        }
        return candidates.last().sp
    }

    private fun tempToColor(temp: Float): Color {
        val colorCold = Color(0xFF007AFF) // Deeper blue for better contrast
        val colorMild = Color(0xFFE8A24E)
        val colorHot = Color(0xFFFF3B30)  // Vivid red

        return when {
            temp <= 50f -> colorCold
            temp >= 90f -> colorHot
            temp <= 70f -> lerp(colorCold, colorMild, (temp - 50f) / 20f)
            else -> lerp(colorMild, colorHot, (temp - 70f) / 20f)
        }
    }
}

internal fun formatTrayTemperature(temperature: Float): String =
    temperature.roundToInt().toString()
