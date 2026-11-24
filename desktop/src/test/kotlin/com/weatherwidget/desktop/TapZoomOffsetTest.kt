package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.DrawnTimeAxis
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The hour a desktop tap or wheel zooms on ([tapZoomOffset] → `:shared` [DrawnTimeAxis]).
 *
 * 1. It was once computed as `start + fraction * (cutoff - start)` in Float on epoch millis —
 *    131 s steps at that size — so a tap minutes past a half hour could centre the hour before.
 * 2. All three hourly graphs stretch `points.first()..points.last()` across `0..width` (`xAtTime`),
 *    so when a source's forecast ends mid-window (e.g. OWM ending at 11 AM inside a 3 AM–9 PM
 *    Sunday window), pointer x maps against [DrawnTimeAxis.resolve] rather than `start..cutoff`.
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

    @Test
    fun `tap at 9 AM on truncated OWM Sunday window zooms onto 9 AM not afternoon`() {
        val hourMs = 3_600_000L
        val now = 1_795_554_000_000L // Tue 14:00
        val sun03 = now + 109 * hourMs // Sun 03:00 (WIDE window start for Sun noon)
        val sun11 = now + 117 * hourMs // Sun 11:00 (OWM 5-day horizon)
        val sun21 = now + 127 * hourMs // Sun 21:00 (WIDE window end)
        val axis = DrawnTimeAxis.resolve(sun03, sun11, sun03, sun21)

        // 9 AM is 6h past 3 AM, drawn at 6/8 = 0.75 of the width.
        // Using the unclipped window (03:00..21:00) jumped to 109 + 0.75*18 = 123 (5 PM, empty).
        assertEquals(115, tapZoomOffset(0.75, axis.startMs, axis.endMs, now))
    }
}
