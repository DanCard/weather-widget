package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TemperatureColorModelTest {

    @Test
    fun `at or below cold threshold returns cold color`() {
        assertEquals(TemperatureColorModel.COLOR_COLD, TemperatureColorModel.tempToColorArgb(50f))
        assertEquals(TemperatureColorModel.COLOR_COLD, TemperatureColorModel.tempToColorArgb(20f))
    }

    @Test
    fun `at or above hot threshold returns hot color`() {
        assertEquals(TemperatureColorModel.COLOR_HOT, TemperatureColorModel.tempToColorArgb(90f))
        assertEquals(TemperatureColorModel.COLOR_HOT, TemperatureColorModel.tempToColorArgb(110f))
    }

    @Test
    fun `at mild temp the cold-to-mild blend lands exactly on mild`() {
        // fraction = (70-50)/(70-50) = 1.0, so blend(cold, mild, 1f) == mild
        assertEquals(TemperatureColorModel.COLOR_MILD, TemperatureColorModel.tempToColorArgb(70f))
    }

    @Test
    fun `midpoint blends are strictly between the endpoint channels`() {
        val c = TemperatureColorModel.tempToColorArgb(60f) // halfway cold->mild
        // Red channel: cold 0x5A (90), mild 0xE8 (232) -> midpoint ~161
        val r = TemperatureColorModel.red(c)
        assertTrue("red should be between cold and mild", r in 91..231)
    }

    @Test
    fun `result is always fully opaque`() {
        for (t in listOf(10f, 50f, 60f, 70f, 80f, 90f, 120f)) {
            val a = (TemperatureColorModel.tempToColorArgb(t) ushr 24) and 0xFF
            assertEquals("alpha for $t", 255, a)
        }
    }

    @Test
    fun `withAlpha replaces alpha and preserves rgb`() {
        val base = TemperatureColorModel.COLOR_MILD
        val faded = TemperatureColorModel.withAlpha(base, 68)
        assertEquals(68, (faded ushr 24) and 0xFF)
        assertEquals(TemperatureColorModel.red(base), TemperatureColorModel.red(faded))
        assertEquals(TemperatureColorModel.green(base), TemperatureColorModel.green(faded))
        assertEquals(TemperatureColorModel.blue(base), TemperatureColorModel.blue(faded))
    }

    @Test
    fun `gradient stops put the high on top, the low at the bottom and each inside threshold between`() {
        // 40..100 over 60: 90 at 1/6, 70 at 1/2, 50 at 5/6.
        val stops = TemperatureColorModel.gradientStops(minTemp = 40f, maxTemp = 100f, tempRange = 60f)
        val expected = listOf(0f, 1f / 6f, 0.5f, 5f / 6f, 1f)
        assertEquals(expected.size, stops.size)
        expected.zip(stops).forEach { (want, stop) -> assertEquals(want, stop.first, 1e-5f) }
        assertEquals(TemperatureColorModel.COLOR_HOT, stops.first().second)
        assertEquals(TemperatureColorModel.COLOR_MILD, stops[2].second)
        assertEquals(TemperatureColorModel.COLOR_COLD, stops.last().second)
    }

    @Test
    fun `gradient stops skip thresholds outside or on the range ends`() {
        // 55..70: no threshold strictly inside (70 is the high itself).
        val stops = TemperatureColorModel.gradientStops(minTemp = 55f, maxTemp = 70f, tempRange = 15f)
        assertEquals(listOf(0f, 1f), stops.map { it.first })
        assertEquals(TemperatureColorModel.COLOR_MILD, stops.first().second)
    }

    @Test
    fun `a flat range collapses to one stop per position`() {
        // range 0 is clamped to positions 0 and 1 only; nothing is duplicated.
        val stops = TemperatureColorModel.gradientStops(minTemp = 60f, maxTemp = 60f, tempRange = 1f)
        assertEquals(stops.map { it.first }.distinct(), stops.map { it.first })
    }
}
