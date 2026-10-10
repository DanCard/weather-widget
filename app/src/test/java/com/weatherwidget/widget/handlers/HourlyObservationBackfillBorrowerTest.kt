package com.weatherwidget.widget.handlers

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A borrowing source's backfill decision must be about the series it borrows. Emulator 2026-10-07:
 * Google Weather drew NWS actuals, the host suspended 03:26 → 07:12, and the 3.5 h hole in the NWS
 * series was never repaired because the decision was made for GOOGLE_WEATHER and returned
 * `provider_history_in_forecast`.
 */
@Category(ShortDuration::class)
class HourlyObservationBackfillBorrowerTest {

    private val lat = 37.417
    private val lon = -122.089
    private val noPreference: (WeatherSource) -> WeatherSource? = { null }

    @Test
    fun `Google inside NWS coverage backfills as NWS`() {
        assertEquals(WeatherSource.NWS, backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon, noPreference))
        assertEquals(WeatherSource.NWS, backfillActualsSource(WeatherSource.SILURIAN, lat, lon, noPreference))
    }

    @Test
    fun `Google outside NWS coverage backfills as METAR`() {
        assertEquals(
            WeatherSource.METAR,
            backfillActualsSource(WeatherSource.GOOGLE_WEATHER, 50.45, 30.52, noPreference),
        )
    }

    @Test
    fun `an explicit provider choice wins`() {
        assertEquals(
            WeatherSource.METAR,
            backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon) { WeatherSource.METAR },
        )
    }

    @Test
    fun `a source with its own observations is unchanged`() {
        assertEquals(WeatherSource.NWS, backfillActualsSource(WeatherSource.NWS, lat, lon, noPreference))
        assertEquals(WeatherSource.OPEN_METEO, backfillActualsSource(WeatherSource.OPEN_METEO, lat, lon, noPreference))
    }

    @Test
    fun `a suspend-sized hole in the borrowed NWS series requests repair`() {
        val now = LocalDateTime.of(2026, 10, 7, 7, 15)
        val zone = ZoneId.systemDefault()
        fun at(h: Int, m: Int) = now.toLocalDate().atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        // Every 20 min from midnight to 03:00, then nothing until 06:35 — the emulator's rows.
        val rows = (0..9).map { obs(at(0, 0) + it * 20 * 60_000L, 65f - it * 0.3f) } +
            listOf(obs(at(6, 35), 62.6f), obs(at(6, 55), 62.6f))

        val actualsSource = backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon, noPreference)
        val decision = evaluateHourlyBackfillNeed(
            displaySource = actualsSource,
            graphStart = now.minusHours(9),
            graphEnd = now.plusHours(9),
            observations = rows,
            now = now,
        )

        assertTrue(decision.reason, decision.shouldRequest)
        assertTrue(decision.reason, decision.reason.startsWith("max_gap_min="))
    }

    @Test
    fun `judged as itself, Google never requests repair - the bug`() {
        val now = LocalDateTime.of(2026, 10, 7, 7, 15)
        val decision = evaluateHourlyBackfillNeed(
            displaySource = WeatherSource.GOOGLE_WEATHER,
            graphStart = now.minusHours(9),
            graphEnd = now.plusHours(9),
            observations = emptyList(),
            now = now,
        )
        assertFalse(decision.shouldRequest)
        assertEquals("provider_history_in_forecast", decision.reason)
    }

    @Test
    fun `Google borrowing NWS shares the NWS cooldown bucket`() {
        val google = hourlyBackfillSourceKey(
            backfillActualsSource(WeatherSource.GOOGLE_WEATHER, lat, lon, noPreference), lat, lon,
        )
        assertEquals(hourlyBackfillSourceKey(WeatherSource.NWS, lat, lon), google)
    }

    private fun obs(timestamp: Long, temp: Float) = ObservationEntity(
        stationId = "KNUQ",
        stationName = "Moffett",
        timestamp = timestamp,
        temperature = temp,
        condition = "Clear",
        locationLat = lat,
        locationLon = lon,
        api = WeatherSource.NWS.id,
        stationType = StationType.OFFICIAL,
    )
}
