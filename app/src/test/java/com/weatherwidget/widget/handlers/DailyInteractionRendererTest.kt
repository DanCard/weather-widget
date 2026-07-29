package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.WidgetQueryWindows

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.WeatherWidgetProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyInteractionRendererTest {

    @Test
    fun `all daily bounds derive from one captured time near midnight`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = LocalDateTime.of(2026, 7, 28, 23, 59, 59)

        val bounds = DailyInteractionRenderer.timeBounds(now, zone)

        assertEquals(LocalDate.of(2026, 7, 28), bounds.today)
        assertEquals(
            LocalDate.of(2026, 7, 28).atStartOfDay(zone).toInstant().toEpochMilli(),
            bounds.todayStartMs,
        )
        assertEquals(
            now.minusHours(WidgetQueryWindows.HOURLY_LOOKBACK_HOURS)
                .atZone(zone).toInstant().toEpochMilli(),
            bounds.hourlyStartMs,
        )
        assertEquals(
            now.plusHours(WidgetQueryWindows.HOURLY_GRAPH_LOOKAHEAD_HOURS)
                .atZone(zone).toInstant().toEpochMilli(),
            bounds.hourlyEndMs,
        )
    }
}
