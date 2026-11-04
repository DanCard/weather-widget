package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/** Newest fetch wins for every hour, started or not. plans/261104-remove-hourly-pre-hour-freeze.md */
@Category(ShortDuration::class)
class SettledForecastPickTest {

    private val hourMs = 3_600_000L
    private val hour = 100 * hourMs

    private fun row(fetchedAt: Long, label: String, precip: Int? = null) =
        HourlyForecast(dateTime = hour, temperature = 0f, condition = label, fetchedAt = fetchedAt, precipProbability = precip)

    private val earlier = row(hour - 5 * hourMs, "earlier")
    private val preHour = row(hour - 1_000L, "preHour")
    private val hindcast = row(hour + 2_000L, "hindcast")
    private val lateHindcast = row(hour + 9_000L, "lateHindcast")

    private fun pick(rows: List<HourlyForecast>) = SettledForecastPick.pick(rows)?.condition

    /** Desktop asleep 19:33-03:10 on 2026-11-04: NWS 18:45 said 4%, its 03:10 fetch said 25%. */
    @Test
    fun `a fetch during the hour replaces an evening forecast`() {
        val evening = row(hour - (8 * hourMs + 15 * 60_000L), "18:45", precip = 4)
        val inHour = row(hour + 10 * 60_000L, "03:10", precip = 25)
        val picked = SettledForecastPick.pick(listOf(evening, inHour))
        println("picked=${picked?.condition} precip=${picked?.precipProbability}")
        assertEquals(25, picked?.precipProbability)
    }

    @Test
    fun `the newest fetch wins, hindcast included`() {
        assertEquals("lateHindcast", pick(listOf(hindcast, earlier, lateHindcast, preHour)))
    }

    @Test
    fun `a synthesized row loses to any real fetch`() {
        assertEquals("earlier", pick(listOf(row(0L, "synthesized"), earlier)))
    }

    @Test
    fun `on a fetchedAt tie the row listed first wins`() {
        val live = row(preHour.fetchedAt, "live")
        assertEquals("live", pick(listOf(live, preHour)))
        assertEquals("preHour", pick(listOf(preHour, live)))
    }

    @Test
    fun `backfill order is newest first`() {
        assertEquals(
            listOf("lateHindcast", "hindcast", "preHour", "earlier"),
            SettledForecastPick.backfillOrder(listOf(hindcast, earlier, lateHindcast, preHour)).map { it.condition },
        )
    }

    @Test
    fun `no rows picks nothing`() {
        assertNull(pick(emptyList()))
    }
}
