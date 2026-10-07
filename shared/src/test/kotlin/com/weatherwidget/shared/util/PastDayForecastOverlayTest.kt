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

    private data class HRow(
        val high: Float?,
        val low: Float?,
        val fetchedAt: Long,
        val hindcastHigh: Float? = null,
        val hindcastLow: Float? = null,
    )

    private fun resolve(frozenHigh: Float?, frozenLow: Float?, rows: List<HRow>, hourly: List<Float> = emptyList()) =
        PastDayForecastOverlay.resolve(
            frozenHigh, frozenLow, rows, { it.high }, { it.low }, { it.fetchedAt },
            { it.hindcastHigh }, { it.hindcastLow }, hourly,
        )

    private fun real(h: Float, l: Float) = PastDayForecastOverlay.Resolved(h, l, isFallback = false)
    private fun fallback(h: Float, l: Float) = PastDayForecastOverlay.Resolved(h, l, isFallback = true)

    @Test
    fun `resolve keeps the frozen pair and the newest stored pair as real`() {
        assertEquals(real(70f, 50f), resolve(70f, 50f, listOf(HRow(80f, 60f, 1))))
        assertEquals(real(80f, 60f), resolve(null, null, listOf(HRow(80f, 60f, 1), HRow(80f, null, 2))))
    }

    @Test
    fun `a side completed from a stored one-sided row is still real`() {
        assertEquals(real(70f, 55f), resolve(70f, null, listOf(HRow(null, 55f, 1))))
    }

    @Test
    fun `the earliest post-cutoff value is a dashed fallback`() {
        // Google 2026-10-06 shape: high frozen at 83.8, low only ever sent after 06:00.
        val rows = listOf(HRow(83.8f, null, 1, hindcastLow = 61f), HRow(83.8f, null, 2, hindcastLow = 62f))
        assertEquals(fallback(83.8f, 61f), resolve(83.8f, null, rows, hourly = listOf(63.5f, 80f)))
    }

    @Test
    fun `the hourly range is the last resort`() {
        // Nothing for the low but the day's hourly forecast (what the live column drew that day).
        val rows = listOf(HRow(83.8f, null, 1))
        assertEquals(fallback(83.8f, 63.5f), resolve(83.8f, null, rows, hourly = listOf(73.6f, 83.8f, 63.5f)))
        assertEquals(fallback(83.8f, 63.5f), resolve(null, null, emptyList(), hourly = listOf(83.8f, 63.5f)))
    }

    @Test
    fun `nothing for a side still draws nothing`() {
        assertNull(resolve(83.8f, null, listOf(HRow(83.8f, null, 1))))
        assertNull(resolve(null, null, emptyList()))
    }

    @Test
    fun `only hours fetched before they happened count as hourly forecasts`() {
        data class H(val dateTime: Long, val fetchedAt: Long, val temp: Float)
        val temps = PastDayForecastOverlay.forecastHourlyTemps(
            listOf(H(10, 5, 64f), H(11, 11, 61f), H(12, 20, 60f), H(13, 0, 59f)),
            { it.dateTime }, { it.fetchedAt }, { it.temp },
        )
        assertEquals(listOf(64f), temps)
    }
}
