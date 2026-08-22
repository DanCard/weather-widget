package com.weatherwidget.shared.actuals

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ForecastOnlyHistoryPlannerTest {

    private val todayMs = 20_000L * 86_400_000L // arbitrary epoch day
    private val pastMs = todayMs - 86_400_000L

    private fun candidate(
        dateMs: Long = pastMs,
        source: String = "OPEN_METEO",
        high: Float? = 73f,
        low: Float? = 58f,
        fetchedAt: Long = 1000L,
        isClimateNormal: Boolean = false,
    ) = ForecastOnlyHistoryPlanner.Candidate(
        dateMs = dateMs,
        source = source,
        locationLat = 37.42,
        locationLon = -122.08,
        highTemp = high,
        lowTemp = low,
        precipAmountMm = 1.5f,
        condition = "Clear",
        fetchedAt = fetchedAt,
        isClimateNormal = isClimateNormal,
    )

    private fun plan(
        candidates: List<ForecastOnlyHistoryPlanner.Candidate>,
        existing: Set<Pair<Long, String>> = emptySet(),
    ) = ForecastOnlyHistoryPlanner.plan(candidates, existing, todayMs, GENERIC_GAP_ID)

    @Test
    fun `past day with no row plans a forecast-only row from the latest complete batch`() {
        val rows = plan(
            listOf(
                candidate(fetchedAt = 1000L, high = 72f, low = 57f),
                candidate(fetchedAt = 2000L, high = 73.6f, low = 58.3f),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(73.6f, rows[0].forecastHighTemp)
        assertEquals(58.3f, rows[0].forecastLowTemp)
        assertEquals("OPEN_METEO", rows[0].source)
    }

    @Test
    fun `existing row suppresses creation — idempotent`() {
        val rows = plan(
            listOf(candidate()),
            existing = setOf(pastMs to "OPEN_METEO"),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `today and future days never get rows`() {
        val rows = plan(
            listOf(
                candidate(dateMs = todayMs),
                candidate(dateMs = todayMs + 86_400_000L),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `climate-normal and generic-gap batches never seed rows`() {
        val rows = plan(
            listOf(
                candidate(isClimateNormal = true),
                candidate(source = GENERIC_GAP_ID),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `incomplete batches never seed rows`() {
        val rows = plan(
            listOf(
                candidate(high = null, low = 58f),
                candidate(high = 73f, low = null, fetchedAt = 2000L),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `latest fetched batch wins over an earlier complete one`() {
        // Newest FIRST in the input, to prove selection is by fetchedAt, not list order.
        val rows = plan(
            listOf(
                candidate(fetchedAt = 2000L, high = 80f, low = 60f),
                candidate(fetchedAt = 1000L, high = 72f, low = 57f),
            ),
        )
        assertEquals(80f, rows[0].forecastHighTemp)
    }

    companion object {
        private const val GENERIC_GAP_ID = "Generic"
    }
}
