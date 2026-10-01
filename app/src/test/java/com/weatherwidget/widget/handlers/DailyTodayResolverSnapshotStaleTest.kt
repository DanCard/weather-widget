package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The today column's snapshot bar is dashed when its forecast is more than 48h old. Age is measured
 * from the last fetch that returned the values (`batchFetchedAt`), not the first (`fetchedAt`).
 * Pixel 7 Pro, 2026-10-01: NWS 84/58 first stored 09-29 08:39, re-confirmed unchanged through 09-30
 * 23:43, drew dashed at 09:09 because it was judged from `fetchedAt`.
 */
@Category(ShortDuration::class)
class DailyTodayResolverSnapshotStaleTest {
    private val hour = 3_600_000L
    private val now = 1_790_874_540_000L // 2026-10-01 09:09 PDT

    private fun row(fetchedAgoHours: Long, confirmedAgoHours: Long) = ForecastEntity(
        targetDate = 1_790_812_800_000L,
        dateOfPrediction = 1_790_640_000_000L,
        locationLat = 37.417,
        locationLon = -122.089,
        highTemp = 84f,
        lowTemp = 58f,
        condition = "Sunny",
        source = "NWS",
        fetchedAt = now - fetchedAgoHours * hour,
        batchFetchedAt = now - confirmedAgoHours * hour,
    )

    @Test
    fun `old first sighting re-confirmed recently is not stale`() {
        assertFalse(DailyTodayResolver.isSnapshotStale(row(fetchedAgoHours = 49, confirmedAgoHours = 10), now))
    }

    @Test
    fun `not re-confirmed for more than 48h is stale`() {
        assertTrue(DailyTodayResolver.isSnapshotStale(row(fetchedAgoHours = 150, confirmedAgoHours = 49), now))
    }

    @Test
    fun `no snapshot is not stale`() {
        assertFalse(DailyTodayResolver.isSnapshotStale(null, now))
    }
}
