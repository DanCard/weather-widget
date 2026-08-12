package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.widget.ZoomStage
import com.weatherwidget.widget.ZoomWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Unit tests for buildHourDataList actuals integration.
 * buildHourDataList is marked @VisibleForTesting internal — accessible from same module tests.
 */
@Category(ShortDuration::class)
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
        val collector = BlendDebugCollector(
            throttleMs = 50L,
            clockMs = { nowMs },
        )

        collector.recordDetailed({ "first" })
        nowMs += 10L
        collector.recordDetailed({ "second" })
        nowMs += 39L
        collector.recordDetailed({ "third" })
        nowMs += 1L
        collector.recordDetailed({ "fourth" })

        assertEquals(4, collector.rawDetailedLines)
        assertEquals(2, collector.emittedDetailedLines)
        assertEquals(2, collector.suppressedDetailedLines)
        assertEquals(listOf("first", "fourth"), collector.emittedLines())
    }

    @Test
    fun `blend debug collector always emit bypasses throttle`() {
        var nowMs = 2_000L
        val collector = BlendDebugCollector(
            throttleMs = 50L,
            clockMs = { nowMs },
        )

        collector.recordDetailed({ "first" })
        nowMs += 10L
        collector.recordDetailed({ "forced" }, alwaysEmit = true)
        nowMs += 10L
        collector.recordDetailed({ "suppressed" })

        assertEquals(3, collector.rawDetailedLines)
        assertEquals(2, collector.emittedDetailedLines)
        assertEquals(1, collector.suppressedDetailedLines)
        assertEquals(listOf("first", "forced"), collector.emittedLines())
    }

    @Test
    fun `blend observation stats with two observations at real timestamps`() {
        val forecasts = wideForecasts()
        val start = TestData.toEpoch("2026-02-20T10:00")
        val end = TestData.toEpoch("2026-02-20T11:00")
        val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = listOf(
                TestData.observation(timestamp = start, temperature = 68f),
                TestData.observation(timestamp = end, temperature = 72f),
            ).map { it.toReading() },
            hourlyForecasts = forecasts.map { it.toHourlyForecast() },
            displaySourceId = WeatherSource.NWS.id,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = start,
            endMs = end,
            onBlendDebug = null,
        )

        // Only 2 candidate times (real observation timestamps), no synthetic grid
        assertEquals(2, result.stats.rawObservationCount)
        assertEquals(2, result.stats.filteredObservationCount)
        assertEquals(1, result.stats.stationCount)
        assertEquals(2, result.stats.candidateTimeCount)
        assertEquals(2, result.stats.emittedPointCount)
        assertEquals(0, result.stats.dedupSkippedCount)
        assertEquals(2, result.observations.size)
    }

    @Test
    fun `idle-period nws blending stays bounded and completes quickly`() {
        val forecasts = wideForecasts()
        val actuals = idlePeriodNwsActuals()
        val startMs = center.minusHours(ZoomStage.WIDE.window().backHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = center.plusHours(ZoomStage.WIDE.window().forwardHours).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val startNs = System.nanoTime()
        val blendResult = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = actuals.map { it.toReading() },
            hourlyForecasts = forecasts.map { it.toHourlyForecast() },
            displaySourceId = WeatherSource.NWS.id,
            userLat = TestData.LAT,
            userLon = TestData.LON,
            startMs = startMs,
            endMs = endMs,
            onBlendDebug = null,
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        val stats = blendResult.stats
        println("Idle-period NWS blend: elapsedMs=$elapsedMs ${stats.summary()}")

        assertEquals(15, stats.rawObservationCount)
        assertEquals(15, stats.filteredObservationCount)
        assertEquals(5, stats.stationCount)
        // Candidate times = real observation timestamps only (≤15 unique times across 5 stations)
        assertTrue("candidate times should be bounded to real observations: ${stats.summary()}", stats.candidateTimeCount <= 15)
        assertTrue("emitted points should be bounded: ${stats.summary()}", stats.emittedPointCount <= 15)
        assertTrue(
            "idle-period blend took ${elapsedMs}ms; ${stats.summary()}",
            elapsedMs <= IDLE_BLEND_MAX_MS,
        )

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
        )
        assertTrue("wide window should surface actuals for the graph path", hours.any { it.isActual && it.actualTemperature != null })
    }

    @Test
    fun `actual matched by dateTime sets isActual and actualTemperature`() {
        val forecasts = wideForecasts()
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T10:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 68f))

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
        )

        val hour10 = hours.find { it.dateTime.hour == 10 && it.dateTime.dayOfMonth == 20 }
        requireNotNull(hour10)
        // temperature field = forecast (60 + 10 = 70), NOT the actual (99)
        assertEquals("temperature should be forecast value", 70f, hour10.temperature)
        assertEquals("actualTemperature should be actual value", 99f, hour10.actualTemperature)
    }

    @Test
    fun `WIDE zoom covers 24 hours`() {
        val forecasts = wideForecasts()
        val wideHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
        )
        val narrowHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
        )

        assertTrue(
            "WIDE (${wideHours.size}) should have more hours than NARROW (${narrowHours.size})",
            wideHours.size > narrowHours.size,
        )
        // Marks, not hours: a window covering n hours runs start..start+n and so carries n+1 marks
        // (both edges belong to the view). This test previously asserted n and therefore passed on a
        // graph covering an hour less than its own name claims — see SharedNarrowSpanDisplayedHoursTest.
        assertEquals("WIDE should cover exactly 24 hours (12h back + 12h forward)", 25, wideHours.size)
        assertEquals("NARROW should cover the default 5h span (3h back + 2h forward)", 6, narrowHours.size)
    }

    @Test
    fun `buildHourDataList is consistent across zoom levels`() {
        val forecasts = wideForecasts()
        // Observation at T-3h (09:00)
        // Observation at T-1h (11:00)
        // NARROW window starts at T-2h (10:00)
        val tMinus3h = center.minusHours(3)
        val tMinus1h = center.minusHours(1)
        
        val actuals = listOf(
            observationAt(tMinus3h.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), 60f, stationId = "S1", distanceKm = 2f),
            observationAt(tMinus1h.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), 70f, stationId = "S2", distanceKm = 10f)
        )

        // Wide zoom: 11:00 point should be a blend of S1 (extrapolated) and S2
        val wideHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
        )
        
        // Narrow zoom: 11:00 point should be IDENTICAL to wide zoom 
        // because we removed the startMs filter in blendObservationSeries.
        val narrowHours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
            actuals = actuals,
        )

        val widePointAt11 = wideHours.find { it.dateTime == tMinus1h }
        val narrowPointAt11 = narrowHours.find { it.dateTime == tMinus1h }

        assertNotNull("Wide result should have point at 11:00", widePointAt11)
        assertNotNull("Narrow result should have point at 11:00", narrowPointAt11)
        
        assertEquals("Temperature at 11:00 must be consistent across zoom levels", 
            widePointAt11!!.actualTemperature!!, 
            narrowPointAt11!!.actualTemperature!!, 
            0.01f
        )
        
        // Sanity check: verify it's a blend (not just 70.0)
        assertTrue("Temperature should be a blend (not exactly 70.0)", 
            Math.abs(narrowPointAt11.actualTemperature!! - 70.0f) > 0.1f
        )
    }

    @Test
    fun `actuals outside the zoom window contribute to isActual via carry-forward`() {
        val forecasts = wideForecasts()
        // NARROW window around noon: back=2 → 10:00, forward=2 → 14:00
        // Actual at 06:00 is outside NARROW window
        val actuals = listOf(TestData.observation(timestamp = java.time.LocalDateTime.parse("2026-02-20T06:00").atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), temperature = 55f))

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.NARROW.window(),
            actuals = actuals,
        )

        // 06:00 is not in NARROW window — no HourData for it at all
        val hour06 = hours.find { it.dateTime.hour == 6 }
        assertNull("Hour 06 should not appear in NARROW window", hour06)
        
        // BUT, the points that ARE in the window should be isActual=true because of carry-forward from 06:00!
        assertTrue("Hours in window should be isActual via carry-forward", hours.all { it.isActual })
        assertEquals("Carry-forward temperature should match the raw observation value", 55.0f, hours.first().actualTemperature!!, 0.1f)
    }

    @Test
    fun `carry-forward keeps synthetic past bucket actual without moving last observed anchor`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:00", 61f, stationId = "KPAO", distanceKm = 2f),
        )

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
            onBlendDebug = { debugLines += it() },
        )

        assertTrue(debugLines.any { it.contains("window source=NWS") && it.contains("AW020") && it.contains("KNUQ") })
        // Single-station emit at 10:05 (only AW020 reported, KNUQ not yet)
        assertTrue(debugLines.any { it.contains("single_station") && it.contains("source=observed") })
        // Multi-station blend at 10:15 (both AW020 interpolated + KNUQ direct)
        assertTrue(debugLines.any { it.contains("blended=") && it.contains("stationCount=2") })
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
            onBlendDebug = { debugLines += it() },
        )

        val point1035 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:35") })
        val point1100 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:00") })
        val blended1035 = requireNotNull(point1035.actualTemperature)
        val blended1100 = requireNotNull(point1100.actualTemperature)

        assertTrue(debugLines.any { it.contains("emit t=10:35") && it.contains("source=observed") })
        assertTrue("10:35 should stay above AW020-only 56F because KNUQ is bridged", blended1035 > 57.5f)
        assertTrue("11:00 should carry the bridged value forward across the gap", blended1100 > 57.5f)
        assertEquals("Carry-forward should preserve the last blended actual until the next real report", blended1035, blended1100, 0.01f)
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

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
            onBlendDebug = { debugLines += it() },
        )

        val point1035 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:35") })
        val point1100 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:00") })
        val extrapolatedBlend = requireNotNull(point1035.actualTemperature)
        val carriedBlend = requireNotNull(point1100.actualTemperature)

        assertTrue(debugLines.any { it.contains("emit t=10:35") && it.contains("source=observed") })
        assertTrue(
            "10:35 should stay above AW020-only 56F because LOAC1 is briefly held forward after dropout",
            extrapolatedBlend > 56f,
        )
        assertTrue(
            "10:35 should stay below the original LOAC1 60F because forecast-guided extrapolation follows the cooling trend",
            extrapolatedBlend < 60f,
        )
        assertEquals("The extrapolated blend should carry forward to the next top-of-hour bucket", extrapolatedBlend, carriedBlend, 0.01f)
    }

    @Test
    fun `forecast-guided extrapolation does not move last observed actual anchor`() {
        val forecasts = wideForecasts()
        val actuals = listOf(
            observationAt("2026-02-20T10:15", 60f, stationId = "LOAC1", distanceKm = 3.2f),
        )
        val debugLines = mutableListOf<String>()

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
            onBlendDebug = { debugLines += it() },
        )

        val observed1015 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:15") })
        val carried1100 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:00") })
        val lastObserved = requireNotNull(hours.lastOrNull { it.isObservedActual })

        assertTrue(debugLines.any { it.contains("emit t=10:15") && it.contains("single_station") })
        assertTrue("Raw observation should remain observed actual", observed1015.isObservedActual)
        assertTrue("Carry-forward point should still render as actual for continuity", carried1100.isActual)
        assertFalse("Carry-forward point must not become the observed anchor", carried1100.isObservedActual)
        assertEquals(LocalDateTime.parse("2026-02-20T10:15"), lastObserved.dateTime)
    }

    @Test
    fun `blend prioritizes observed status over anchor status for staleness`() {
        // Setup: Two stations. 
        // AW020 is closer (2.9km) but only has an extrapolated point at 10:30.
        // KSJC is further (15.8km) but has a fresh observed point at 10:30.
        // The anchor for T10:30 will be AW020 because it matches the timestamp exactly (0 offset).
        val forecasts = wideForecasts()
        val t1030 = TestData.toEpoch("2026-02-20T10:30")
        
        val actuals = listOf(
            observationAt("2026-02-20T10:15", 57f, stationId = "AW020", distanceKm = 2.9f),
            observationAt("2026-02-20T10:30", 62f, stationId = "KSJC", distanceKm = 15.8f),
        )

        val hours = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = center,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            zoom = ZoomStage.WIDE.window(),
            actuals = actuals,
        )

        val point1030 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:30") })
        
        // Before the fix, point1030.isObservedActual would be false because AW020 (anchor) is extrapolated at 10:30.
        // After the fix, it should be true because KSJC provides a real observation at 10:30.
        assertTrue("Blended point should be marked as observed if ANY peer is observed", point1030.isObservedActual)
        
        val lastObserved = requireNotNull(hours.lastOrNull { it.isObservedActual })
        assertEquals("Fetch dot anchor should be at the freshest observed point (10:30) even if it's from a further station", 
            LocalDateTime.parse("2026-02-20T10:30"), lastObserved.dateTime)
    }

    private fun observationAt(
        dateTime: String,
        temperature: Float,
        stationId: String,
        distanceKm: Float,
        api: String = WeatherSource.NWS.id,
    ) = TestData.observation(
        stationId = stationId,
        timestamp = LocalDateTime.parse(dateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        temperature = temperature,
        distanceKm = distanceKm,
        api = api,
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
