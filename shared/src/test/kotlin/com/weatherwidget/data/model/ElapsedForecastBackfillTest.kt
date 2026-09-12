package com.weatherwidget.data.model

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * plans/260911-backfill-elapsed-hour-forecast-history-on-fresh-site.md: which elapsed hours of a
 * fetched payload may be filed into `hourly_forecast_history`.
 */
@Category(ShortDuration::class)
class ElapsedForecastBackfillTest {

    private val h = 3_600_000L
    private val now = 1_800_000_000_000L + 1_234_567L // deliberately not hour-aligned

    private fun hour(t: Long, temp: Float = 60f) = HourlyForecast(dateTime = t, temperature = temp, condition = "Clear")

    @Test
    fun `selects only uncovered elapsed hours inside the window`() {
        val fetched = listOf(
            hour(now - ElapsedForecastBackfill.LOOKBACK_MS - h), // older than the look-back
            hour(now - ElapsedForecastBackfill.LOOKBACK_MS),     // exactly at the look-back edge: in
            hour(now - 5 * h),                                   // elapsed, uncovered
            hour(now - 4 * h),                                   // elapsed, covered
            hour(now - 3 * h),                                   // elapsed, uncovered
            hour(now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS), // the live filter's side of the boundary
            hour(now - 30 * 60_000L),                            // in progress: live
            hour(now + h),                                       // future: live
        )
        val selected = ElapsedForecastBackfill.select(fetched, now, coveredHours = setOf(now - 4 * h))

        assertEquals(
            listOf(now - ElapsedForecastBackfill.LOOKBACK_MS, now - 5 * h, now - 3 * h),
            selected.map { it.dateTime },
        )
    }

    @Test
    fun `boundary agrees with the live-table filter so an hour goes to exactly one path`() {
        val boundary = now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS
        val fetched = listOf(hour(boundary - 1), hour(boundary))
        val live = fetched.filter { it.dateTime >= now - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS }
        val backfill = ElapsedForecastBackfill.select(fetched, now, emptySet())

        assertEquals(listOf(boundary), live.map { it.dateTime })
        assertEquals(listOf(boundary - 1), backfill.map { it.dateTime })
    }

    @Test
    fun `all covered yields empty - steady state writes nothing`() {
        val fetched = (1..10).map { hour(now - it * h - h) }
        val selected = ElapsedForecastBackfill.select(fetched, now, fetched.map { it.dateTime }.toSet())
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `duplicate hours in the payload collapse to the last one`() {
        val t = now - 3 * h
        val selected = ElapsedForecastBackfill.select(listOf(hour(t, 50f), hour(t, 55f)), now, emptySet())
        assertEquals(1, selected.size)
        assertEquals(55f, selected.single().temperature, 0f)
    }
}
