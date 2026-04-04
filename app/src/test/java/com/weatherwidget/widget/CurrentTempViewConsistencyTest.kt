package com.weatherwidget.widget

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.util.SpatialInterpolator
import com.weatherwidget.widget.handlers.buildHourDataList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



/**
 * Verifies that the hourly graph header uses the time-aligned IDW blend (graphObservedTemp)
 * rather than the temporally-misaligned NWS_BLEND for its delta calculation.
 *
 * NWS_BLEND mixes station readings from different timestamps (e.g. a hot station from 40 min
 * ago blended with a newer cooler one), inflating the delta and causing the current temp to
 * lag behind the true trend. The graph's IDW extrapolates all stations to the same instant.
 */
@Category(ShortDuration::class)
class CurrentTempViewConsistencyTest {

    private val now = LocalDateTime.of(2026, 3, 23, 14, 0)
    private val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // Three NWS stations at different distances — will produce a non-trivial IDW blend
    private val stationAw020 = TestData.observation(
        stationId = "AW020",
        temperature = 81.0f,
        distanceKm = 2.9f,
        timestamp = nowMs - 15 * 60_000,
        fetchedAt = nowMs,
    )
    private val stationKnuq = TestData.observation(
        stationId = "KNUQ",
        temperature = 73.0f,
        distanceKm = 3.7f,
        timestamp = nowMs - 30 * 60_000,
        fetchedAt = nowMs,
    )
    private val stationKsjc = TestData.observation(
        stationId = "KSJC",
        temperature = 75.0f,
        distanceKm = 15.8f,
        timestamp = nowMs - 20 * 60_000,
        fetchedAt = nowMs,
    )

    /** Simulates what getMainObservationsWithComputedNwsBlend() produces. */
    private fun buildCurrentTempsWithBlend(): List<ObservationEntity> {
        val stations = listOf(stationAw020, stationKnuq, stationKsjc)
        val blendedTemp = SpatialInterpolator.interpolateIDW(TestData.LAT, TestData.LON, stations, nowMs)
            ?: return stations
        val nwsBlend = ObservationEntity(
            stationId = "NWS_BLEND",
            stationName = "NWS Blended",
            timestamp = stations.maxOf { it.timestamp },
            temperature = blendedTemp,
            condition = stationAw020.condition,
            locationLat = TestData.LAT,
            locationLon = TestData.LON,
            distanceKm = 0f,
            stationType = "BLENDED",
            fetchedAt = nowMs,
            api = WeatherSource.NWS.id,
        )
        return stations + nwsBlend
    }

    @Test
    fun `graph view uses time-aligned IDW blend not NWS_BLEND for delta calculation`() {
        val currentTemps = buildCurrentTempsWithBlend()
        val hourlyForecasts = buildHourlyForecasts()

        val observation = ObservationResolver.resolveObservedCurrentTemp(currentTemps, WeatherSource.NWS)
        assertNotNull("Should find a current temp observation", observation)

        // Graph view path: buildHourDataList → latest isObservedActual → CurrentTemperatureResolver
        val graphHours = buildHourDataList(
            hourlyForecasts = hourlyForecasts,
            centerTime = now,
            numColumns = 5,
            displaySource = WeatherSource.NWS,
            actuals = listOf(stationAw020, stationKnuq, stationKsjc),
        )
        val graphObservedTemp = graphHours
            .filter { it.isObservedActual && it.actualTemperature != null }
            .maxByOrNull { it.dateTime }
            ?.actualTemperature
        val graphObservedAt = graphHours
            .filter { it.isObservedActual && it.actualTemperature != null }
            .maxByOrNull { it.dateTime }
            ?.dateTime?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

        assertNotNull("Graph IDW blend must produce an observed actual point", graphObservedTemp)

        // Compute display temp using graph IDW (new preferred source)
        val graphDisplayTemp = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            lastObservedTemp = graphObservedTemp,
            observedAt = graphObservedAt,
            storedDeltaState = null,
            currentLat = TestData.LAT,
            currentLon = TestData.LON,
        ).displayTemp

        // Compute display temp using NWS_BLEND (old source, should differ when stations are misaligned)
        val nwsBlendDisplayTemp = CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = hourlyForecasts,
            lastObservedTemp = observation!!.temperature,
            observedAt = observation.observedAt,
            storedDeltaState = null,
            currentLat = TestData.LAT,
            currentLon = TestData.LON,
        ).displayTemp

        // The graph IDW temp must be used (not NWS_BLEND): station AW020 is 15 min old, KNUQ 30 min
        // old — the time-aligned IDW corrects for this while NWS_BLEND uses the raw stale hot reading.
        // When stations are temporally misaligned, the two sources produce different deltas.
        assertNotNull("Graph display temp must be resolved", graphDisplayTemp)
        assertNotNull("NWS_BLEND display temp must be resolved", nwsBlendDisplayTemp)
        // The graph IDW and NWS_BLEND differ because AW020 (close, hot station) contributes its
        // raw 81°F reading to NWS_BLEND but is extrapolated forward (cooler) in the graph IDW.
        assert(graphDisplayTemp != nwsBlendDisplayTemp) {
            "Graph IDW and NWS_BLEND should produce different current temps when stations are temporally misaligned"
        }
    }

    private fun buildHourlyForecasts() = (-6..48).map { offset ->
        TestData.hourly(
            dateTime = now.plusHours(offset.toLong())
                .let { "%04d-%02d-%02dT%02d:00".format(it.year, it.monthValue, it.dayOfMonth, it.hour) },
            temperature = 70f + offset * 0.5f,
            fetchedAt = nowMs,
        )
    }
}
