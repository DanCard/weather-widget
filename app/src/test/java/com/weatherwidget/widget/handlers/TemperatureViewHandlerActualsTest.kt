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

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    // Center at a fixed noon so hour alignment is deterministic
    private val center = LocalDateTime.of(2026, 2, 20, 12, 0)

    /**
     * Build forecast entities covering the WIDE zoom window around [center].
     * WIDE: back=8h, forward=16h → 2026-02-20 04:00 through 2026-02-20 28:00
     */
    private fun wideForecasts(): List<com.weatherwidget.data.local.HourlyForecastEntity> {
        val start = center.minusHours(10) // extra buffer
        val end = center.plusHours(50)
        val result = mutableListOf<com.weatherwidget.data.local.HourlyForecastEntity>()
        var cur = start
        while (!cur.isAfter(end)) {
            result.add(TestData.hourly(dateTime = cur.format(fmt), temperature = 60f + cur.hour))
            cur = cur.plusHours(1)
        }
        return result
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
    fun `WIDE zoom covers more hours than NARROW zoom`() {
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
        assertTrue("WIDE should cover ≥25 hours when data available", wideHours.size >= 25)
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
    fun `mixed NWS stations pick one consistent series by coverage`() {
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

        val hour10 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T10:10") })
        val hour11 = requireNotNull(hours.find { it.dateTime == LocalDateTime.parse("2026-02-20T11:10") })

        assertEquals(62f, hour10.actualTemperature)
        assertEquals(63f, hour11.actualTemperature)
        assertNotEquals("Rejected station should not supply the actual", 80f, hour10.actualTemperature)
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
}
