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
 * The hour the daytime rain chance peaks, written above the daily rain % when it is > 10 %
 * (user, 2026-10-18, threshold lowered from 30 % on 2026-11-04;
 * `plans/261018-daily-rain-chance-peak-hour.md`).
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
        peakEndAt: Long? = null,
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
        dayPeakEndAt = peakEndAt,
        nowMs = now,
        use24HourClock = use24,
        zoneId = zone,
        locale = locale,
    )

    @Test
    fun `above 10 percent shows the peak hour`() {
        assertEquals("1pm", peak())
        assertEquals("1pm", peak(probability = 11, label = "11%"))
        assertEquals("was hidden under the old 30% threshold", "1pm", peak(probability = 30, label = "30%"))
        assertEquals("1pm", peak(probability = 12, label = "12%"))
    }

    @Test
    fun `10 percent and below shows none`() {
        assertNull(peak(probability = 10, label = "10%"))
        assertNull(peak(probability = 5, label = "5%"))
    }

    @Test
    fun `an amount label or 100 percent shows the peak hour`() {
        assertEquals("≥ 99 % future days showing amount show peak", "1pm", peak(probability = 99, label = ".25in"))
        assertEquals("100% chance forecast at 11am shows 11am", "11am", peak(probability = 100, label = "100%", peakAt = at(tomorrow, 11)))
        assertEquals("100% chance with amount forecast at 11am shows 11am", "11am", peak(probability = 100, label = ".50in", peakAt = at(tomorrow, 11)))
        assertEquals("95% today showing amount shows peak", "11am", peak(date = today, probability = 95, label = ".15in", peakAt = at(today, 11), now = at(today, 9)))
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
    fun `today keeps the first hour while the plateau runs`() {
        // OWM 2026-11-13: 100% from 10am through 1pm; the label vanished at 11:00 before this fix.
        val run = { now: Long -> peak(date = today, probability = 100, label = "100%", peakAt = at(today, 10), peakEndAt = at(today, 13), now = now) }
        assertEquals("first hour of the run", "10am", run(at(today, 11, 0)))
        assertEquals("still the first hour mid-run", "10am", run(at(today, 12, 12)))
        assertEquals("during the run's last hour", "10am", run(at(today, 13, 59)))
        assertNull("after the run ends", run(at(today, 14, 0)))
    }

    @Test
    fun `a one-hour peak or a missing end keeps the hour-long window`() {
        assertEquals("1pm", peak(date = today, peakEndAt = at(today, 13), now = at(today, 13, 59)))
        assertNull(peak(date = today, peakEndAt = at(today, 13), now = at(today, 14, 0)))
        assertNull("an end before the start is ignored", peak(date = today, peakEndAt = at(today, 9), now = at(today, 14, 0)))
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
        assertNull("the run's end is only worked out for today", resolved.dayPeakEndAt)

        val todayResolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = hourly.map { it.copy(dateTime = it.dateTime - 86_400_000L) },
            targetDate = today,
            zoneId = zone,
            today = today,
        )
        assertEquals(at(today, 11), todayResolved.dayPeakAt)
        assertEquals("today's run ends at its last 40% hour", at(today, 14), todayResolved.dayPeakEndAt)
    }

    @Test
    fun `a later separate hour at the max does not extend the run`() {
        val chance = mapOf(10 to 100, 11 to 100, 12 to 90, 13 to 100)
        val hourly = (0 until 36).map { h ->
            HourlyForecast(
                dateTime = at(tomorrow, 0) + h * 3_600_000L,
                temperature = 60f,
                condition = "Rain",
                precipProbability = chance[h] ?: 10,
                source = WeatherSource.OPEN_METEO.id,
            )
        }
        val end = DailyRainLabels.peakPlateauEnd(hourly, at(tomorrow, 10), 100, at(tomorrow, 20))
        println("split run: end=$end")
        assertEquals(at(tomorrow, 11), end)
    }

    @Test
    fun `a run reaching 8pm stops at the last daytime hour`() {
        val hourly = (0 until 36).map { h ->
            HourlyForecast(
                dateTime = at(tomorrow, 0) + h * 3_600_000L,
                temperature = 60f,
                condition = "Rain",
                precipProbability = if (h >= 18) 80 else 10,
                source = WeatherSource.OPEN_METEO.id,
            )
        }
        assertEquals(at(tomorrow, 19), DailyRainLabels.peakPlateauEnd(hourly, at(tomorrow, 18), 80, at(tomorrow, 20)))
        assertNull("a stored peak whose hour is not at the max has no run", DailyRainLabels.peakPlateauEnd(hourly, at(tomorrow, 9), 80, at(tomorrow, 20)))
    }
}
