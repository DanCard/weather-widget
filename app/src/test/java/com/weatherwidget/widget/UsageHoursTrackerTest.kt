package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** performance/261019-android-usage-hours-slow-fetching-when-unused.md (phase 1, log-only) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(ShortDuration::class)
class UsageHoursTrackerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(day: Int, h: Int) =
        LocalDateTime.of(2026, 10, 19, h, 10).plusDays(day.toLong()).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        context.getSharedPreferences("usage_hours_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `sightings persist and the daily lines are logged once each`() {
        UsageHoursTracker.advance(context, at(0, 0))
        UsageHoursTracker.sight(context, at(0, 3))
        UsageHoursTracker.sight(context, at(0, 9))
        val first = UsageHoursTracker.dailyLogLines(context, at(1, 8))
        assertEquals(listOf("USAGE_HOURS", "USAGE_PREDICTION_CHECK"), first.map { it.first })
        assertTrue(first[1].second, first[1].second.startsWith("day=2026-10-19 predictedUnusedHours=5 misses=1 missHours=03"))
        assertTrue("same day: nothing new", UsageHoursTracker.dailyLogLines(context, at(1, 12)).isEmpty())
        assertEquals(listOf("USAGE_HOURS", "USAGE_PREDICTION_CHECK"), UsageHoursTracker.dailyLogLines(context, at(2, 8)).map { it.first })
    }
}
