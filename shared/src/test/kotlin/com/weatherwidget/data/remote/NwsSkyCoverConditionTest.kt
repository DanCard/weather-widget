package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsSkyCoverConditionTest {
    @Test
    fun `bands follow NWS sky-cover thresholds`() {
        assertEquals("Unknown", NwsSkyCoverCondition.shortForecastFor(null))
        assertEquals("Clear", NwsSkyCoverCondition.shortForecastFor(0))
        assertEquals("Clear", NwsSkyCoverCondition.shortForecastFor(12))
        assertEquals("Mostly Clear", NwsSkyCoverCondition.shortForecastFor(13))
        assertEquals("Mostly Clear", NwsSkyCoverCondition.shortForecastFor(37))
        assertEquals("Partly Cloudy", NwsSkyCoverCondition.shortForecastFor(38))
        assertEquals("Partly Cloudy", NwsSkyCoverCondition.shortForecastFor(62))
        assertEquals("Mostly Cloudy", NwsSkyCoverCondition.shortForecastFor(63))
        assertEquals("Mostly Cloudy", NwsSkyCoverCondition.shortForecastFor(87))
        assertEquals("Cloudy", NwsSkyCoverCondition.shortForecastFor(88))
        assertEquals("Cloudy", NwsSkyCoverCondition.shortForecastFor(100))
    }
}
