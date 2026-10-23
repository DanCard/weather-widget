package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/** [SourceSwitchFetchRule]: a source becoming primary, both platforms. */
@Category(ShortDuration::class)
class SourceSwitchFetchRuleTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 23)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private val google = "GOOGLE_WEATHER"

    private fun hours(fetchedAt: Long) = (0 until 6).map { HourlyForecast(now + it * hour, 60f, "Clear", source = google, fetchedAt = fetchedAt) }
    private val todayRow = listOf(DailyForecast(today.toString(), 70f, 50f, "Clear"))

    @Test
    fun aFreshCacheIsDrawable() {
        assertTrue(SourceSwitchFetchRule.hasDrawableCache(google, now - hour, hours(now - hour), today, now, zone, todayRow))
    }

    @Test
    fun aWeekOldCacheIsNot() {
        // Desktop's old test took any cache with today's rows: a week-old one fetched nothing and showed no banner.
        val weekAgo = now - 7 * 24 * hour
        assertFalse(SourceSwitchFetchRule.hasDrawableCache(google, weekAgo, hours(weekAgo), today, now, zone, todayRow))
    }

    @Test
    fun hourlyMustBeFreshAndTodays() {
        assertFalse(SourceSwitchFetchRule.hasDrawableCache(google, now - hour, hours(now - 30 * hour), today, now, zone))
        assertFalse("no row for today", SourceSwitchFetchRule.hasDrawableCache(google, now - hour, hours(now - hour), today, now, zone, emptyList()))
    }

    @Test
    fun noCacheMeansABannerAndAForcedFetch() {
        assertEquals(SourceSwitchFetchRule.Start(false, banner = true, forced = true), SourceSwitchFetchRule.start(false, true, true))
        assertFalse("nowhere to show it", SourceSwitchFetchRule.start(false, true, hasSurface = false).banner)
        assertEquals(SourceSwitchFetchRule.Start(true, banner = false, forced = false), SourceSwitchFetchRule.start(true, true, true))
    }
}
