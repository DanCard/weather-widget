package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * A started hour keeps its fresh pre-hour forecast; a hindcast stands in only when that prior is
 * missing or older than the guard's age. plans/261113-no-hindcasts-unless-prior-missing.md
 */
@Category(ShortDuration::class)
class SettledForecastPickTest {

    private val hourMs = 3_600_000L
    private val minute = 60_000L
    private val hour = 100 * hourMs
    private val now = hour + 30 * minute
    private val sixHours = 6 * hourMs

    private fun row(fetchedAt: Long, label: String, precip: Int? = null, cloud: Int? = null) =
        HourlyForecast(
            dateTime = hour, temperature = 0f, condition = label, fetchedAt = fetchedAt,
            precipProbability = precip, cloudCover = cloud,
        )

    private val earlier = row(hour - 5 * hourMs, "earlier")
    private val preHour = row(hour - 1_000L, "preHour")
    private val hindcast = row(hour + 2_000L, "hindcast")
    private val lateHindcast = row(hour + 9_000L, "lateHindcast")

    private fun pick(rows: List<HourlyForecast>, nowMs: Long = now, maxAge: Long = sixHours) =
        SettledForecastPick.pick(rows, hour, nowMs, maxAge) { it.fetchedAt }?.condition

    @Test
    fun `a fresh pre-hour forecast beats every hindcast`() {
        assertEquals("preHour", pick(listOf(hindcast, earlier, lateHindcast, preHour)))
    }

    @Test
    fun `an hour not yet started takes the newest fetch`() {
        assertEquals("lateHindcast", pick(listOf(hindcast, earlier, lateHindcast, preHour), nowMs = hour - 1))
    }

    /** Desktop asleep 19:33-03:10 on 2026-11-04: NWS 18:45 said 4%, its 03:10 fetch said 25%. */
    @Test
    fun `a stale evening forecast counts as missing, so the first fetch after the hour stands in`() {
        val evening = row(hour - (8 * hourMs + 15 * minute), "18:45", precip = 4)
        val inHour = row(hour + 10 * minute, "03:10", precip = 25)
        val later = row(hour + 2 * hourMs, "05:00", precip = 60)
        val picked = SettledForecastPick.pick(listOf(evening, later, inHour), hour, hour + 3 * hourMs, sixHours) { it.fetchedAt }
        println("T=6h picked=${picked?.condition} precip=${picked?.precipProbability}")
        assertEquals(25, picked?.precipProbability)

        val loose = SettledForecastPick.pick(listOf(evening, later, inHour), hour, hour + 3 * hourMs, 12 * hourMs) { it.fetchedAt }
        println("T=12h picked=${loose?.condition} precip=${loose?.precipProbability}")
        assertEquals(4, loose?.precipProbability)
    }

    @Test
    fun `no pre-hour forecast takes the earliest hindcast`() {
        assertEquals("hindcast", pick(listOf(lateHindcast, hindcast)))
    }

    @Test
    fun `only a stale prior and nothing after keeps the stale prior`() {
        assertEquals("earlier", pick(listOf(row(hour - 30 * hourMs, "older"), row(hour - 7 * hourMs, "earlier"))))
    }

    @Test
    fun `the guard off takes the newest fetch, hindcast included`() {
        assertEquals("lateHindcast", pick(listOf(hindcast, earlier, lateHindcast, preHour), maxAge = 0))
    }

    /** HourlyStaleFragmentCollapseTest's shape: a jitter fragment's weeks-old long-range row. */
    @Test
    fun `a weeks-old pre-hour row loses to the fresh post-hour row`() {
        assertEquals("fresh", pick(listOf(row(hour - 20 * 24 * hourMs, "weeksOld"), row(hour + 5 * minute, "fresh"))))
    }

    @Test
    fun `a synthesized row loses to any later fetch`() {
        assertEquals("hindcast", pick(listOf(row(0L, "synthesized"), hindcast)))
        assertEquals("preHour", pick(listOf(row(0L, "synthesized"), preHour)))
    }

    @Test
    fun `on a fetchedAt tie the row listed first wins`() {
        val live = row(preHour.fetchedAt, "live")
        assertEquals("live", pick(listOf(live, preHour)))
        assertEquals("preHour", pick(listOf(preHour, live)))
    }

    @Test
    fun `backfill order is the chosen side first, then the other side`() {
        val rows = listOf(hindcast, earlier, lateHindcast, preHour)
        assertEquals(
            listOf("preHour", "earlier", "hindcast", "lateHindcast"),
            SettledForecastPick.backfillOrder(rows, hour, now, sixHours) { it.fetchedAt }.map { it.condition },
        )
        val stale = listOf(hindcast, earlier, lateHindcast)
        assertEquals(
            listOf("hindcast", "lateHindcast", "earlier"),
            SettledForecastPick.backfillOrder(stale, hour, now, 4 * hourMs) { it.fetchedAt }.map { it.condition },
        )
    }

    @Test
    fun `a null field on a fresh prior borrows from older priors before any hindcast`() {
        val rows = listOf(row(hour - 1_000L, "pre", cloud = null), row(hour - 2 * hourMs, "older", cloud = 40), row(hour + 1_000L, "post", cloud = 90))
        val order = SettledForecastPick.backfillOrder(rows, hour, now, sixHours) { it.fetchedAt }
        assertEquals(40, order.firstNotNullOfOrNull { it.cloudCover })
    }

    @Test
    fun `no rows picks nothing`() {
        assertNull(pick(emptyList()))
    }
}
