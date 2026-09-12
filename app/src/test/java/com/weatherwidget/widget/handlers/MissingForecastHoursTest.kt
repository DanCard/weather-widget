package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class MissingForecastHoursTest {

    private val zoneId = ZoneId.of("America/Los_Angeles")
    private val start = LocalDateTime.of(2026, 7, 23, 5, 0)

    @Test
    fun `summarizes contiguous absent and wrong-source forecast anchors separately`() {
        val forecasts = mapOf(
            hour(0) to null,
            hour(1) to null,
            hour(2) to forecast(hour(2), WeatherSource.OPEN_METEO),
            hour(3) to forecast(hour(3), WeatherSource.NWS),
            hour(4) to null,
        )

        val result = summarizeMissingForecastHours(
            startHour = start,
            // endHour is inclusive (it is a mark inside the view), so this walks hours 0..4 — exactly
            // the five the fixture above describes. It used to read plusHours(5) against an exclusive
            // end for the same five hours.
            endHour = start.plusHours(4),
            zoneId = zoneId,
            forecastsByTime = forecasts,
            displaySource = WeatherSource.NWS,
            // "Now" before the window: every gap is still fillable, nothing is elapsed.
            nowMs = hour(-2),
        )

        assertEquals(4, result.missingCount)
        assertEquals(0, result.elapsedCount)
        assertEquals(4, result.fillableCount)
        assertEquals(3, result.noSelectedForecastCount)
        assertEquals(1, result.wrongSourceCount)
        assertEquals(
            listOf(
                start to start.plusHours(3),
                start.plusHours(4) to start.plusHours(5),
            ),
            result.spans,
        )
        assertEquals(
            "missing=4 elapsed=0 noSelected=3 wrongSource=1 spans=[07-23 05:00..07-23 08:00,07-23 09:00..07-23 10:00]",
            result.diagnosticText(),
        )
    }

    /**
     * Emulator 2026-09-12 14:37: the only gap was `06:00..07:00` with now = 14:37 — older than the
     * live-table boundary, so history's to fill, not a fetch's. The detector used to force a
     * 4-source re-fetch for it every 15 minutes.
     */
    @Test
    fun `gaps before the elapsed boundary are counted but not fillable`() {
        val forecasts = mapOf(
            hour(0) to null,                                   // elapsed: 3 h before now
            hour(1) to null,                                   // elapsed: 2 h before now
            hour(2) to null,                                   // exactly now-1h: live-table hour, fillable
            hour(3) to forecast(hour(3), WeatherSource.NWS),   // now
            hour(4) to null,                                   // future, fillable
        )

        val result = summarizeMissingForecastHours(
            startHour = start,
            endHour = start.plusHours(4),
            zoneId = zoneId,
            forecastsByTime = forecasts,
            displaySource = WeatherSource.NWS,
            nowMs = hour(3),
        )

        assertEquals(4, result.missingCount)
        assertEquals(2, result.elapsedCount)
        assertEquals(2, result.fillableCount)
        assertEquals(
            "missing=4 elapsed=2 noSelected=4 wrongSource=0 spans=[07-23 05:00..07-23 08:00,07-23 09:00..07-23 10:00]",
            result.diagnosticText(),
        )
    }

    /** All gaps elapsed → fillableCount is 0 and the loader must not force a fetch. */
    @Test
    fun `a window whose only gaps are elapsed has nothing fillable`() {
        val forecasts = mapOf(
            hour(0) to null,
            hour(1) to forecast(hour(1), WeatherSource.NWS),
            hour(2) to forecast(hour(2), WeatherSource.NWS),
        )

        val result = summarizeMissingForecastHours(
            startHour = start,
            endHour = start.plusHours(2),
            zoneId = zoneId,
            forecastsByTime = forecasts,
            displaySource = WeatherSource.NWS,
            nowMs = hour(2),
        )

        assertEquals(1, result.missingCount)
        assertEquals(1, result.elapsedCount)
        assertEquals(0, result.fillableCount)
    }

    private fun hour(offset: Long): Long = start.plusHours(offset).atZone(zoneId).toInstant().toEpochMilli()

    private fun forecast(timeMs: Long, source: WeatherSource) =
        HourlyForecastEntity(
            dateTime = timeMs,
            locationLat = 37.416,
            locationLon = -122.089,
            temperature = 70f,
            condition = "Clear",
            source = source.id,
            fetchedAt = 1L,
        )
}
