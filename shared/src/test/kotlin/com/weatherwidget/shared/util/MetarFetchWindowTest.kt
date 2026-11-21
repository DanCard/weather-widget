package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the METAR gap window. Mutation check: the old fixed 2 h makes the no-rows and gap cases fail;
 * a fixed 24 h makes the just-stored case fail.
 */
@Category(ShortDuration::class)
class MetarFetchWindowTest {

    private val now = 1_795_275_038_000L
    private val hourMs = 60 * 60_000L

    @Test
    fun `no stored METAR rows requests the whole day`() {
        // The 2026-11-21 desktop case: actuals switched NWS -> METAR, nothing stored for METAR.
        assertEquals(24, MetarFetchWindow.hours(null, now))
    }

    @Test
    fun `a gap of a day or more requests the whole day`() {
        assertEquals(24, MetarFetchWindow.hours(now - 24 * hourMs, now))
        assertEquals(24, MetarFetchWindow.hours(now - 3 * 24 * hourMs, now))
    }

    @Test
    fun `just-stored rows keep the two hour minimum`() {
        assertEquals(2, MetarFetchWindow.hours(now - 10 * 60_000L, now))
    }

    @Test
    fun `a sleep gap is covered with the margin, rounded up to whole hours`() {
        // 5 h gap + 30 min margin = 5.5 h -> 6.
        assertEquals(6, MetarFetchWindow.hours(now - 5 * hourMs, now))
        // 90 min + 30 = 2 h exactly.
        assertEquals(2, MetarFetchWindow.hours(now - 90 * 60_000L, now))
        // 91 min + 30 = 121 min -> 3.
        assertEquals(3, MetarFetchWindow.hours(now - 91 * 60_000L, now))
    }

    @Test
    fun `just under a day still caps at 24`() {
        assertEquals(24, MetarFetchWindow.hours(now - 23 * hourMs - 59 * 60_000L, now))
    }
}
