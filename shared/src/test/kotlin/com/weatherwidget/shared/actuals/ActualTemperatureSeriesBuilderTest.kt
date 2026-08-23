package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ActualTemperatureSeriesBuilderTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val center = LocalDateTime.parse("2026-06-03T12:00:00")

    @Test
    fun `cached silurian synthetic rows never become temperature actuals`() {
        val observations = listOf(
            observation(
                "SILURIAN_MAIN",
                "2026-06-03T11:00:00",
                91f,
                api = WeatherSource.SILURIAN.id,
                distanceKm = 0f,
            ),
        )
        val forecasts = forecasts(
            "2026-06-03T08:00:00",
            8,
            source = WeatherSource.SILURIAN.id,
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = center,
            displaySourceId = WeatherSource.SILURIAN.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-03T12:30:00"),
            zoneId = zone,
        )

        assertNull(result.selectedStationId)
        assertTrue(result.points.none { it.isActual || it.actualTemp != null })
        assertTrue(
            ActualsAggregator.aggregate(
                observations = observations,
                hourlyForecasts = forecasts,
                locationLat = LAT,
                locationLon = LON,
                zoneId = zone,
            ).isEmpty(),
        )
    }

    @Test
    fun `build injects blended actuals and carries latest actual across past hours`() {
        val forecasts = forecasts("2026-06-03T08:00:00", 8)
        val observations = listOf(
            observation("S_NEAR", "2026-06-03T10:10:00", 70f, distanceKm = 2f),
            observation("S_FAR", "2026-06-03T10:10:00", 80f, distanceKm = 10f),
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = center,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-03T12:30:00"),
            zoneId = zone,
        )

        val actualPoint = result.points.single { it.timeMs == epoch("2026-06-03T10:10:00") }
        assertTrue(actualPoint.isActual)
        assertTrue(actualPoint.isObservedActual)
        assertEquals(70.38f, actualPoint.actualTemp!!, 0.05f)

        val carriedNoon = result.points.single { it.timeMs == epoch("2026-06-03T12:00:00") }
        assertTrue(carriedNoon.isActual)
        assertFalse(carriedNoon.isObservedActual)
        assertEquals(actualPoint.actualTemp, carriedNoon.actualTemp!!, 0.001f)
    }

    @Test
    fun `non NWS source selects the station with best coverage before building actuals`() {
        val forecasts = forecasts("2026-06-03T08:00:00", 8, source = WeatherSource.WEATHER_API.id)
        val observations = listOf(
            observation("ONE", "2026-06-03T10:00:00", 60f, api = WeatherSource.WEATHER_API.id, distanceKm = 1f),
            observation("TWO", "2026-06-03T10:00:00", 80f, api = WeatherSource.WEATHER_API.id, distanceKm = 2f),
            observation("TWO", "2026-06-03T11:00:00", 82f, api = WeatherSource.WEATHER_API.id, distanceKm = 2f),
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = center,
            displaySourceId = WeatherSource.WEATHER_API.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = LocalDateTime.parse("2026-06-03T12:30:00"),
            zoneId = zone,
        )

        assertEquals("TWO", result.selectedStationId)
        assertEquals(80f, result.points.single { it.timeMs == epoch("2026-06-03T10:00:00") }.actualTemp!!, 0.001f)
    }

    @Test
    fun `single-day build reproduces daily aggregate high and low exactly`() {
        // A full day of NWS observations across two stations, sub-hourly, with a sharp afternoon peak
        // and an overnight trough at off-hour minutes (the kind the 5-min dedup-thinning can clip). The
        // day-bounded build must reproduce ActualsAggregator's stored daily high/low for that day, so the
        // hourly graph's labeled actual extrema match the daily bar regardless of how thinning lands.
        val day = java.time.LocalDate.parse("2026-06-03")
        val obs = listOf(
            observation("S_NEAR", "2026-06-03T01:00:00", 60f, distanceKm = 2f),
            observation("S_NEAR", "2026-06-03T05:53:00", 57.2f, distanceKm = 2f),
            observation("S_NEAR", "2026-06-03T10:00:00", 66f, distanceKm = 2f),
            observation("S_FAR", "2026-06-03T15:05:00", 74f, distanceKm = 12f),
            observation("S_NEAR", "2026-06-03T15:07:00", 77.4f, distanceKm = 2f),
            observation("S_NEAR", "2026-06-03T20:00:00", 68f, distanceKm = 2f),
            observation("S_NEAR", "2026-06-03T23:30:00", 62f, distanceKm = 2f),
        )
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
        assertEquals("graph high should equal daily_history high", daily.computedHighTemp!!, actualTemps.max(), 0.001f)
        assertEquals("graph low should equal daily_history low", daily.computedLowTemp!!, actualTemps.min(), 0.001f)
    }

    @Test
    fun `hourly per-day extrema match the daily aggregate across a multi-day window`() {
        // Cross-pipeline convergence guard: run ONE observation set through BOTH the daily-bar path
        // (ActualsAggregator.aggregate) and the full hourly path (build -> assembleHourData ->
        // TemperatureExtrema), and assert the hourly graph's labeled per-day actual high/low equals the
        // daily bar's high/low for each fully-visible past day. Sub-5-min peak/trough clusters + a
        // prior-day observation make the old 5-min greedy dedup thin the two windows out of phase
        // (different max/min); blending at every timestamp makes them agree by construction.
        val forecasts = forecasts("2026-06-02T00:00:00", 96)
        val obs = listOf(
            observation("S", "2026-06-02T23:58:00", 62f, distanceKm = 2f), // prior day -> shifts dedup phase
            // 2026-06-03 (true low 53 @05:03, true high 81 @14:03 — both off the 5-min grid)
            observation("S", "2026-06-03T00:01:00", 61f, distanceKm = 2f),
            observation("S", "2026-06-03T05:00:00", 54f, distanceKm = 2f),
            observation("S", "2026-06-03T05:03:00", 53f, distanceKm = 2f),
            observation("S", "2026-06-03T14:00:00", 79f, distanceKm = 2f),
            observation("S", "2026-06-03T14:03:00", 81f, distanceKm = 2f),
            observation("S", "2026-06-03T21:00:00", 68f, distanceKm = 2f),
            // 2026-06-04 (true low 56 @05:00, true high 85 @15:04)
            observation("S", "2026-06-04T00:30:00", 63f, distanceKm = 2f),
            observation("S", "2026-06-04T05:00:00", 56f, distanceKm = 2f),
            observation("S", "2026-06-04T15:00:00", 83f, distanceKm = 2f),
            observation("S", "2026-06-04T15:04:00", 85f, distanceKm = 2f),
            observation("S", "2026-06-04T22:00:00", 66f, distanceKm = 2f),
        )
        val now = LocalDateTime.parse("2026-06-05T12:00:00")

        // Daily-bar path.
        val daily = ActualsAggregator.aggregate(
            observations = obs,
            hourlyForecasts = forecasts,
            locationLat = LAT,
            locationLon = LON,
            zoneId = zone,
        ).filter { it.source == WeatherSource.NWS.id }.associateBy {
            java.time.Instant.ofEpochMilli(it.date).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        }

        // Hourly path: build the actual series over a window spanning both days, assemble the dense
        // HourData list, and resolve per-day actual extrema exactly as the renderer does.
        val series = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = obs,
            centerTime = LocalDateTime.parse("2026-06-04T00:00:00"),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            backHours = 30,
            forwardHours = 30,
            contextLookbackHours = 72,
            contextLookaheadHours = 60,
            now = now,
            zoneId = zone,
        )
        val hours = com.weatherwidget.shared.graph.HourDataAssembler.assembleHourData(series, zone)
        val extrema = com.weatherwidget.shared.graph.TemperatureLabelResolver
            .computeExtremaIndices(hours, transitionX = null, effectiveActualEndIndex = hours.lastIndex, fetchTime = null, useCelsius = false)

        fun hourlyValueOn(date: java.time.LocalDate, indices: List<Int>): Float {
            val onDay = indices.filter { hours[it].dateTime.toLocalDate() == date }
            assertEquals("expected exactly one per-day extreme on $date", 1, onDay.size)
            return extrema.actualLabelTemps[onDay.single()]
        }

        // Anchor on the TRUE per-day max/min (the off-5-min sub-hourly readings). The old 5-min dedup
        // dropped these in BOTH paths consistently — daily bar and hourly graph agreed at the WRONG
        // (thinned) value — so asserting only "hourly == daily" wouldn't catch the regression. Asserting
        // both equal the true raw extreme proves the thinning no longer loses the real peak/trough.
        val expected = mapOf(
            java.time.LocalDate.parse("2026-06-03") to (81f to 53f),
            java.time.LocalDate.parse("2026-06-04") to (85f to 56f),
        )
        for ((date, hiLo) in expected) {
            val (trueHigh, trueLow) = hiLo
            val bar = daily.getValue(date)
            assertEquals("daily-bar high on $date should be the true peak", trueHigh, bar.computedHighTemp!!, 0.1f)
            assertEquals("daily-bar low on $date should be the true trough", trueLow, bar.computedLowTemp!!, 0.1f)
            assertEquals(
                "hourly high must equal daily-bar high on $date",
                bar.computedHighTemp, hourlyValueOn(date, extrema.actualDailyHighIndices), 0.001f,
            )
            assertEquals(
                "hourly low must equal daily-bar low on $date",
                bar.computedLowTemp, hourlyValueOn(date, extrema.actualDailyLowIndices), 0.001f,
            )
        }
    }

    @Test
    fun `blended day high is independent of query window when a station distance drifts`() {
        // Regression for the daily-bar (77.4) vs hourly-graph (76.8) split: a station's logged
        // distanceKm drifts as the configured location moves. The IDW weight at today's 15:00 peak must
        // use today's distance regardless of whether the blend input window also includes a stale,
        // farther-logged reading from days ago. The daily bar feeds an isolated today-only window; the
        // hourly graph feeds a 72h window — they must agree. Before the fix, resolveStationValueAt used
        // stationObs.first().distanceKm, so the wide window's earliest (stale, 2.9km) reading down-weighted
        // the near station for the whole day and dragged the peak down. (daily_vs_hourly_actual_extrema_mismatch)
        val todayPeak = "2026-06-03T15:00:00"
        val nearToday = listOf(
            observation("S_NEAR", "2026-06-03T06:00:00", 58f, distanceKm = 2.0f),
            observation("S_NEAR", todayPeak, 79f, distanceKm = 2.0f),
            observation("S_NEAR", "2026-06-03T20:00:00", 64f, distanceKm = 2.0f),
        )
        val midToday = listOf(
            observation("S_MID", "2026-06-03T06:00:00", 56f, distanceKm = 4.0f),
            observation("S_MID", "2026-06-03T15:00:00", 75f, distanceKm = 4.0f),
            observation("S_MID", "2026-06-03T20:00:00", 62f, distanceKm = 4.0f),
        )
        // Same near station, three days earlier, logged from a farther location (2.9km). Only its
        // presence as the station's earliest reading differs between the two windows.
        val staleNear = listOf(
            observation("S_NEAR", "2026-05-31T15:00:00", 70f, distanceKm = 2.9f),
        )
        val forecasts = forecasts("2026-05-31T00:00:00", 96)
        val dayStart = epoch("2026-06-03T00:00:00")
        val dayEnd = epoch("2026-06-04T00:00:00")

        fun dayHigh(obs: List<ObservationReading>, startMs: Long, endMs: Long): Float =
            ActualTemperatureSeriesBuilder.blendObservationSeries(
                observations = obs,
                hourlyForecasts = forecasts,
                displaySourceId = WeatherSource.NWS.id,
                userLat = LAT,
                userLon = LON,
                startMs = startMs,
                endMs = endMs,
            ).observations.filter { it.timestamp in dayStart until dayEnd }.maxOf { it.temperature }

        // Daily-bar path: isolated today-only window.
        val isolated = dayHigh(nearToday + midToday, dayStart, dayEnd)
        // Hourly-graph path: wide window that also contains the stale earlier reading.
        val wide = dayHigh(
            staleNear + nearToday + midToday,
            epoch("2026-05-31T00:00:00"),
            epoch("2026-06-04T00:00:00"),
        )

        assertEquals("today's blended high must not depend on the query window", isolated, wide, 0.001f)
    }

    @Test
    fun `personal and official stations get equal weight with no discount (default)`() {
        // Default personalStationWeight = 1.0 (0% discount): PWS gets the same IDW weight as an
        // official station at the same distance. PWS 79° @2km vs official 75° @4km:
        // 0.25*79 + 0.0625*75 over 0.3125 = 78.2.
        val blended = blendTwoStation(personalStationWeight = 1.0)
        assertEquals(78.2f, blended, 0.05f)
    }

    @Test
    fun `personal station at half weight shifts the blend toward the official reading`() {
        // personalStationWeight = 0.5 (50% discount): PWS weight 0.5*0.25=0.125, official 0.0625.
        // (0.125*79 + 0.0625*75) / 0.1875 = 77.67 — pulled down from 78.2 toward the official 75°.
        val blended = blendTwoStation(personalStationWeight = 0.5)
        assertEquals(77.67f, blended, 0.05f)
    }

    @Test
    fun `personal station fully discounted is excluded from the blend`() {
        // personalStationWeight = 0.0 (100% discount): PWS dropped entirely, only the official remains.
        val blended = blendTwoStation(personalStationWeight = 0.0)
        assertEquals(75f, blended, 0.01f)
    }

    @Test
    fun `qc-failed reading is excluded from the blended series`() {
        // KPAO 2026-07-13: Synoptic served a QC-flagged 50° reading closer to the user than the
        // clean station. It must not drag the blend — the series must come out as if only the
        // clean reading existed (this is the path that anchors the displayed current temp).
        val peak = "2026-06-03T15:00:00"
        val obs = listOf(
            observation("KPAO", peak, 50f, distanceKm = 2f).copy(qcFailed = true),
            observation("OFFICIAL_1", peak, 75f, distanceKm = 4f),
        )

        val blended = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts("2026-06-03T00:00:00", 24),
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = epoch("2026-06-03T00:00:00"),
            endMs = epoch("2026-06-04T00:00:00"),
        ).observations.single().temperature

        assertEquals(75f, blended, 0.01f)
    }

    private fun blendTwoStation(personalStationWeight: Double): Float {
        val peak = "2026-06-03T15:00:00"
        val obs = listOf(
            observation("PWS", peak, 79f, distanceKm = 2f, stationType = "PERSONAL"),
            observation("OFFICIAL_1", peak, 75f, distanceKm = 4f, stationType = "OFFICIAL"),
        )
        val forecasts = forecasts("2026-06-03T00:00:00", 24)

        return ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = obs,
            hourlyForecasts = forecasts,
            displaySourceId = WeatherSource.NWS.id,
            userLat = LAT,
            userLon = LON,
            startMs = epoch("2026-06-03T00:00:00"),
            endMs = epoch("2026-06-04T00:00:00"),
            personalStationWeight = personalStationWeight,
        ).observations.single().temperature
    }

    @Test
    fun `build gracefully interpolates forecastTemp when past hours have missing forecasts`() {
        val forecasts = listOf(
            HourlyForecast(
                dateTime = epoch("2026-06-03T14:00:00"),
                temperature = 80f,
                condition = "Clouds",
                source = WeatherSource.OPEN_WEATHER_MAP.id,
            ),
            HourlyForecast(
                dateTime = epoch("2026-06-03T15:00:00"),
                temperature = 82f,
                condition = "Clouds",
                source = WeatherSource.OPEN_WEATHER_MAP.id,
            ),
        )
        val observations = listOf(
            observation(
                stationId = "OPEN_WEATHER_MAP_MAIN",
                time = "2026-06-03T13:05:00",
                temperature = 79f,
                api = WeatherSource.OPEN_WEATHER_MAP.id,
                distanceKm = 0f,
            ),
        )

        val result = ActualTemperatureSeriesBuilder.build(
            hourlyForecasts = forecasts,
            observations = observations,
            centerTime = LocalDateTime.parse("2026-06-03T13:00:00"),
            displaySourceId = WeatherSource.OPEN_WEATHER_MAP.id,
            userLat = LAT,
            userLon = LON,
            backHours = 4,
            forwardHours = 4,
            contextLookbackHours = 12,
            contextLookaheadHours = 12,
            now = LocalDateTime.parse("2026-06-03T13:10:00"),
            zoneId = zone,
        )

        val obsPoint = result.points.firstOrNull { it.timeMs == epoch("2026-06-03T13:05:00") }
        assertFalse("Observation point forecastTemp should not be NaN", obsPoint?.forecastTemp?.isNaN() == true)
        assertEquals(80f, obsPoint?.forecastTemp ?: Float.NaN, 0.01f)
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
