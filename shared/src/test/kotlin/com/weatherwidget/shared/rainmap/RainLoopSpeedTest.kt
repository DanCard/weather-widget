package com.weatherwidget.shared.rainmap

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.util.Locale
import kotlin.math.sqrt

@Category(ShortDuration::class)
class RainLoopSpeedTest {
    @Test
    fun `default is 0_8 s per hour, half the old 0_4 s`() {
        assertEquals(0.8f, RainLoopSpeed.DEFAULT_SECONDS)
        // 35 ms tick: 0.04375 h per tick → a 48 h loop in ~38 s.
        assertEquals(0.04375, RainLoopSpeed.hoursPerTick(35, RainLoopSpeed.DEFAULT_SECONDS), 1e-9)
    }

    @Test
    fun `slider ends are slowest and fastest, midpoint is the geometric mean`() {
        assertEquals(2.0f, RainLoopSpeed.secondsAt(0f), 1e-5f)
        assertEquals(0.15f, RainLoopSpeed.secondsAt(1f), 1e-5f)
        assertEquals(sqrt(2.0f * 0.15f), RainLoopSpeed.secondsAt(0.5f), 1e-4f)
    }

    @Test
    fun `fraction and seconds round-trip`() {
        for (f in listOf(0f, 0.1f, 0.37f, 0.5f, 0.9f, 1f)) {
            assertEquals(f, RainLoopSpeed.fractionOf(RainLoopSpeed.secondsAt(f)), 1e-4f)
        }
    }

    @Test
    fun `out-of-range stored values are clamped`() {
        assertEquals(2.0f, RainLoopSpeed.clamp(10f))
        assertEquals(0.15f, RainLoopSpeed.clamp(0f))
        assertEquals(0.8f, RainLoopSpeed.clamp(Float.NaN))
        assertEquals(1f, RainLoopSpeed.fractionOf(0.01f))
    }

    @Test
    fun `readout number`() {
        assertEquals("0.8", RainLoopSpeed.formatSeconds(0.8f, Locale.US))
        assertEquals("0.15", RainLoopSpeed.formatSeconds(0.15f, Locale.US))
        assertEquals("2", RainLoopSpeed.formatSeconds(2.0f, Locale.US))
        assertEquals("1.3", RainLoopSpeed.formatSeconds(1.27f, Locale.US))
        assertEquals("0,8", RainLoopSpeed.formatSeconds(0.8f, Locale.GERMANY))
    }
}
