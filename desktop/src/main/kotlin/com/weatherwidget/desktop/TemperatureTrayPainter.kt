package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
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
    private val useCelsius: Boolean = false,
) : Painter() {
    override val intrinsicSize: Size = Size(24f, 24f)

    override fun DrawScope.onDraw() {
        drawRect(color = Color.Transparent, size = size, blendMode = BlendMode.Clear)

        val tempText = temperature?.let { formatTrayTemperature(it, useCelsius) } ?: "--"

        val textColor = trayTempToColor(temperature ?: 70f)
        val textWeight = FontWeight.Bold

        val fontSize = resolveFontSize(tempText, textColor)
        val textLayout = textMeasurer.measure(
            text = tempText,
            style = TextStyle(
                color = textColor,
                fontSize = fontSize,
                fontWeight = textWeight,
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
        val maxWidth = size.width * 0.9f
        val maxHeight = size.height * 0.9f
        val candidates = listOf(22, 20, 18, 16, 14, 12, 10, 8)
        for (candidate in candidates) {
            val layout = textMeasurer.measure(
                text = text,
                style = TextStyle(
                    color = color,
                    fontSize = candidate.sp,
                    fontWeight = FontWeight.Bold,
                )
            )
            if (layout.size.width <= maxWidth && layout.size.height <= maxHeight) {
                return candidate.sp
            }
        }
        return candidates.last().sp
    }

}

/** Temp→color tuned for text legibility on dark backgrounds (tray icon, observations list). */
internal fun trayTempToColor(temp: Float): Color {
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

internal fun formatTrayTemperature(temperature: Float, useCelsius: Boolean = false): String {
    val displayVal = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(temperature) else temperature
    return String.format(Locale.US, "%.1f", displayVal)
}
