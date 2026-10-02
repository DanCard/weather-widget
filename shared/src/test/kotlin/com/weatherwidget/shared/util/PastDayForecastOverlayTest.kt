package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The past-day overlay rule. The first case is the desktop DB's NWS 2026-09-02: the newest row had
 * collapsed to 74/74 and Android drew a zero-height bar where an older 74/56 row existed.
 */
@Category(ShortDuration::class)
class PastDayForecastOverlayTest {
    private data class Row(val high: Float?, val low: Float?, val fetchedAt: Long)

    private fun pick(vararg rows: Row) =
        PastDayForecastOverlay.pick(rows.toList(), { it.high }, { it.low }, { it.fetchedAt })

    @Test
    fun `an older real range beats a newer collapsed row`() {
        assertEquals(Row(74f, 56f, 1), pick(Row(74f, 56f, 1), Row(74f, 74f, 2)))
    }

    @Test
    fun `the newest real range wins`() {
        assertEquals(Row(75f, 57f, 3), pick(Row(74f, 56f, 1), Row(75f, 57f, 3), Row(70f, 50f, 2)))
    }

    @Test
    fun `a day with only collapsed rows still draws its newest`() {
        assertEquals(Row(60f, 60f, 2), pick(Row(61f, 61f, 1), Row(60f, 60f, 2)))
    }

    @Test
    fun `one-sided rows never draw, however new`() {
        assertEquals(Row(74f, 56f, 1), pick(Row(74f, 56f, 1), Row(80f, null, 5), Row(null, 50f, 6)))
        assertNull(pick(Row(80f, null, 5), Row(null, 50f, 6)))
    }

    @Test
    fun `no rows, no overlay`() {
        assertNull(pick())
    }
}
