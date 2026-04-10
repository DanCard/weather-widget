package com.weatherwidget.widget

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category


@Category(ShortDuration::class)
class TemperatureGraphStyleTest {

    // Tests for tempToY
    // Note: Y=0 is top of screen. Higher Y = lower on screen (colder).
    // minTemp -> graphBottom (higher Y value), maxTemp -> graphTop (lower Y value)

    @Test
    fun tempToY_atMinTemp_returnsGraphBottom() {
        val result = TemperatureGraphStyle.tempToY(50f, 100f, 200f, 50f, 40f)
        assertEquals(300f, result, 0.01f)
    }

    @Test
    fun tempToY_atMaxTemp_returnsGraphTop() {
        val result = TemperatureGraphStyle.tempToY(90f, 100f, 200f, 50f, 40f)
        assertEquals(100f, result, 0.01f)
    }

    @Test
    fun tempToY_atMidTemp_returnsMidpoint() {
        val result = TemperatureGraphStyle.tempToY(70f, 100f, 200f, 50f, 40f)
        assertEquals(200f, result, 0.01f)
    }

    @Test
    fun tempToY_atQuarterRange_returnsCorrectY() {
        val result = TemperatureGraphStyle.tempToY(60f, 100f, 200f, 50f, 40f)
        assertEquals(250f, result, 0.01f)
    }

    @Test
    fun tempToY_atThreeQuarterRange_returnsCorrectY() {
        val result = TemperatureGraphStyle.tempToY(80f, 100f, 200f, 50f, 40f)
        assertEquals(150f, result, 0.01f)
    }

    @Test
    fun tempToY_invertedTempRange_handlesNegativeRange() {
        val result = TemperatureGraphStyle.tempToY(70f, 100f, 200f, 90f, -40f)
        assertEquals(200f, result, 0.01f)
    }

    // Tests for tempToColor

    @Test
    fun tempToColor_atFreezing_returnsColdColor() {
        val result = TemperatureGraphStyle.tempToColor(32f)
        val expected = Color.parseColor("#5AC8FA")
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_atColdThreshold_returnsColdColor() {
        val result = TemperatureGraphStyle.tempToColor(50f)
        val expected = Color.parseColor("#5AC8FA")
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_atMildThreshold_returnsMildColor() {
        val result = TemperatureGraphStyle.tempToColor(70f)
        val expected = Color.parseColor("#E8A24E")
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_atHotThreshold_returnsHotColor() {
        val result = TemperatureGraphStyle.tempToColor(90f)
        val expected = Color.parseColor("#FF6B35")
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_aboveHotThreshold_returnsHotColor() {
        val result = TemperatureGraphStyle.tempToColor(100f)
        val expected = Color.parseColor("#FF6B35")
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_betweenColdAndMild_returnsBlendedColor() {
        val result = TemperatureGraphStyle.tempToColor(60f)
        val cold = Color.parseColor("#5AC8FA")
        val mild = Color.parseColor("#E8A24E")
        val expected = blendColors(cold, mild, 0.5f)
        assertEquals(expected, result)
    }

    @Test
    fun tempToColor_betweenMildAndHot_returnsBlendedColor() {
        val result = TemperatureGraphStyle.tempToColor(80f)
        val mild = Color.parseColor("#E8A24E")
        val hot = Color.parseColor("#FF6B35")
        val expected = blendColors(mild, hot, 0.5f)
        assertEquals(expected, result)
    }

    // Tests for formatTemp

    @Test
    fun formatTemp_wholeNumber_returnsWithoutDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70f)
        assertEquals("70", result)
    }

    @Test
    fun formatTemp_singleDecimal_returnsWithOneDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70.5f)
        assertEquals("70.5", result)
    }

    @Test
    fun formatTemp_twoDecimals_roundsToOneDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70.55f)
        assertEquals("70.6", result)
    }

    @Test
    fun formatTemp_negativeTemperature_handlesNegatives() {
        val result = TemperatureGraphStyle.formatTemp(-10f)
        assertEquals("-10", result)
    }

    // Tests for formatAgeLabel

    @Test
    fun formatAgeLabel_zeroMinutes_returnsZeroMinutes() {
        val result = TemperatureGraphStyle.formatAgeLabel(0, 6)
        assertEquals("0m", result)
    }

    @Test
    fun formatAgeLabel_negativeMinutes_returnsNull() {
        val result = TemperatureGraphStyle.formatAgeLabel(-5, 6)
        assertEquals(null, result)
    }

    @Test
    fun formatAgeLabel_underOneHour_returnsMinutes() {
        val result = TemperatureGraphStyle.formatAgeLabel(30, 6)
        assertEquals("30m", result)
    }

    @Test
    fun formatAgeLabel_exactlyOneHour_returnsHours() {
        val result = TemperatureGraphStyle.formatAgeLabel(60, 6)
        assertEquals("1h", result)
    }

    @Test
    fun formatAgeLabel_oneHourThirty_returnsHoursAndMinutes() {
        val result = TemperatureGraphStyle.formatAgeLabel(90, 6)
        assertEquals("1h 30m", result)
    }

    @Test
    fun formatAgeLabel_multipleHours_returnsHours() {
        val result = TemperatureGraphStyle.formatAgeLabel(180, 6)
        assertEquals("3h", result)
    }

    @Test
    fun formatAgeLabel_over12HoursSpan_returnsNull() {
        val result = TemperatureGraphStyle.formatAgeLabel(60, 24)
        assertEquals(null, result)
    }

    @Test
    fun formatAgeLabel_exactly12HoursSpan_returnsLabel() {
        val result = TemperatureGraphStyle.formatAgeLabel(60, 12)
        assertEquals("1h", result)
    }

    companion object {
        private fun blendColors(c1: Int, c2: Int, fraction: Float): Int {
            val f = fraction.coerceIn(0f, 1f)
            val r = (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt()
            val g = (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt()
            val b = (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
            return Color.rgb(r, g, b)
        }
    }
}