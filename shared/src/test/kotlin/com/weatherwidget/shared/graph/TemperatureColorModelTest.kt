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
}
