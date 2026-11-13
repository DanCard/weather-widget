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
 * `plans/261018-daily-rain-chance-peak-hour.md`). Shown on every day whatever the clock: past days
 * replay the hour frozen with their chance (`plans/261113-today-rain-peak-never-hidden.md`).
 */
@Category(ShortDuration::class)
class DailyRainPeakLabelTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 18)
    private val tomorrow = today.plusDays(1)

    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun peak(
        label: String? = "45%",
        probability: Int? = 45,
        peakAt: Long? = at(tomorrow, 13),
        use24: Boolean = false,
        locale: Locale = Locale.US,
    ) = DailyRainLabels.buildDailyRainPeakLabel(
        dailyRainLabelText = label,
        dayPrecipProbability = probability,
        dayPeakAt = peakAt,
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
        assertEquals("95% today showing amount shows peak", "11am", peak(probability = 95, label = ".15in", peakAt = at(today, 11)))
    }

    @Test
    fun `no label or no peak shows none`() {
        assertNull(peak(label = null))
        assertNull(peak(peakAt = null))
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
    }

    private fun hoursOf(day: LocalDate, chance: (Int) -> Int) = (0 until 36).map { h ->
        HourlyForecast(
            dateTime = at(day, 0) + h * 3_600_000L,
            temperature = 60f,
            condition = "Rain",
            precipProbability = chance(h),
            source = WeatherSource.NWS.id,
        )
    }

    private fun past(storedChance: Int?, storedPeakAt: Long?, hourly: List<HourlyForecast> = emptyList(), daytime: Int? = null) =
        DailyRainLabels.resolveDailyLabelPrecip(
            isPast = true,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = daytime,
            nighttimePrecipProbability = null,
            precipProbability = null,
            hourly = hourly,
            targetDate = today.minusDays(1),
            zoneId = zone,
            storedDayPrecipChance = storedChance,
            storedDayPrecipPeakAt = storedPeakAt,
        )

    @Test
    fun `a past day replays the hour frozen with its chance`() {
        val yesterday = today.minusDays(1)
        val r = past(storedChance = 99, storedPeakAt = at(yesterday, 12))
        println("past frozen: day=${r.dayPrecip} peakAt=${r.dayPeakAt}")
        assertEquals(99, r.dayPrecip)
        assertEquals(at(yesterday, 12), r.dayPeakAt)
        assertEquals("12pm", peak(probability = r.dayPrecip, label = "99%", peakAt = r.dayPeakAt))
    }

    @Test
    fun `a past row frozen before the column borrows the hours' peak only when their max matches`() {
        val yesterday = today.minusDays(1)
        // NWS 2026-11-13 shape: 97 at 11am, 99 at noon, 89 at 1pm.
        val hours = hoursOf(yesterday) { h -> mapOf(11 to 97, 12 to 99, 13 to 89)[h] ?: 30 }
        val matching = past(storedChance = 99, storedPeakAt = null, hourly = hours)
        println("past pre-column matching: peakAt=${matching.dayPeakAt}")
        assertEquals(at(yesterday, 12), matching.dayPeakAt)
        val different = past(storedChance = 100, storedPeakAt = null, hourly = hours)
        assertNull("never an hour belonging to a different %", different.dayPeakAt)
    }

    @Test
    fun `a past provider period value has no hour`() {
        val r = past(storedChance = null, storedPeakAt = null, daytime = 60)
        assertEquals(60, r.dayPrecip)
        assertNull(r.dayPeakAt)
    }
}
