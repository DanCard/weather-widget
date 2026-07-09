package com.weatherwidget.widget

import com.weatherwidget.shared.graph.TemperatureColorModel
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

    // tempToColor now delegates to the shared TemperatureColorModel (pure Kotlin bit math). These
    // assert it forwards correctly; expected values come from the same shared model, which — unlike
    // the old android.graphics.Color stub (every method returns 0) — yields real colors under
    // plain JUnit. See TemperatureColorModelTest for the underlying blend math.

    @Test
    fun tempToColor_atFreezing_returnsColdColor() {
        assertEquals(TemperatureColorModel.COLOR_COLD, TemperatureGraphStyle.tempToColor(32f))
    }

    @Test
    fun tempToColor_atColdThreshold_returnsColdColor() {
        assertEquals(TemperatureColorModel.COLOR_COLD, TemperatureGraphStyle.tempToColor(50f))
    }

    @Test
    fun tempToColor_atMildThreshold_returnsMildColor() {
        assertEquals(TemperatureColorModel.COLOR_MILD, TemperatureGraphStyle.tempToColor(70f))
    }

    @Test
    fun tempToColor_atHotThreshold_returnsHotColor() {
        assertEquals(TemperatureColorModel.COLOR_HOT, TemperatureGraphStyle.tempToColor(90f))
    }

    @Test
    fun tempToColor_aboveHotThreshold_returnsHotColor() {
        assertEquals(TemperatureColorModel.COLOR_HOT, TemperatureGraphStyle.tempToColor(100f))
    }

    @Test
    fun tempToColor_betweenColdAndMild_returnsBlendedColor() {
        val expected = TemperatureColorModel.blend(TemperatureColorModel.COLOR_COLD, TemperatureColorModel.COLOR_MILD, 0.5f)
        assertEquals(expected, TemperatureGraphStyle.tempToColor(60f))
    }

    @Test
    fun tempToColor_betweenMildAndHot_returnsBlendedColor() {
        val expected = TemperatureColorModel.blend(TemperatureColorModel.COLOR_MILD, TemperatureColorModel.COLOR_HOT, 0.5f)
        assertEquals(expected, TemperatureGraphStyle.tempToColor(80f))
    }

    // Tests for formatTemp

    @Test
    fun formatTemp_wholeNumber_returnsWithoutDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70f, useCelsius = false)
        assertEquals("70", result)
    }

    @Test
    fun formatTemp_singleDecimal_returnsWithOneDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70.5f, useCelsius = false)
        assertEquals("70.5", result)
    }

    @Test
    fun formatTemp_twoDecimals_roundsToOneDecimal() {
        val result = TemperatureGraphStyle.formatTemp(70.55f, useCelsius = false)
        assertEquals("70.6", result)
    }

    @Test
    fun formatTemp_negativeTemperature_handlesNegatives() {
        val result = TemperatureGraphStyle.formatTemp(-10f, useCelsius = false)
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

}