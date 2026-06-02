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
        val tempValue = temperature?.roundToInt()
        val tempText = tempValue?.toString() ?: "—"
        
        // Background color based on temperature range
        val bgColor = tempToColor(temperature ?: 70f)
        
        // Draw a slightly rounded square background for maximum surface area
        drawRect(
            color = bgColor,
            size = size
        )

        // Draw a small indicator dot if we have real data
        if (temperature != null) {
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = size.minDimension * 0.05f,
                center = Offset(size.width * 0.85f, size.height * 0.15f)
            )
        }

        // Maximize text size for legibility in small tray areas
        val fontSize = if (tempText.length > 2) 34.sp else 40.sp
        val textLayout = textMeasurer.measure(
            text = tempText,
            style = TextStyle(
                color = Color.White,
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
