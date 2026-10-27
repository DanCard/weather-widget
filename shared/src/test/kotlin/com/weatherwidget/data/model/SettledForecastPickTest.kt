package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class SettledForecastPickTest {

    private val hourMs = 3_600_000L
    private val hour = 100 * hourMs
    private val started = hour + 1

    private fun row(fetchedAt: Long, label: String) =
        HourlyForecast(dateTime = hour, temperature = 0f, condition = label, fetchedAt = fetchedAt)

    private val earlier = row(hour - 5 * hourMs, "earlier")
    private val preHour = row(hour - 1_000L, "preHour")
    private val hindcast = row(hour + 2_000L, "hindcast")
    private val lateHindcast = row(hour + 9_000L, "lateHindcast")

    private fun pick(rows: List<HourlyForecast>, nowMs: Long) = SettledForecastPick.pick(rows, hour, nowMs)?.condition

    @Test
    fun `an hour not yet started takes the newest fetch`() {
        assertEquals("preHour", pick(listOf(earlier, preHour), nowMs = hour))
    }

    @Test
    fun `a started hour takes the newest fetch at or before it`() {
        assertEquals("preHour", pick(listOf(hindcast, earlier, lateHindcast, preHour), started))
    }

    @Test
    fun `a fetch exactly at the hour counts as pre-hour`() {
        assertEquals("atHour", pick(listOf(preHour, row(hour, "atHour"), hindcast), started))
    }

    @Test
    fun `a started hour with no pre-hour fetch takes the earliest`() {
        assertEquals("hindcast", pick(listOf(lateHindcast, hindcast), started))
    }

    @Test
    fun `a synthesized row loses to any real pre-hour fetch`() {
        assertEquals("earlier", pick(listOf(row(0L, "synthesized"), earlier), started))
    }

    /** User, 2026-10-27: "Freeze wins always" — a day-old forecast still beats a refill or hindcast. */
    @Test
    fun `a stale pre-hour forecast still beats a post-hour row`() {
        assertEquals("stale", pick(listOf(hindcast, row(hour - 24 * hourMs, "stale")), started))
    }

    @Test
    fun `on a fetchedAt tie the row listed first wins`() {
        val live = row(preHour.fetchedAt, "live")
        assertEquals("live", pick(listOf(live, preHour), started))
    }

    @Test
    fun `backfill order follows the pick's window, nearest first`() {
        fun order(rows: List<HourlyForecast>, nowMs: Long) =
            SettledForecastPick.backfillOrder(rows, hour, nowMs).map { it.condition }
        val rows = listOf(hindcast, earlier, lateHindcast, preHour)

        assertEquals(listOf("preHour", "earlier"), order(rows, started))
        assertEquals(listOf("hindcast", "lateHindcast"), order(listOf(lateHindcast, hindcast), started))
        assertEquals(listOf("lateHindcast", "hindcast", "preHour", "earlier"), order(rows, nowMs = hour))
    }

    @Test
    fun `no rows picks nothing`() {
        assertNull(pick(emptyList(), started))
    }
}
