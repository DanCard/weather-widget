package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.shared.util.PriorDayForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * The today column's snapshot bar is dashed when a side's forecast was last confirmed more than a
 * day before its anchor (06:00 / 16:00 yesterday). Age is measured from the last fetch that returned
 * the values (`batchFetchedAt`), not the first (`fetchedAt`). Pixel 7 Pro, 2026-10-01: NWS 84/58
 * first stored 09-29 08:39, re-confirmed unchanged through 09-30, drew dashed because it was judged
 * from `fetchedAt`.
 */
@Category(ShortDuration::class)
class DailyTodayResolverSnapshotStaleTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 1)

    private fun at(day: Int, h: Int, m: Int = 0) =
        LocalDate.of(2026, 9, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun row(fetchedAt: Long, confirmedAt: Long) = ForecastEntity(
        targetDate = 1_790_812_800_000L,
        dateOfPrediction = 1_790_640_000_000L,
        locationLat = 37.417,
        locationLon = -122.089,
        highTemp = 84f,
        lowTemp = 58f,
        condition = "Sunny",
        source = "NWS",
        fetchedAt = fetchedAt,
        batchFetchedAt = confirmedAt,
    )

    private fun stale(r: ForecastEntity?) =
        DailyTodayResolver.isSnapshotStale(PriorDayForecast.Pick(r, r), today, zone)

    @Test
    fun `old first sighting re-confirmed just before the anchors is not stale`() {
        assertFalse(stale(row(fetchedAt = at(29, 8, 39), confirmedAt = at(30, 5, 30))))
    }

    @Test
    fun `not re-confirmed within a day of the anchor is stale`() {
        // Last confirmed 09-29 05:00: 25h before the 09-30 06:00 low anchor.
        assertTrue(stale(row(fetchedAt = at(27, 8), confirmedAt = at(29, 5))))
    }

    @Test
    fun `no snapshot is not stale`() {
        assertFalse(stale(null))
    }
}
