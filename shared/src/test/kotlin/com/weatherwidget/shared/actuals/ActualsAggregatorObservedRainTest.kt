package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertNull
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.observations.ActualsProviderResolver
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Integration: [ActualsAggregator] files a wet day's rain through [ObservedPrecip], so three
 * airports that caught the same two showers give the showers' amount, not three times it
 * (plans/261021-observed-rain-combine-stations-not-sum.md).
 */
@Category(ShortDuration::class)
class ActualsAggregatorObservedRainTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.now(zone).minusDays(3)
    private val p0012Mm = 0.12f * 25.4f
    private val p0004Mm = 0.04f * 25.4f

    private fun station(id: String, distanceKm: Float, minute: Int): List<ObservationReading> =
        (0..23).map { h ->
            val p = when (h) {
                14 -> " P0012" // a daytime shower
                22 -> " P0004" // a night shower
                else -> ""
            }
            ObservationReading(
                stationId = id,
                stationName = id,
                timestamp = LocalDateTime.of(day, LocalTime.of(h, minute)).atZone(zone).toInstant().toEpochMilli(),
                temperature = 55f + h % 12,
                condition = if (p.isEmpty()) "Cloudy" else "Rain",
                locationLat = 37.42,
                locationLon = -122.08,
                distanceKm = distanceKm,
                api = WeatherSource.NWS.id,
                precipAmountMm = when (h) { 14 -> p0012Mm; 22 -> p0004Mm; else -> null },
                isMetar = true,
                rawMetar = "$id ${day.dayOfMonth}${"%02d".format(h)}${"%02d".format(minute)}Z AUTO 00000KT 10SM OVC020 15/12 A2990 RMK AO2$p",
            )
        }

    @Test
    fun `a three-station wet day files the showers once`() {
        val rows = station("KNUQ", 3.8f, 55) + station("KPAO", 6.1f, 47) + station("KSJC", 15.9f, 53)
        val nws = ActualsAggregator.aggregate(
            observations = rows,
            hourlyForecasts = emptyList(),
            locationLat = 37.42,
            locationLon = -122.08,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id && it.date == day.toEpochDay() * 86_400_000L }

        assertNotNull(nws.precipAmountMm)
        assertEquals(p0012Mm + p0004Mm, nws.precipAmountMm!!, 0.001f) // the old code: 3x = 12.19 mm
        assertEquals(p0012Mm, nws.precipDayMm!!, 0.001f)
        assertEquals(p0004Mm, nws.precipNightMm!!, 0.001f)
    }

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun borrowNws() = ActualsProviderResolver.installPreferenceSource { source ->
        WeatherSource.NWS.takeIf { source == WeatherSource.OPEN_METEO || source == WeatherSource.GOOGLE_WEATHER }
    }

    /**
     * Groups that borrow NWS's rows reduce the wet day's rain once, and each still files the same
     * totals as the provider. The old code ran [ObservedPrecip.daily] once per group per date
     * (Pixel 7 Pro recompute: precipMs as large as the series blend).
     * performance/261118-cold-start-daily-actuals-cost.md
     */
    @Test
    fun `borrowing groups share one rain reduction per date with equal totals`() {
        borrowNws()
        val rows = station("KNUQ", 3.8f, 55) + station("KPAO", 6.1f, 47) + station("KSJC", 15.9f, 53)
        val stats = ActualsAggregator.AggregateStats()

        val all = ActualsAggregator.aggregate(
            observations = rows, hourlyForecasts = emptyList(), locationLat = 37.42, locationLon = -122.08,
            zoneId = zone, stats = stats,
        ).filter { it.date == day.toEpochDay() * 86_400_000L }

        println("ActualsAggregatorObservedRainTest: ${stats.logFragment()} sources=${all.map { it.source }}")
        assertEquals(setOf("NWS", "OPEN_METEO", "GOOGLE_WEATHER"), all.map { it.source }.toSet())
        all.forEach { row ->
            assertEquals(row.source, p0012Mm + p0004Mm, row.precipAmountMm!!, 0.001f)
            assertEquals(row.source, p0012Mm, row.precipDayMm!!, 0.001f)
            assertEquals(row.source, p0004Mm, row.precipNightMm!!, 0.001f)
        }
        assertEquals("three groups, one date: one reduction", 1, stats.rainReductions)
        assertEquals(3, stats.datesBlended)
    }

    /** Tomorrow.io's five-minute history: temperature only — Tomorrow.io rejects rain fields at 5 m. */
    private fun tomorrowFiveMinute(): List<ObservationReading> =
        (0 until 24 * 12).map { i ->
            ObservationReading(
                stationId = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID,
                stationName = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_NAME,
                timestamp = LocalDateTime.of(day, LocalTime.of(i / 12, (i % 12) * 5)).atZone(zone).toInstant().toEpochMilli(),
                temperature = 40f + (i / 12) % 12, // its own curve: 40–51°, not the stations' 55–66°
                condition = "Cloudy",
                locationLat = 37.42,
                locationLon = -122.08,
                distanceKm = 0f,
                api = WeatherSource.TOMORROW_IO.id,
            )
        }

    /**
     * Desktop 2026-11-20: Tomorrow.io's past rows held NULL rain beside the gauges' 46 mm, so the daily
     * label fell back to "100%". Its feed has no rain; its rain now comes from the gauges (NWS inside
     * coverage) while its high/low stay its own (plans/261120-rain-actuals-from-gauges-for-rainless-feeds.md).
     */
    @Test
    fun `a feed without rain takes the gauges' rain and keeps its own temperatures`() {
        val stations = station("KNUQ", 3.8f, 55) + station("KPAO", 6.1f, 47)
        val stats = ActualsAggregator.AggregateStats()
        val rows = ActualsAggregator.aggregate(
            observations = stations + tomorrowFiveMinute(), hourlyForecasts = emptyList(),
            locationLat = 37.42, locationLon = -122.08, zoneId = zone, stats = stats,
        ).filter { it.date == day.toEpochDay() * 86_400_000L }
        val tomorrow = rows.single { it.source == WeatherSource.TOMORROW_IO.id }
        val nws = rows.single { it.source == WeatherSource.NWS.id }
        println("rainless feed: tmrw rain=${tomorrow.precipAmountMm}/${tomorrow.precipDayMm}/${tomorrow.precipNightMm} " +
            "high=${tomorrow.computedHighTemp} nws high=${nws.computedHighTemp} ${stats.logFragment()}")

        assertEquals(p0012Mm + p0004Mm, tomorrow.precipAmountMm!!, 0.001f) // was null
        assertEquals(p0012Mm, tomorrow.precipDayMm!!, 0.001f)
        assertEquals(p0004Mm, tomorrow.precipNightMm!!, 0.001f)
        assertEquals(51f, tomorrow.computedHighTemp!!, 0.5f) // its own five-minute curve
        assertEquals(66f, nws.computedHighTemp!!, 0.5f)
        assertEquals("Tomorrow.io shares NWS's one reduction", 1, stats.rainReductions)
    }

    /** The shared part is the OBSERVED rain only: with none measured, each source falls back to its own forecast. */
    @Test
    fun `a dry provider still gives each borrower its own forecast fallback`() {
        borrowNws()
        val today = LocalDate.now(zone)
        val dry = (0..10).map { h ->
            ObservationReading(
                stationId = "KNUQ", stationName = "KNUQ",
                timestamp = LocalDateTime.of(today, LocalTime.of(h, 55)).atZone(zone).toInstant().toEpochMilli(),
                temperature = 55f + h, condition = "Cloudy", locationLat = 37.42, locationLon = -122.08,
                distanceKm = 3.8f, api = WeatherSource.NWS.id, isMetar = true,
                // No AO2 and no P group: says nothing about rain, so nothing is measured.
                rawMetar = "KNUQ ${today.dayOfMonth}${"%02d".format(h)}55Z 00000KT 10SM OVC020 15/12 A2990",
            )
        }
        fun forecast(source: WeatherSource, mm: Float) = (0..23).map { h ->
            HourlyForecast(
                dateTime = LocalDateTime.of(today, LocalTime.of(h, 0)).atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f, condition = "Rain", source = source.id, precipAmountMm = if (h == 12) mm else 0f,
            )
        }
        val stats = ActualsAggregator.AggregateStats()

        val rows = ActualsAggregator.aggregate(
            observations = dry,
            hourlyForecasts = forecast(WeatherSource.NWS, 1f) + forecast(WeatherSource.OPEN_METEO, 2f) +
                forecast(WeatherSource.GOOGLE_WEATHER, 3f),
            locationLat = 37.42, locationLon = -122.08, zoneId = zone, stats = stats,
        ).filter { it.date == today.toEpochDay() * 86_400_000L }.associateBy { it.source }

        println("ActualsAggregatorObservedRainTest: dry ${stats.logFragment()} " + rows.mapValues { it.value.precipAmountMm })
        assertEquals(1f, rows.getValue("NWS").precipAmountMm!!, 0.001f)
        assertEquals(2f, rows.getValue("OPEN_METEO").precipAmountMm!!, 0.001f)
        assertEquals(3f, rows.getValue("GOOGLE_WEATHER").precipAmountMm!!, 0.001f)
        assertEquals(1, stats.rainReductions)
    }

    @Test
    fun `a restricted call builds only the listed groups`() {
        borrowNws()
        val rows = station("KNUQ", 3.8f, 55)
        val stats = ActualsAggregator.AggregateStats()

        val kept = ActualsAggregator.aggregate(
            observations = rows, hourlyForecasts = emptyList(), locationLat = 37.42, locationLon = -122.08,
            zoneId = zone, stats = stats, sources = setOf("GOOGLE_WEATHER"),
        )

        assertEquals(setOf("GOOGLE_WEATHER"), kept.map { it.source }.toSet())
        assertEquals(1, stats.groups)
        assertNull(kept.firstOrNull { it.source == "NWS" })
    }
}
