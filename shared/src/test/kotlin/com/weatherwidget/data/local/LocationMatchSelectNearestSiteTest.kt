package com.weatherwidget.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit coverage for [LocationMatch.selectNearestSite] — the step that collapses a raw
 * proximity-box result to one physical site.
 *
 * Regression guard for the stations list showing 6 entries when only `MAX_RETRIES = 5` stations are
 * ever fetched: rows for LSGC1 (Los Gatos) had been fetched under a site the device visited earlier
 * that day, which sits 0.075°/0.047° from the current site — a different town, yet well inside the
 * ±[LocationMatch.TOLERANCE_DEG] box, so the DAO returned it and the list rendered it.
 */
class LocationMatchSelectNearestSiteTest {

    /** Minimal stand-in for the row shape both backends share: a stored *device* location. */
    private data class Row(val id: String, val locationLat: Double, val locationLon: Double)

    // The two sites observed on the Samsung device on 2026-07-15.
    private val currentLat = 37.4168
    private val currentLon = -122.089
    private val earlierLat = 37.3414
    private val earlierLon = -122.0422

    private fun select(rows: List<Row>, lat: Double = currentLat, lon: Double = currentLon) =
        LocationMatch.selectNearestSite(rows, lat, lon, { it.locationLat }, { it.locationLon })

    @Test
    fun `drops a stale nearby site that the box admits`() {
        val rows = listOf(
            Row("AW020", currentLat, currentLon),
            Row("KNUQ", currentLat, currentLon),
            Row("KPAO", currentLat, currentLon),
            Row("LOAC1", currentLat, currentLon),
            Row("KSJC", currentLat, currentLon),
            Row("LSGC1", earlierLat, earlierLon),
        )

        assertEquals(
            listOf("AW020", "KNUQ", "KPAO", "LOAC1", "KSJC"),
            select(rows).map { it.id },
        )
    }

    @Test
    fun `the stale site is genuinely inside the box the DAO queries`() {
        // Proves the test above is exercising the real bug and not a coordinate that the SQL
        // predicate would have filtered out on its own.
        val insideBox = kotlin.math.abs(earlierLat - currentLat) <= LocationMatch.TOLERANCE_DEG &&
            kotlin.math.abs(earlierLon - currentLon) <= LocationMatch.TOLERANCE_DEG
        assertEquals(true, insideBox)
    }

    @Test
    fun `keeps sub-precision fragments of the winning site`() {
        // Same spot at two GPS precisions must not be split — this is why the filter is sameSite and
        // not exact-float equality.
        val rows = listOf(
            Row("afternoon", 37.4168014526367, -122.088897705078),
            Row("morning", 37.4168434143066, -122.088996887207),
        )

        assertEquals(2, select(rows).size)
    }

    @Test
    fun `picks the nearest site when every row is stale`() {
        // Device moved; nothing sits at the query point. The closest site still wins outright rather
        // than the list coming back mixed.
        val rows = listOf(
            Row("closer", 37.4000, -122.0800),
            Row("further", earlierLat, earlierLon),
        )

        assertEquals(listOf("closer"), select(rows).map { it.id })
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<Row>(), select(emptyList()))
    }

    @Test
    fun `a single site passes through untouched`() {
        val rows = listOf(Row("a", currentLat, currentLon), Row("b", currentLat, currentLon))
        assertEquals(rows, select(rows))
    }
}
