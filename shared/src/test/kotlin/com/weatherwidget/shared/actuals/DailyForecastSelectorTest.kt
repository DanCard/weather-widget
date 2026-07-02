package com.weatherwidget.shared.actuals

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyForecastSelectorTest {

    // The real coordinates from the on-device bug: the fetch site hopped ~1.5 m on June 30, so the
    // June 29 batch at the old key was never overwritten and its 74° kept displaying over 77°.
    private val siteA = 37.41684341430664 to -122.0890045166016 // current fetch site (= query centre)
    private val siteB = 37.41682815551758 to -122.0889205932617 // abandoned jitter site

    private data class Row(
        val targetDate: Long,
        val source: String,
        val lat: Double,
        val lon: Double,
        val batchFetchedAt: Long,
        val highTemp: Float,
        val fetchedAt: Long = batchFetchedAt,
    )

    private fun select(rows: List<Row>, centerLat: Double = siteA.first, centerLon: Double = siteA.second) =
        DailyForecastSelector.selectFreshestPerDaySource(
            rows,
            centerLat,
            centerLon,
            targetDate = { it.targetDate },
            source = { it.source },
            locationLat = { it.lat },
            locationLon = { it.lon },
            batchFetchedAt = { it.batchFetchedAt },
            fetchedAt = { it.fetchedAt },
        )

    @Test
    fun `stale abandoned-site row loses to the freshest batch (the 74-vs-77 bug)`() {
        val rows = listOf(
            // Stale site B row sorts FIRST (as it did on-device, by rowid) — first-match must not win.
            Row(targetDate = 100L, source = "NWS", lat = siteB.first, lon = siteB.second, batchFetchedAt = 1_000L, highTemp = 74f),
            Row(targetDate = 100L, source = "NWS", lat = siteA.first, lon = siteA.second, batchFetchedAt = 9_000L, highTemp = 77f),
        )
        val picked = select(rows)
        assertEquals(1, picked.size)
        assertEquals(77f, picked.single().highTemp, 0f)
    }

    @Test
    fun `genuinely different neighbouring marker is excluded even when fresher`() {
        val defaultMarkerLat = 37.422 // ~0.005° away: a real different marker, not jitter
        val rows = listOf(
            Row(100L, "NWS", siteA.first, siteA.second, batchFetchedAt = 5_000L, highTemp = 76f),
            Row(100L, "NWS", defaultMarkerLat, siteA.second, batchFetchedAt = 9_999L, highTemp = 50f),
        )
        assertEquals(76f, select(rows).single().highTemp, 0f)
    }

    @Test
    fun `off-site rows are kept when no same-site row exists`() {
        val rows = listOf(
            Row(100L, "NWS", 37.422, -122.0841, batchFetchedAt = 1_000L, highTemp = 70f),
            Row(100L, "NWS", 37.422, -122.0841, batchFetchedAt = 2_000L, highTemp = 71f),
        )
        assertEquals(71f, select(rows).single().highTemp, 0f)
    }

    @Test
    fun `sources stay independent per day`() {
        val rows = listOf(
            Row(100L, "NWS", siteA.first, siteA.second, batchFetchedAt = 9_000L, highTemp = 77f),
            Row(100L, "OPEN_METEO", siteB.first, siteB.second, batchFetchedAt = 1_000L, highTemp = 80f),
        )
        val picked = select(rows)
        assertEquals(2, picked.size)
        assertEquals(setOf(77f, 80f), picked.map { it.highTemp }.toSet())
    }

    @Test
    fun `equal batch ties break on fetchedAt`() {
        val rows = listOf(
            Row(100L, "NWS", siteA.first, siteA.second, batchFetchedAt = 5_000L, highTemp = 75f, fetchedAt = 1L),
            Row(100L, "NWS", siteA.first, siteA.second, batchFetchedAt = 5_000L, highTemp = 76f, fetchedAt = 2L),
        )
        assertEquals(76f, select(rows).single().highTemp, 0f)
    }

    @Test
    fun `output stays targetDate-ascending after grouping`() {
        val rows = listOf(
            Row(200L, "NWS", siteA.first, siteA.second, batchFetchedAt = 9_000L, highTemp = 78f),
            Row(100L, "NWS", siteB.first, siteB.second, batchFetchedAt = 1_000L, highTemp = 74f),
            Row(100L, "NWS", siteA.first, siteA.second, batchFetchedAt = 9_000L, highTemp = 77f),
        )
        assertEquals(listOf(100L, 200L), select(rows).map { it.targetDate })
    }
}
