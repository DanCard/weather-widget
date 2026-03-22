package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Integration test for the current temperature displayed at the top left
 * of the daily forecast widget.
 *
 * Exercises the full pipeline:
 *   DB (observations + hourly forecasts) → ObservationResolver → CurrentTemperatureResolver → display temp
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentTemperatureIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase

    private val lat = 37.42
    private val lon = -122.08
    private val todayStr = "2026-02-25"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `interpolated estimate plus observation delta produces correct display temp`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val nowMs = toEpochMs(now)
        val fetchedAt = nowMs

        // Hourly forecasts: 10:00 = 60°F, 11:00 = 70°F → interpolated at 10:30 = 65°F
        insertHourlyForecast("10:00", 60f, fetchedAt)
        insertHourlyForecast("11:00", 70f, fetchedAt)

        // NWS observation at 10:15 with temp 63°F (observation is 3°F below estimated-at-obs-time)
        val obsTime = now.minusMinutes(15)
        val obsFetchedAt = toEpochMs(obsTime)
        insertObservation(
            stationId = "AW020",
            timestamp = toEpochMs(obsTime),
            temperature = 63f,
            fetchedAt = obsFetchedAt,
        )

        val observations = db.observationDao().getRecentObservations(nowMs - 86_400_000)
        val observedCurrentTemp = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)
        assertNotNull("Should resolve observed current temp from NWS observations", observedCurrentTemp)

        val startMs = toEpochMs(LocalDateTime.parse("${todayStr}T00:00"))
        val endMs = toEpochMs(LocalDateTime.parse("${todayStr}T23:00"))
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startMs, endMs, lat, lon,
        )

        val result = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            observedCurrentTemp = observedCurrentTemp!!.temperature,
            observedAt = observedCurrentTemp.observedAt,
            storedDeltaState = null,
            currentLat = lat,
            currentLon = lon,
        )

        // Estimated at 10:15 (obs time) = 60 + 0.25 * 10 = 62.5
        // Delta = 63 - 62.5 = 0.5
        // Estimated at 10:30 (now) = 65
        // Display = 65 + 0.5 = 65.5
        assertEquals(65f, result.estimatedTemp!!, 0.01f)
        assertEquals(0.5f, result.appliedDelta!!, 0.01f)
        assertEquals(65.5f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `falls back to observed temp when no hourly forecasts exist`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val nowMs = toEpochMs(now)

        insertObservation(
            stationId = "KSJC",
            timestamp = nowMs,
            temperature = 72f,
            fetchedAt = nowMs,
        )

        val observations = db.observationDao().getRecentObservations(nowMs - 86_400_000)
        val observedCurrentTemp = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        val result = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = emptyList(),
            observedCurrentTemp = observedCurrentTemp!!.temperature,
            observedAt = observedCurrentTemp.observedAt,
            storedDeltaState = null,
            currentLat = lat,
            currentLon = lon,
        )

        assertEquals(72f, result.displayTemp!!, 0.01f)
        assertNull(result.estimatedTemp)
    }

    @Test
    fun `uses interpolated temp when no observation exists`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val nowMs = toEpochMs(now)
        val fetchedAt = nowMs

        insertHourlyForecast("10:00", 55f, fetchedAt)
        insertHourlyForecast("11:00", 65f, fetchedAt)

        val observations = db.observationDao().getRecentObservations(nowMs - 86_400_000)
        val observedCurrentTemp = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        val startMs = toEpochMs(LocalDateTime.parse("${todayStr}T00:00"))
        val endMs = toEpochMs(LocalDateTime.parse("${todayStr}T23:00"))
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startMs, endMs, lat, lon,
        )

        val result = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            observedCurrentTemp = observedCurrentTemp?.temperature,
            observedAt = observedCurrentTemp?.observedAt,
            storedDeltaState = null,
            currentLat = lat,
            currentLon = lon,
        )

        // Interpolated at 10:30 between 55 and 65 = 60
        assertEquals(60f, result.displayTemp!!, 0.01f)
        assertEquals(60f, result.estimatedTemp!!, 0.01f)
    }

    @Test
    fun `resolves correct observed temp for non-NWS source from DB`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 10, 0)
        val nowMs = toEpochMs(now)

        insertObservation(stationId = "OPEN_METEO_MAIN", timestamp = nowMs, temperature = 68f, fetchedAt = nowMs)
        insertObservation(stationId = "AW020", timestamp = nowMs - 10_000, temperature = 65f, fetchedAt = nowMs - 10_000)

        val observations = db.observationDao().getRecentObservations(nowMs - 86_400_000)

        val nwsObs = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)
        val meteoObs = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.OPEN_METEO)

        assertNotNull("Should resolve NWS observation", nwsObs)
        assertNotNull("Should resolve Open-Meteo observation", meteoObs)
        assertEquals(65f, nwsObs!!.temperature, 0.01f)
        assertEquals(68f, meteoObs!!.temperature, 0.01f)
    }

    @Test
    fun `stale hourly data is detected when fetchedAt is old`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 10, 30)
        val nowMs = toEpochMs(now)
        val staleFetchedAt = nowMs - (3 * 60 * 60 * 1000) // 3 hours ago

        insertHourlyForecast("10:00", 60f, staleFetchedAt)
        insertHourlyForecast("11:00", 70f, staleFetchedAt)

        val startMs = toEpochMs(LocalDateTime.parse("${todayStr}T00:00"))
        val endMs = toEpochMs(LocalDateTime.parse("${todayStr}T23:00"))
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startMs, endMs, lat, lon,
        )

        val result = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            observedCurrentTemp = null,
            observedAt = null,
            storedDeltaState = null,
            currentLat = lat,
            currentLon = lon,
        )

        assertTrue("Should detect stale estimate", result.isStaleEstimate)
        assertEquals(65f, result.displayTemp!!, 0.01f)
    }

    @Test
    fun `stored delta decays over time and resets on new observation`() = runTest {
        val now = LocalDateTime.of(2026, 2, 25, 12, 0)
        val nowMs = toEpochMs(now)
        val fetchedAt = nowMs

        insertHourlyForecast("12:00", 70f, fetchedAt)
        insertHourlyForecast("13:00", 75f, fetchedAt)

        // Stored delta from 2.5 hours ago: -3°F
        val storedDelta = CurrentTemperatureDeltaState(
            delta = -3f,
            lastObservedTemp = 67f,
            lastObservedAt = nowMs - (150 * 60 * 1000), // 2.5 hours
            updatedAtMs = nowMs - (150 * 60 * 1000),
            sourceId = WeatherSource.NWS.id,
            locationLat = lat,
            locationLon = lon,
        )

        val startMs = toEpochMs(LocalDateTime.parse("${todayStr}T00:00"))
        val endMs = toEpochMs(LocalDateTime.parse("${todayStr}T23:00"))
        val hourlyForecasts = db.hourlyForecastDao().getHourlyForecasts(
            startMs, endMs, lat, lon,
        )

        // First call with stored delta — should apply decayed delta
        val result1 = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            observedCurrentTemp = 67f,
            observedAt = nowMs - (150 * 60 * 1000),
            storedDeltaState = storedDelta,
            currentLat = lat,
            currentLon = lon,
        )

        // After 2.5h total elapsed (1.5h into 3h decay period): delta = -3 * 0.5 = -1.5
        assertEquals(-1.5f, result1.appliedDelta!!, 0.01f)
        assertEquals(70f - 1.5f, result1.displayTemp!!, 0.01f)

        // Second call with new observation — should recompute delta
        val newObsTemp = 68f
        val result2 = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            observedCurrentTemp = newObsTemp,
            observedAt = nowMs,
            storedDeltaState = result1.updatedDeltaState,
            currentLat = lat,
            currentLon = lon,
        )

        // New observation at 12:00, estimated at 12:00 = 70, delta = 68 - 70 = -2
        assertEquals(-2f, result2.appliedDelta!!, 0.01f)
        assertEquals(68f, result2.displayTemp!!, 0.01f)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun insertHourlyForecast(
        hourStr: String,
        temp: Float,
        fetchedAt: Long,
    ) {
        db.hourlyForecastDao().insertAll(
            listOf(
                HourlyForecastEntity(
                    dateTime = toEpochMs(LocalDateTime.parse("${todayStr}T$hourStr")),
                    locationLat = lat,
                    locationLon = lon,
                    temperature = temp,
                    condition = "Clear",
                    source = WeatherSource.NWS.id,
                    fetchedAt = fetchedAt,
                )
            )
        )
    }

    private suspend fun insertObservation(
        stationId: String,
        timestamp: Long,
        temperature: Float,
        fetchedAt: Long,
    ) {
        db.observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = stationId,
                    stationName = "Test $stationId",
                    timestamp = timestamp,
                    temperature = temperature,
                    condition = "Clear",
                    locationLat = lat,
                    locationLon = lon,
                    distanceKm = 5f,
                    stationType = "OFFICIAL",
                    fetchedAt = fetchedAt,
                    api = ObservationResolver.inferSource(stationId),
                )
            )
        )
    }

    private fun toEpochMs(dateTime: LocalDateTime): Long {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
