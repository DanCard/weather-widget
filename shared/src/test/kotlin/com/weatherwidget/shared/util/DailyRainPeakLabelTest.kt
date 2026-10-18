package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The hour the daytime rain chance peaks, written above the daily rain % when it is > 30 % and
 * < 100 % (user, 2026-10-18; `plans/261018-daily-rain-chance-peak-hour.md`).
 */
@Category(ShortDuration::class)
class DailyRainPeakLabelTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 18)
    private val tomorrow = today.plusDays(1)

    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun peak(
        date: LocalDate = tomorrow,
        label: String? = "45%",
        probability: Int? = 45,
        peakAt: Long? = at(date, 13),
        now: Long = at(today, 9),
        use24: Boolean = false,
        isPast: Boolean = false,
        locale: Locale = Locale.US,
    ) = DailyRainLabels.buildDailyRainPeakLabel(
        date = date,
        today = today,
        isPastDate = isPast,
        dailyRainLabelText = label,
        dayPrecipProbability = probability,
        dayPeakAt = peakAt,
        nowMs = now,
        use24HourClock = use24,
        zoneId = zone,
        locale = locale,
    )

    @Test
    fun `above 30 percent shows the peak hour`() {
        assertEquals("1pm", peak())
        assertEquals("1pm", peak(probability = 31, label = "31%"))
    }

    @Test
    fun `30 percent and below shows none`() {
        assertNull(peak(probability = 30, label = "30%"))
        assertNull(peak(probability = 12, label = "12%"))
    }

    @Test
    fun `an amount label or 100 percent shows none`() {
        assertNull("≥ 99 % future days show an amount", peak(probability = 99, label = ".25in"))
        assertNull(peak(probability = 100, label = "100%"))
    }

    @Test
    fun `no label, no peak, or a past day shows none`() {
        assertNull(peak(label = null))
        assertNull(peak(peakAt = null))
        assertNull(peak(date = today.minusDays(1), isPast = true, peakAt = at(today.minusDays(1), 13)))
    }

    @Test
    fun `today hides the hour once it is over`() {
        assertEquals("before", "1pm", peak(date = today, now = at(today, 12, 59)))
        assertEquals("during the peak hour", "1pm", peak(date = today, now = at(today, 13, 59)))
        assertNull("after", peak(date = today, now = at(today, 14, 0)))
        assertEquals("future days are never hidden by the clock", "1pm", peak(now = at(today, 18)))
    }

    @Test
    fun `clock style follows the locale`() {
        assertEquals("8am", DailyRainLabels.formatPeakHour(8, use24HourClock = false))
        assertEquals("12pm", DailyRainLabels.formatPeakHour(12, use24HourClock = false))
        assertEquals("13:00", DailyRainLabels.formatPeakHour(13, use24HourClock = true, locale = Locale.GERMANY))
        assertEquals("13:00", peak(use24 = true, locale = Locale.GERMANY))
        assertFalse(DailyRainLabels.localeUses24HourClock(Locale.US))
        assertTrue(DailyRainLabels.localeUses24HourClock(Locale.GERMANY))
        assertTrue(DailyRainLabels.localeUses24HourClock(Locale.UK))
    }

    @Test
    fun `resolved precip carries the peak only when the percent came from hourly data`() {
        val fromRow = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = 20,
            nighttimePrecipProbability = 20,
            precipProbability = 20,
            hourly = emptyList(),
            targetDate = tomorrow.plusDays(4),
            zoneId = zone,
            hourlySummary = DailyHourlySummaries.Summary(dayPrecipMax = 60, nightPrecipMax = 10, dayPrecipPeakAt = at(tomorrow.plusDays(4), 15)),
        )
        assertEquals(60, fromRow.dayPrecip)
        assertEquals(at(tomorrow.plusDays(4), 15), fromRow.dayPeakAt)

        val fromProvider = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = 60,
            nighttimePrecipProbability = 20,
            precipProbability = 60,
            hourly = emptyList(),
            targetDate = tomorrow.plusDays(4),
            zoneId = zone,
        )
        assertEquals(60, fromProvider.dayPrecip)
        assertNull("a provider period value has no hour", fromProvider.dayPeakAt)
    }

    @Test
    fun `resolved precip from stored hours peaks at the plateau's first hour`() {
        val hourly = (0 until 36).map { h ->
            val ms = at(tomorrow, 0) + h * 3_600_000L
            HourlyForecast(
                dateTime = ms,
                temperature = 60f,
                condition = "Rain",
                precipProbability = if (h in 11..14) 40 else 10,
                source = WeatherSource.OPEN_METEO.id,
            )
        }
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = hourly,
            targetDate = tomorrow,
            zoneId = zone,
        )
        assertEquals(40, resolved.dayPrecip)
        assertEquals(at(tomorrow, 11), resolved.dayPeakAt)
    }
}
