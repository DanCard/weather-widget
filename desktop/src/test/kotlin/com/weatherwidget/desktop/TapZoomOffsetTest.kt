package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The hour a desktop tap or wheel zooms on. It was computed as `start + fraction * (cutoff - start)`
 * in Float on epoch millis — 131 s steps at that size — so a tap minutes past a half hour could
 * centre the hour before.
 */
@Category(ShortDuration::class)
class TapZoomOffsetTest {
    @Test
    fun `a tap two minutes past a half hour centres the nearer hour`() {
        val now = 1_792_850_037_000L
        val start = 1_792_827_417_000L // now - 6 h 17 min
        val cutoff = start + 12 * 3_600_000L
        // 75/1200 across = now - 5 h 32 min: nearer -6 (the Float math said -5).
        assertEquals(-6, tapZoomOffset(75 / 1200.0, start, cutoff, now))
    }
}
