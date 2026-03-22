package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Unit tests for buildHourDataList actuals integration.
 * buildHourDataList is marked @VisibleForTesting internal — accessible from same module tests.
 */
class TemperatureViewHandlerActualsTest {
    companion object {
        private const val IDLE_BLEND_MAX_MS = 250L
    }

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    // Center at a fixed noon so hour alignment is deterministic
    private val center = LocalDateTime.of(2026, 2, 20, 12, 0)

    /**
     * Build forecast entities covering the WIDE zoom window around [center].
     * WIDE: back=8h, forward=16h → 2026-02-20 04:00 through 2026-02-20 28:00
     */
    private fun wideForecasts(): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val start = center.minusHours(24) // extra buffer
        val end = center.plusHours(72) // extra buffer
        val result = mutableListOf<com.weatherwidget.data.local.HourlyForecastEntity>()
        var cur = start
        while (!cur.isAfter(end)) {
            result.add(TestData.hourly(dateTime = cur.format(fmt), temperature = 60f + cur.hour))
            cur = cur.plusHours(1)
        }
        return result
    }

    @Test
    fun `blend debug collector throttles detailed lines within window`() {
        var nowMs = 1_000L
        val collector = TemperatureViewHandler.BlendDebugCollector(
            throttleMs = 50L,
            clockMs = { nowMs },
        )

        collector.recordDetailed("first")
        nowMs += 10L
        collector.recordDetailed("second")
        nowMs += 39L
        collector.recordDetailed("third")
        nowMs += 1L
        collector.recordDetailed("fourth")

        assertEquals(4, collector.rawDetailedLines)
        assertEquals(2, collector.emittedDetailedLines)
        assertEquals(2, collector.suppressedDetailedLines)
        assertEquals(listOf("first", "fourth"), collector.emittedLines())
    }

    @Test
    fun `blend debug collector always emit bypasses throttle`() {
        var nowMs = 2_000L
        val collector = TemperatureViewHandler.BlendDebugCollector(
            throttleMs = 50L,
            clockMs = { nowMs },
        )

        collector.recordDetailed("first")
        nowMs += 10L
        collector.recordDetailed("forced", alwaysEmit = true)
        nowMs += 10L
        collector.recordDetailed("suppressed")

        assertEquals(3, collector.rawDetailedLines)
        assertEquals(2, collector.emittedDetailedLines)
        assertEquals(1, collector.suppressedDetailedLines)
        assertEquals(listOf("first", "forced"), collector.emittedLines())
    }

    @Test
    fun `blend observation stats capture interpolation growth`() {
        val forecasts = wideForecasts()
        val start = TestData.toEpoch("2026-02-20T10:00")
        val end = TestData.toEpoch("2026-02-20T11:00")
        val result = TemperatureViewHandler.blendObservationSeries(
            observations = listOf(
                TestData.observation(timestamp = start, temperature = 68f),
                TestData.observation(timestamp = end, temperature = 72f),
            ),
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = start,
            endMs = end,
        )

        assertEquals(5, result.observations.size)
        assertEquals(2, result.stats.rawObservationCount)
        assertEquals(2, result.stats.filteredObservationCount)
        assertEquals(1, result.stats.stationCount)
        assertEquals(5, result.stats.candidateTimeCount)
        assertEquals(5, result.stats.emittedPointCount)
        assertEquals(0, result.stats.dedupSkippedCount)
        assertEquals(0, result.stats.emptyPeerCount)
        assertEquals(1, result.stats.stationSeriesStats.size)
        val station = result.stats.stationSeriesStats.single()
        assertEquals(2, station.rawObservationCount)
        assertEquals(2, station.observedPointCount)
        assertEquals(3, station.interpolatedPointCount)
        assertEquals(0, station.extrapolatedPointCount)
        assertEquals(5, station.outputPointCount)
    }

    @Test
    fun `idle-period nws blending stays bounded and completes quickly`() {
        val forecasts = wideForecasts()
        val actuals = idlePeriodNwsActuals()
        val startMs = center.minusHours(ZoomLevel.WIDE.backHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = center.plusHours(ZoomLevel.WIDE.forwardHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val startNs = System.nanoTime()
        val blendResult = TemperatureViewHandler.blendObservationSeries(
            observations = actuals,
            hourlyForecasts = forecasts,
            displaySource = WeatherSource.NWS,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = startMs,
            endMs = endMs,
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        val stats = blendResult.stats
        val maxSyntheticPerStation = stats.stationSeriesStats.maxOfOrNull {
            it.interpolatedPointCount + it.extrapolatedPointCount
        } ?: 0
        println("Idle-period NWS blend: elapsedMs=$elapsedMs ${stats.summary()}")

        assertEquals(15, stats.rawObservationCount)
        assertEquals(15, stats.filteredObservationCount)
        assertEquals(5, stats.stationCount)
        assertTrue("candidate times should stay bounded: ${stats.summary()}", stats.candidateTimeCount <= 140)
        assertTrue("emitted points should stay bounded: ${stats.summary()}", stats.emittedPointCount <= 140)
        assertTrue("per-station synthetic growth should stay bounded: ${stats.summary()}", maxSyntheticPerStation <= 40)
        assertTrue("scenario should exercise interpolation: ${stats.summary()}", stats.stationSeriesStats.any { it.interpolatedPointCount > 0 })
        assertTrue("scenario should exercise extrapolation: ${stats.summary()}", stats.stationSeriesStats.any { it.extrapolatedPointCount > 0 })
        assertTrue(
            "idle-period blend took ${elapsedMs}ms; ${stats.summary()}",
            elapsedMs <= IDLE_BLEND_MAX_MS,
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )
        assertTrue("wide window should surface actuals for the graph path", hours.any { it.isActual && it.actualTemperature != null })
    }

    @Test
    fun `actual matched by dateTime sets isActual and actualTemperature`() {
        val forecasts = wideForecasts()
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T10:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 68f))

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val hour10 = hours.find { it.dateTime.hour == 10 && it.dateTime.dayOfMonth == 20 }
        requireNotNull(hour10) { "Expected hour 10 in result" }
        assertTrue("isActual should be true for matched hour", hour10.isActual)
        assertEquals(68f, hour10.actualTemperature)
    }

    @Test
    fun `non-matching hours have isActual false and null actualTemperature`() {
        val forecasts = wideForecasts()
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T10:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 68f))

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val hoursBefore10 = hours.filter { it.dateTime.hour < 10 && it.dateTime.dayOfMonth == 20 }
        assertTrue("At least some non-actual hours should exist", hoursBefore10.isNotEmpty())
        for (h in hoursBefore10) {
            assertFalse("Hour ${h.dateTime} should have isActual=false", h.isActual)
            assertNull("Hour ${h.dateTime} should have null actualTemperature", h.actualTemperature)
        }
    }

    @Test
    fun `no actuals produces all-forecast list with isActual false`() {
        val forecasts = wideForecasts()

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = emptyList(),
        )

        assertTrue("Should have hours", hours.isNotEmpty())
        assertTrue("All hours should have isActual=false", hours.all { !it.isActual })
        assertTrue("All hours should have null actualTemperature", hours.all { it.actualTemperature == null })
    }

    @Test
    fun `forecast temperature field is always the forecast value, not the actual`() {
        val forecasts = wideForecasts()
        // The forecast at 10:00 has temperature = 60 + 10 = 70f
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T10:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 99f))

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val hour10 = hours.find { it.dateTime.hour == 10 && it.dateTime.dayOfMonth == 20 }
        requireNotNull(hour10)
        // temperature field = forecast (60 + 10 = 70), NOT the actual (99)
        assertEquals("temperature should be forecast value", 70f, hour10.temperature)
        assertEquals("actualTemperature should be actual value", 99f, hour10.actualTemperature)
    }

    @Test
    fun `WIDE zoom covers 25 hours`() {
        val forecasts = wideForecasts()
        val wideHours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
        )
        val narrowHours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
        )

        assertTrue(
            "WIDE (${wideHours.size}) should have more hours than NARROW (${narrowHours.size})",
            wideHours.size > narrowHours.size,
        )
        assertEquals("WIDE should cover exactly 25 hours (12h back + 12h forward + center)", 25, wideHours.size)
        assertTrue("NARROW should cover ≤5 hours", narrowHours.size <= 5)
    }

    @Test
    fun `actuals outside the zoom window do not appear as isActual`() {
        val forecasts = wideForecasts()
        // NARROW window around noon: back=2 → 10:00, forward=2 → 14:00
        // Actual at 06:00 is outside NARROW window
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T06:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 55f))

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.NARROW,
            actuals = actuals,
        )

        // 06:00 is not in NARROW window — no HourData for it at all
        val hour06 = hours.find { it.dateTime.hour == 6 }
        assertNull("Hour 06 should not appear in NARROW window", hour06)
        assertTrue("No hours should be isActual", hours.none { it.isActual })
    }

    @Test
    fun `carry-forward keeps synthetic past bucket actual without moving last observed anchor`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:00", 61f, stationId = "KPAO", distanceKm = 2f),
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val observedPoint = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:00") })
        val carriedHour = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T12:00") })
        val lastObserved = requireNotNull(hours.lastOrNull { it.isObservedActual })

        assertTrue("Observed sub-hour point should remain marked as real actual", observedPoint.isObservedActual)
        assertTrue("Carried top-of-hour bucket should still render as actual for continuity", carriedHour.isActual)
        assertFalse("Carried top-of-hour bucket should not count as a real observed anchor", carriedHour.isObservedActual)
        assertEquals(LocalDateTime.parse("2026-02-20T10:00"), lastObserved.dateTime)
    }

    @Test
    fun `mixed NWS stations IDW-blend nearby observations`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T09:10", 61f, stationId = "KPAO", distanceKm = 2f),
            observationAt("2026-02-20T10:10", 62f, stationId = "KPAO", distanceKm = 2f),
            observationAt("2026-02-20T11:10", 63f, stationId = "KPAO", distanceKm = 2f),
            observationAt("2026-02-20T10:05", 80f, stationId = "KSFO", distanceKm = 10f),
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val blendedPoint = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:05") }) {
            "Expected blended point at T10:05"
        }
        assertNotEquals("Blend should not be dominated by far station", 80f, blendedPoint.actualTemperature)
        val blendedTemp = blendedPoint.actualTemperature!!
        assertTrue("Blended temp should be closer to near station (62f) than far station (80f)", blendedTemp < 70f)

        val hour1010 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:10") })
        val blended1010 = requireNotNull(hour1010.actualTemperature)
        assertTrue("10:10 blend should stay near the close station instead of the far 80F station", blended1010 < 70f)
        assertTrue("10:10 blend should remain warmer than the close station's 62F point due to the far-station contribution", blended1010 > 62f)

        val hour11 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:10") })
        val blended11 = requireNotNull(hour11.actualTemperature)
        assertTrue("11:10 should stay near the close station instead of the far 80F station", blended11 < 70f)
        assertTrue("11:10 can still be slightly warmer than the close station due to 3-hour extrapolation of the far station", blended11 > 63f)
    }

    @Test
    fun `blend diagnostics log both single-station and cohort-change emissions`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 60f, stationId = "KNUQ", distanceKm = 3.7f),
            observationAt("2026-02-20T10:25", 56f, stationId = "AW020", distanceKm = 2.9f),
        )
        val debugLines = mutableListOf<String>()

        TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
            onBlendDebug = { debugLines += it },
        )

        assertTrue(debugLines.any { it.contains("window source=NWS") && it.contains("AW020") && it.contains("KNUQ") })
        assertTrue(debugLines.any { it.contains("station_interpolate") || it.contains("single_station=AW020") })
        assertTrue(debugLines.any { it.contains("cohortChanged=true") && it.contains("KNUQ") })
    }

    @Test
    fun `station-local interpolation keeps intermittent station in later blend windows`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:25", 56f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 60f, stationId = "KNUQ", distanceKm = 3.7f),
            observationAt("2026-02-20T10:35", 62f, stationId = "KNUQ", distanceKm = 3.7f),
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val point1025 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:25") })
        val blended = requireNotNull(point1025.actualTemperature)
        assertTrue("Interpolated KNUQ should keep the 10:25 blend above AW020-only 56F", blended > 57f)
        assertTrue("Interpolated KNUQ should keep the 10:25 blend below warmest station 62F", blended < 62f)
    }

    @Test
    fun `station-local interpolation fills multi-step gaps up to 3 hours`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:35", 56f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 60f, stationId = "KNUQ", distanceKm = 3.7f),
            observationAt("2026-02-20T12:15", 66f, stationId = "KNUQ", distanceKm = 3.7f),
        )
        val debugLines = mutableListOf<String>()

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
            onBlendDebug = { debugLines += it },
        )

        val point1030 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:30") })
        val point1130 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:30") })
        val blended1030 = requireNotNull(point1030.actualTemperature)
        val blended1130 = requireNotNull(point1130.actualTemperature)

        assertTrue(debugLines.any { it.contains("station_interpolate station=KNUQ at=10:30") })
        assertTrue(debugLines.any { it.contains("station_interpolate station=KNUQ at=11:30") })
        assertTrue("10:30 should stay above AW020-only 57F because KNUQ is bridged", blended1030 > 57.5f)
        assertTrue("11:30 should stay above AW020-only 55F because KNUQ is bridged across the 2-hour gap", blended1130 > 57.5f)
    }

    @Test
    fun `forecast-guided extrapolation keeps last station briefly after dropout`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:35", 56f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 60f, stationId = "LOAC1", distanceKm = 3.2f),
        )
        val debugLines = mutableListOf<String>()

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
            onBlendDebug = { debugLines += it },
        )

        val point1030 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:30") })
        val extrapolatedBlend = requireNotNull(point1030.actualTemperature)

        assertTrue(debugLines.any { it.contains("station_extrapolate station=LOAC1 at=10:30") })
        assertTrue(
            "10:30 should stay above AW020-only 56F because LOAC1 is briefly held forward after dropout",
            extrapolatedBlend > 56f,
        )
        assertTrue(
            "10:30 should stay below the original LOAC1 60F because forecast-guided extrapolation follows the cooling trend",
            extrapolatedBlend < 60f,
        )
    }

    @Test
    fun `forecast-guided extrapolation does not move last observed actual anchor`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:15", 60f, stationId = "LOAC1", distanceKm = 3.2f),
        )
        val debugLines = mutableListOf<String>()

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
            onBlendDebug = { debugLines += it },
        )

        val observed1015 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:15") })
        val extrapolated1030 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:30") })
        val lastObserved = requireNotNull(hours.lastOrNull { it.isObservedActual })

        assertTrue(debugLines.any { it.contains("station_extrapolate station=LOAC1 at=10:30") })
        assertTrue("Raw observation should remain observed actual", observed1015.isObservedActual)
        assertTrue("Extrapolated point should still render as actual for continuity", extrapolated1030.isActual)
        assertFalse("Extrapolated point must not become the observed anchor", extrapolated1030.isObservedActual)
        assertEquals(LocalDateTime.parse("2026-02-20T10:15"), lastObserved.dateTime)
    }

    @Test
    fun `hourly backfill requested when NWS history has singleton station coverage`() {
        val observations = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 58f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:10", 60f, stationId = "LOAC1", distanceKm = 9f),
        )

        val decision = TemperatureViewHandler.evaluateHourlyBackfillNeed(
            displaySource = WeatherSource.NWS,
            graphStart = LocalDateTime.parse("2026-02-20T10:00"),
            graphEnd = LocalDateTime.parse("2026-02-20T14:00"),
            observations = observations,
            now = LocalDateTime.parse("2026-02-20T11:00"),
        )

        assertTrue(decision.shouldRequest)
        assertTrue(decision.reason.contains("singleton_stations=LOAC1"))
    }

    @Test
    fun `hourly backfill skipped when NWS history is dense and recent`() {
        val observations = listOf(
            observationAt("2026-02-20T10:05", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:25", 56f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:45", 55f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:15", 60f, stationId = "KNUQ", distanceKm = 3.7f),
            observationAt("2026-02-20T10:35", 59f, stationId = "KNUQ", distanceKm = 3.7f),
            observationAt("2026-02-20T10:55", 58f, stationId = "KNUQ", distanceKm = 3.7f),
        )

        val decision = TemperatureViewHandler.evaluateHourlyBackfillNeed(
            displaySource = WeatherSource.NWS,
            graphStart = LocalDateTime.parse("2026-02-20T10:00"),
            graphEnd = LocalDateTime.parse("2026-02-20T14:00"),
            observations = observations,
            now = LocalDateTime.parse("2026-02-20T11:00"),
        )

        assertFalse(decision.shouldRequest)
        assertTrue(decision.reason.contains("coverage_ok"))
    }

    @Test
    fun `hourly backfill skipped for non NWS sources`() {
        val observations = listOf(
            observationAt("2026-02-20T10:05", 70f, stationId = "OPEN_METEO_MAIN", distanceKm = 1f),
        )

        val decision = TemperatureViewHandler.evaluateHourlyBackfillNeed(
            displaySource = WeatherSource.OPEN_METEO,
            graphStart = LocalDateTime.parse("2026-02-20T10:00"),
            graphEnd = LocalDateTime.parse("2026-02-20T14:00"),
            observations = observations,
            now = LocalDateTime.parse("2026-02-20T11:00"),
        )

        assertFalse(decision.shouldRequest)
        assertEquals("non_nws_source", decision.reason)
    }

    @Test
    fun `single series selection applies to non NWS sources too`() {
        val forecasts = wideForecasts().map {
            it.copy(source = WeatherSource.OPEN_METEO.id)
        }
        val actuals = listOf(
            observationAt("2026-02-20T10:05", 70f, stationId = "OPEN_METEO_MAIN", distanceKm = 1f),
            observationAt("2026-02-20T11:05", 71f, stationId = "OPEN_METEO_MAIN", distanceKm = 1f),
            observationAt("2026-02-20T10:25", 55f, stationId = "OPEN_METEO_1", distanceKm = 8f),
        )

        val hours = TemperatureViewHandler.buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.OPEN_METEO,
            zoom = ZoomLevel.WIDE,
            actuals = actuals,
        )

        val hour10 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:05") })
        val hour11 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:05") })

        assertEquals(70f, hour10.actualTemperature)
        assertEquals(71f, hour11.actualTemperature)
    }

    @Test
    fun `when coverage ties nearest station wins`() {
        val selected = TemperatureViewHandler.selectObservationSeries(
            observations = listOf(
                observationAt("2026-02-20T10:05", 61f, stationId = "KNEAR", distanceKm = 1f),
                observationAt("2026-02-20T11:05", 62f, stationId = "KNEAR", distanceKm = 1f),
                observationAt("2026-02-20T10:10", 71f, stationId = "KFAR", distanceKm = 9f),
                observationAt("2026-02-20T11:10", 72f, stationId = "KFAR", distanceKm = 9f),
            ),
            displaySource = WeatherSource.NWS,
            startHour = LocalDateTime.parse("2026-02-20T10:00"),
            endHour = LocalDateTime.parse("2026-02-20T12:00"),
        )

        assertEquals("KNEAR", selected.stationId)
        assertEquals(2, selected.observations.size)
    }

    private fun observationAt(
        dateTime: String,
        temperature: Float,
        stationId: String,
        distanceKm: Float,
    ) = TestData.observation(
        stationId = stationId,
        timestamp = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        temperature = temperature,
        distanceKm = distanceKm,
    )

    private fun idlePeriodNwsActuals() = listOf(
        observationAt("2026-02-20T00:10", 44f, stationId = "KPAO", distanceKm = 2f),
        observationAt("2026-02-20T03:10", 47f, stationId = "KPAO", distanceKm = 2f),
        observationAt("2026-02-20T06:10", 52f, stationId = "KPAO", distanceKm = 2f),
        observationAt("2026-02-20T00:35", 43f, stationId = "KSQL", distanceKm = 4.5f),
        observationAt("2026-02-20T03:35", 46f, stationId = "KSQL", distanceKm = 4.5f),
        observationAt("2026-02-20T06:35", 50f, stationId = "KSQL", distanceKm = 4.5f),
        observationAt("2026-02-20T01:00", 41f, stationId = "KHAF", distanceKm = 15f),
        observationAt("2026-02-20T04:00", 44f, stationId = "KHAF", distanceKm = 15f),
        observationAt("2026-02-20T07:00", 48f, stationId = "KHAF", distanceKm = 15f),
        observationAt("2026-02-20T01:25", 42f, stationId = "AWD01", distanceKm = 6f),
        observationAt("2026-02-20T04:25", 45f, stationId = "AWD01", distanceKm = 6f),
        observationAt("2026-02-20T07:25", 49f, stationId = "AWD01", distanceKm = 6f),
        observationAt("2026-02-20T01:50", 40f, stationId = "KMUX", distanceKm = 9f),
        observationAt("2026-02-20T04:50", 43f, stationId = "KMUX", distanceKm = 9f),
        observationAt("2026-02-20T07:50", 47f, stationId = "KMUX", distanceKm = 9f),
    )
}
