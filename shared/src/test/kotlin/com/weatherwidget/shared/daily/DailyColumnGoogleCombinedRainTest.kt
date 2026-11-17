package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.shared.util.CombinedRainChance
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Google's daily windows show the chance of rain in at least one hour once the window's max reaches
 * 85%, WeatherAPI's from 90%, Tomorrow.io's from 85% (plans/261117-google-combined-daily-rain-chance.md). The fixture is Google's 2026-11-17 11:11
 * fetch for 11-18: 85% from 8am to noon at 5.8–12.6 mm/h, "Heavy Rain", while the label read 85%.
 */
@Category(ShortDuration::class)
class DailyColumnGoogleCombinedRainTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 17)
    private val tomorrow = today.plusDays(1)
    private val now = today.atTime(13, 6)
    private val google = WeatherSource.GOOGLE_WEATHER.id
    private val nws = WeatherSource.NWS.id
    private val wapi = WeatherSource.WEATHER_API.id
    private val tio = WeatherSource.TOMORROW_IO.id
    private val lat = 37.422
    private val lon = -122.084
    private val hour = 3_600_000L

    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Google 11-18, hour → (chance %, mm). */
    private val storm = mapOf(
        0 to (10 to 0f), 1 to (10 to 0f), 2 to (10 to 0f), 3 to (25 to 0.2f), 4 to (40 to 0.5f),
        5 to (65 to 1.4f), 6 to (70 to 2.0f), 7 to (75 to 3.4f), 8 to (85 to 5.8f), 9 to (85 to 7.7f),
        10 to (85 to 9.2f), 11 to (85 to 12.6f), 12 to (85 to 6.9f), 13 to (75 to 4.9f), 14 to (75 to 3.7f),
        15 to (70 to 1.7f), 16 to (55 to 1.0f), 17 to (45 to 0.7f), 18 to (15 to 0.1f), 19 to (15 to 0.1f),
        20 to (20 to 0.2f), 21 to (35 to 0.4f), 22 to (30 to 0.4f), 23 to (35 to 0.4f),
    )

    /** [day] 00:00 through the next day's 08:00, from [chance] (the next morning at 30%, 0.3 mm). */
    private fun hours(day: LocalDate, source: String, chance: (Int) -> Pair<Int, Float>) =
        generateSequence(at(day, 0)) { it + hour }.takeWhile { it < at(day.plusDays(1), 8) }.map { ms ->
            val local = java.time.Instant.ofEpochMilli(ms).atZone(zone)
            val (p, mm) = if (local.toLocalDate() == day) chance(local.hour) else 30 to 0.3f
            HourlyForecast(
                dateTime = ms, temperature = 58f, condition = "Rain", precipProbability = p, precipAmountMm = mm,
                source = source, locationLat = lat, locationLon = lon,
            )
        }.toList()

    private val stormHours = hours(tomorrow, google) { storm.getValue(it) }

    /** The row as a fetch stores it: the summary from the same download. */
    private fun row(source: String, hourly: List<HourlyForecast>, date: LocalDate = tomorrow) =
        DailyForecast(
            date = date.toString(), highTemp = 60f, lowTemp = 52f, condition = "Heavy Rain",
            precipProbability = 85, precipAmountMm = 73.1f, source = source,
            daytimePrecipProbability = 85, nighttimePrecipProbability = 75,
        ).withHourlySummary(DailyHourlySummaries.forDate(hourly, date, source, zone))

    private fun column(forecast: DailyForecast, hourly: List<HourlyForecast>, source: String = google, date: LocalDate = tomorrow, at: LocalDateTime = now) =
        DailyColumnBuilder.build(
            date = date, label = "x", today = at.toLocalDate(), now = at, forecast = forecast,
            actual = null, snapshots = emptyList(), hourly = hourly, currentTemp = null,
            displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
        ).also {
            println("$source $date: day=${it.dayPrecipProbability} label=${it.dailyRainLabelText} peak=${it.dailyRainPeakLabelText} night=${it.nightPrecipProbability}")
        }

    @Test
    fun `combine is the chance of rain in at least one hour`() {
        assertEquals("4 x 85% = 99.95%", 100, CombinedRainChance.combine(listOf(85, 85, 85, 85)))
        assertEquals("12 x 10% = 71.8%", 72, CombinedRainChance.combine(List(12) { 10 }))
        assertEquals(50, CombinedRainChance.combine(listOf(50)))
        assertEquals(0, CombinedRainChance.combine(listOf(0, 0)))
        assertNull(CombinedRainChance.combine(emptyList()))
    }

    @Test
    fun `shown is the combined chance for Google only, from a max of 85`() {
        assertEquals(100, CombinedRainChance.shown(google, max = 85, combined = 100))
        assertEquals("below the threshold the max stands", 84, CombinedRainChance.shown(google, max = 84, combined = 99))
        assertEquals("twelve 10% hours stay 10%", 10, CombinedRainChance.shown(google, max = 10, combined = 72))
        assertEquals("other sources keep the max", 85, CombinedRainChance.shown(nws, max = 85, combined = 100))
        assertEquals("a row without the column keeps the max", 85, CombinedRainChance.shown(google, max = 85, combined = null))
        assertNull(CombinedRainChance.shown(google, max = null, combined = 90))
    }

    @Test
    fun `WeatherAPI shows the combined chance from 90, Google still from 85`() {
        assertEquals(100, CombinedRainChance.shown(wapi, max = 90, combined = 100))
        assertEquals("WeatherAPI below 90 keeps the max", 89, CombinedRainChance.shown(wapi, max = 89, combined = 100))
        assertEquals("Google's cutoff is its own", 100, CombinedRainChance.shown(google, max = 85, combined = 100))
    }

    /** Tomorrow.io 11-18 (15:30 fetch): 85% at 8am, 80% at 9am (13 mm); the label read 85%. */
    @Test
    fun `Tomorrow_io's 85 percent day reads its amount, its 55 percent night keeps the max`() {
        val tioChance = listOf(0, 0, 0, 0, 35, 0, 40, 60, 85, 80, 75, 75, 0, 0, 0, 0, 0, 0, 45, 40, 0, 0, 0, 0)
        val tioMm = listOf(0f, 0f, 0f, 0f, 0f, 0f, .8f, .8f, 5.1f, 13.0f, 4.3f, 4.6f, .5f, 0f, .5f, 0f, .8f, 0f, 0f, 0f, .3f, 0f, 0f, 0f)
        val hourly = hours(tomorrow, tio) { tioChance[it] to tioMm[it] }.map {
            if (it.dateTime >= at(tomorrow.plusDays(1), 0)) it.copy(precipProbability = 55) else it
        }
        assertEquals("Tomorrow.io below 85 keeps the max", 80, CombinedRainChance.shown(tio, max = 80, combined = 100))
        val c = column(row(tio, hourly), hourly, source = tio)
        assertEquals(100, c.dayPrecipProbability)
        assertEquals(DailyRainLabels.formatPrecipAmount(tioMm.subList(8, 20).sum()), c.dailyRainLabelText)
        assertEquals("8am", c.dailyRainPeakLabelText)
        assertEquals("night max 55 < 85", 55, c.nightPrecipProbability)
    }

    /** WeatherAPI 11-18 (15:30 fetch): 84–90% from 7am to 2pm, "Moderate rain"; the label read 90%. */
    @Test
    fun `WeatherAPI's 90 percent day reads its amount, its 84 percent night keeps the max`() {
        val wapiChance = listOf(19, 20, 25, 29, 38, 64, 74, 84, 88, 90, 90, 89, 90, 86, 89, 50, 34, 67, 36, 44, 62, 62, 70, 69)
        val wapiMm = listOf(0f, 0f, .1f, .1f, .2f, 1.1f, 2.1f, 4.1f, 5.7f, 6.0f, 6.9f, 4.8f, 5.4f, 2.7f, 4.4f, .3f, 0f, .6f, .1f, .3f, .8f, .8f, 1.1f, 1.1f)
        val hourly = hours(tomorrow, wapi) { wapiChance[it] to wapiMm[it] }.map {
            // The next morning at 84%, as WeatherAPI's night max.
            if (it.dateTime >= at(tomorrow.plusDays(1), 0)) it.copy(precipProbability = 84) else it
        }
        val c = column(row(wapi, hourly), hourly, source = wapi)
        assertEquals(100, c.dayPrecipProbability)
        assertEquals(DailyRainLabels.formatPrecipAmount(wapiMm.subList(8, 20).sum()), c.dailyRainLabelText)
        assertEquals("9am", c.dailyRainPeakLabelText)
        assertEquals("night max 84 < 90", 84, c.nightPrecipProbability)
    }

    @Test
    fun `the stored summary keeps each window's combined chance beside its max`() {
        val s = DailyHourlySummaries.forDate(stormHours, tomorrow, google, zone)
        println("summary: $s")
        assertEquals(85, s.dayPrecipMax)
        assertEquals(100, s.dayPrecipCombined)
        assertEquals(35, s.nightPrecipMax)
        assertEquals(CombinedRainChance.combine(listOf(20, 35, 30, 35) + List(8) { 30 }), s.nightPrecipCombined)
    }

    @Test
    fun `carryForward moves a window's combined chance with its max`() {
        val prior = DailyHourlySummaries.Summary(dayPrecipMax = 85, nightPrecipMax = 40, dayPrecipCombined = 100, nightPrecipCombined = 90)
        val partial = DailyHourlySummaries.Summary(nightPrecipMax = 20, nightPrecipCombined = 60)
        val merged = DailyHourlySummaries.carryForward(partial, prior)
        assertEquals(85, merged.dayPrecipMax)
        assertEquals(100, merged.dayPrecipCombined)
        assertEquals(20, merged.nightPrecipMax)
        assertEquals(60, merged.nightPrecipCombined)
    }

    @Test
    fun `Google's 85 percent storm day reads its 8am-8pm amount, the peak hour unchanged`() {
        val c = column(row(google, stormHours), stormHours)
        assertEquals(100, c.dayPrecipProbability)
        // 100% ≥ 99%: the existing rule shows the 8am–8pm amount (54.4 mm), not the %.
        assertEquals(DailyRainLabels.formatPrecipAmount(54.4f), c.dailyRainLabelText)
        assertEquals("the first hour at the max", "8am", c.dailyRainPeakLabelText)
        assertEquals("the night max is under 85%: kept", 35, c.nightPrecipProbability)
    }

    @Test
    fun `beyond the stored hours the row's combined chance is shown`() {
        val c = column(row(google, stormHours), hourly = emptyList())
        assertEquals(100, c.dayPrecipProbability)
    }

    @Test
    fun `a row written before the column keeps the max`() {
        val old = row(google, stormHours).copy(hourlyDayPrecipCombined = null, hourlyNightPrecipCombined = null)
        val c = column(old, hourly = emptyList())
        assertEquals(85, c.dayPrecipProbability)
        assertEquals("85%", c.dailyRainLabelText)
    }

    @Test
    fun `NWS on the same hours keeps the max`() {
        val nwsHours = stormHours.map { it.copy(source = nws) }
        val c = column(row(nws, nwsHours), nwsHours, source = nws)
        assertEquals(85, c.dayPrecipProbability)
        assertEquals("85%", c.dailyRainLabelText)
    }

    @Test
    fun `a Google drizzle-floor day stays at its max`() {
        val dry = hours(tomorrow, google) { 10 to 0f }
        val c = column(row(google, dry), dry)
        assertEquals(10, c.dayPrecipProbability)
        assertEquals("10%", c.dailyRainLabelText)
    }

    @Test
    fun `today combines the stitched hours`() {
        val todayHours = hours(today, google) { storm.getValue(it) }
        // The row predates the storm (no summary): today reads the stitched hours the graph draws.
        val stale = DailyForecast(
            date = today.toString(), highTemp = 60f, lowTemp = 52f, condition = "Rain",
            precipProbability = 20, source = google, daytimePrecipProbability = 20,
        )
        val c = column(stale, todayHours, date = today, at = today.atTime(7, 0))
        assertEquals(100, c.dayPrecipProbability)
    }

    @Test
    fun `the source-comparison popup shows the same combined chance`() {
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        fun stored(source: String, hourly: List<HourlyForecast>): DaySourceComparison.StoredRow {
            val f = row(source, hourly).copy(fetchedAt = nowMs - hour, lastConfirmedAt = nowMs - hour)
            val s = DailyForecastSnapshot(
                date = f.date, highTemp = f.highTemp, lowTemp = f.lowTemp, condition = f.condition,
                precipProbability = f.precipProbability, fetchedAt = nowMs - hour, lastConfirmedAt = nowMs - hour,
            )
            return DaySourceComparison.StoredRow(f, s, lat, lon)
        }
        val nwsHours = stormHours.map { it.copy(source = nws) }
        val rows = DaySourceComparison.build(
            date = tomorrow, now = now, lat = lat, lon = lon,
            sources = listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS),
            stored = listOf(stored(google, stormHours), stored(nws, nwsHours)),
            history = emptyList(), hourly = stormHours + nwsHours, highBias = emptyMap(),
            use24HourClock = false, zone = zone,
        ).associateBy { it.source.id }
        println("popup: " + rows.values.joinToString { "${it.source.id} day=${it.dayRainPercent} peak=${it.dayPeakLabel}" })
        assertEquals(100, rows.getValue(google).dayRainPercent)
        assertEquals(85, rows.getValue(nws).dayRainPercent)
    }
}
