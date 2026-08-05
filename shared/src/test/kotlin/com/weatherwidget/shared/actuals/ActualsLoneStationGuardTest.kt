package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * Guards the lone-station rule in [ActualTemperatureSeriesBuilder.blendObservationSeries]: a candidate
 * timestamp covered by exactly one station is only emitted when that station is the day's dominant
 * one. Reproduces the 2026-07-01 field bug where LOAC1 (a hills station 8.3 km out with three pre-dawn
 * readings no other station's interpolation reach covered) set the stored NWS daily low to 48.99°F
 * while every official station stayed above 54°F — making the daily bar disagree with the hourly graph.
 */
@Category(ShortDuration::class)
class ActualsLoneStationGuardTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = java.time.LocalDate.parse("2026-06-03")

    // SPARSE mirrors LOAC1: distant, cold, three pre-dawn readings that officials' 3h reach can't
    // cover (their first reading is 09:00, and interpolation needs a reading on BOTH sides).
    private fun sparsePreDawn() = listOf(
        observation("SPARSE", "2026-06-03T04:47:00", 49.5f, distanceKm = 8.3f),
        observation("SPARSE", "2026-06-03T05:17:00", 48.99f, distanceKm = 8.3f),
        observation("SPARSE", "2026-06-03T05:47:00", 49.8f, distanceKm = 8.3f),
    )

    private fun officialDaytime(): List<ObservationReading> {
        val nearTemps = listOf(54.0f, 56f, 60f, 64f, 68f, 72f, 75f, 74f, 70f, 66f, 62f, 58f)
        return nearTemps.flatMapIndexed { index, temp ->
            val time = "2026-06-03T%02d:00:00".format(9 + index)
            listOf(
                observation("NEAR", time, temp, distanceKm = 2.2f),
                observation("OFFICIAL_1", time, temp + 0.5f, distanceKm = 4f),
            )
        }
    }

    @Test
    fun `lone sparse station cannot set the daily low when other stations report that day`() {
        val obs = sparsePreDawn() + officialDaytime()
        val forecasts = forecasts("2026-06-03T00:00:00", 24)

        val daily = ActualsAggregator.aggregate(
            observations = obs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id }

        assertTrue("low ${daily.computedLowTemp} should come from the officials, not the lone 48.99 outlier", daily.computedLowTemp >= 54f)
        assertTrue("low ${daily.computedLowTemp} should stay near the officials' 54.0 floor", daily.computedLowTemp <= 55f)
        assertTrue("high ${daily.computedHighTemp} unaffected by the guard", daily.computedHighTemp in 74f..76f)

        val stats = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = epoch("2026-06-03T00:00:00"),
            endMs = epoch("2026-06-04T00:00:00"),
            zoneId = zone,
        ).stats
        assertEquals("all three sole-coverage pre-dawn candidates skipped", 3, stats.loneStationSkippedCount)
    }

    @Test
    fun `hourly build and daily aggregate agree on the low when a lone station is suppressed`() {
        val obs = sparsePreDawn() + officialDaytime()
        val forecasts = forecasts("2026-06-03T00:00:00", 24)

        val daily = ActualsAggregator.aggregate(
            observations = obs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == WeatherSource.NWS.id }

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = obs,
            centerTime = day.atTime(12, 0),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = 12,
            forwardHours = 12,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-05T12:00:00"),
            zoneId = zone,
        )

        val actualTemps = result.points.filter { it.isActual && it.actualTemp != null }.map { it.actualTemp!! }
        assertTrue(actualTemps.isNotEmpty())
        assertEquals("hourly graph min must equal the stored daily low", daily.computedLowTemp, actualTemps.min(), 0.001f)
        assertEquals("hourly graph max must equal the stored daily high", daily.computedHighTemp, actualTemps.max(), 0.001f)
    }

    @Test
    fun `sparse station blends instead of standing alone once neighbors gain coverage`() {
        // NEAR now brackets the pre-dawn hours (04:00 and 06:30, a 2.5h gap within interpolation
        // reach), so SPARSE's readings are corroborated: emitted again, but IDW-diluted toward NEAR.
        val obs = sparsePreDawn() + officialDaytime() + listOf(
            observation("NEAR", "2026-06-03T04:00:00", 55.5f, distanceKm = 2.2f),
            observation("NEAR", "2026-06-03T06:30:00", 54.5f, distanceKm = 2.2f),
        )
        val forecasts = forecasts("2026-06-03T00:00:00", 24)

        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = epoch("2026-06-03T00:00:00"),
            endMs = epoch("2026-06-04T00:00:00"),
            zoneId = zone,
        )

        assertEquals(0, result.stats.loneStationSkippedCount)
        val preDawnTrough = result.observations.single { it.timestamp == epoch("2026-06-03T05:17:00") }
        assertTrue("48.99 outlier should be diluted toward NEAR, got ${preDawnTrough.temperature}", preDawnTrough.temperature in 53f..56f)
        assertTrue("no emitted point retains the raw outlier", result.observations.all { it.temperature > 53f })
    }

    @Test
    fun `dominant station keeps solo coverage when the other station is mostly offline`() {
        // The rejected bare "require two candidates" rule would gut this day: NEAR reports all 24
        // hours, FAR only once at midday. NEAR's sole-coverage hours must survive (it is dominant).
        val obs = (0..23).map { hour ->
            observation("NEAR", "2026-06-03T%02d:00:00".format(hour), 60f + hour, distanceKm = 2.2f)
        } + observation("FAR", "2026-06-03T12:37:00", 80f, distanceKm = 12f)
        val forecasts = forecasts("2026-06-03T00:00:00", 24)

        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = epoch("2026-06-03T00:00:00"),
            endMs = epoch("2026-06-04T00:00:00"),
            zoneId = zone,
        )

        assertEquals(0, result.stats.loneStationSkippedCount)
        assertEquals(epoch("2026-06-03T00:00:00"), result.observations.first().timestamp)
        assertEquals(epoch("2026-06-03T23:00:00"), result.observations.last().timestamp)
        assertEquals("every candidate timestamp emitted", 25, result.observations.size)
    }

    @Test
    fun `single station source still yields daily extremes`() {
        val obs = (6..20).map { hour ->
            observation(
                "OPEN_METEO_MAIN",
                "2026-06-03T%02d:00:00".format(hour),
                50f + hour,
                api = WeatherSource.OPEN_METEO.id,
                distanceKm = 0f,
            )
        }
        val forecasts = forecasts("2026-06-03T00:00:00", 24, source = WeatherSource.OPEN_METEO.id)

        val daily = ActualsAggregator.aggregate(
            observations = obs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).single { it.source == WeatherSource.OPEN_METEO.id }

        assertEquals(56f, daily.computedLowTemp, 0.001f)
        assertEquals(70f, daily.computedHighTemp, 0.001f)
    }

    private fun forecasts(start: String, count: Int, source: String = WeatherSource.NWS.id): List<HourlyForecast> {
        val startTime = LocalDateTime.parse(start)
        return (0..count).map { index ->
            val time = startTime.plusHours(index.toLong())
            HourlyForecast(
                dateTime = time.atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f + index,
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun observation(
        stationId: String,
        time: String,
        temperature: Float,
        api: String = WeatherSource.NWS.id,
        distanceKm: Float,
        stationType: String = "OFFICIAL",
    ): ObservationReading =
        ObservationReading(
            stationId = stationId,
            stationName = stationId,
            timestamp = epoch(time),
            temperature = temperature,
            condition = "observed",
            locationLat = LAT,
            locationLon = LON,
            distanceKm = distanceKm,
            api = api,
            stationType = stationType,
        )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
    }
}
