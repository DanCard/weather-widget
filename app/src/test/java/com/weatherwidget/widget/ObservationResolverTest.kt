package com.weatherwidget.widget

import com.weatherwidget.data.local.CurrentTempEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationResolverTest {

    @Test
    fun `resolveObservedCurrentTemp picks newest observation for active source`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 53.0f,
                    fetchedAt = 1_000L,
                    observedAt = 900L,
                ),
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 54.0f,
                    fetchedAt = 2_000L,
                    observedAt = 1_800L,
                ),
                currentTemp(
                    source = WeatherSource.OPEN_METEO.id,
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNotNull(resolved)
        assertEquals(54.0f, resolved!!.temperature)
        assertEquals(1_800L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(2_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp uses observedAt for ordering`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.NWS.id,
                    temperature = 51.0f,
                    fetchedAt = 7_000L,
                    observedAt = 5_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNotNull(resolved)
        assertEquals(51.0f, resolved!!.temperature)
        assertEquals(5_000L, resolved.observedAt)
        assertEquals(WeatherSource.NWS.id, resolved.source)
        assertEquals(7_000L, resolved.rowFetchedAt)
    }

    @Test
    fun `resolveObservedCurrentTemp returns null when active source has no current temp`() {
        val currentTemps =
            listOf(
                currentTemp(
                    source = WeatherSource.OPEN_METEO.id,
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    observedAt = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                currentTemps = currentTemps,
                displaySource = WeatherSource.NWS,
            )

        assertNull(resolved)
    }

    // --- aggregateObservationsToDaily tests ---

    @Test
    fun `aggregateObservationsToDaily uses official 24h extremes when present`() {
        val dayMillis = 1_700_000_000_000L // arbitrary fixed epoch in a single calendar day
        val obs = listOf(
            observation(timestamp = dayMillis,       temperature = 55f, maxTempLast24h = 72f, minTempLast24h = 40f),
            observation(timestamp = dayMillis + 3600_000, temperature = 58f, maxTempLast24h = 74f, minTempLast24h = 38f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        assertEquals(74f, result[0].highTemp)
        assertEquals(38f, result[0].lowTemp)
    }

    @Test
    fun `aggregateObservationsToDaily falls back to spot readings when official extremes are null`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,       temperature = 55f, maxTempLast24h = null, minTempLast24h = null),
            observation(timestamp = dayMillis + 3600_000, temperature = 62f, maxTempLast24h = null, minTempLast24h = null),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        assertEquals(62f, result[0].highTemp)
        assertEquals(55f, result[0].lowTemp)
    }

    @Test
    fun `aggregateObservationsToDaily handles mixed null and non-null official extremes`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,       temperature = 55f, maxTempLast24h = 70f, minTempLast24h = null),
            observation(timestamp = dayMillis + 3600_000, temperature = 62f, maxTempLast24h = null, minTempLast24h = 39f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        // officialHighs = [70f] -> max = 70f; officialLows = [39f] -> min = 39f
        assertEquals(70f, result[0].highTemp)
        assertEquals(39f, result[0].lowTemp)
    }

    // --- computeDailyExtremes tests ---

    @Test
    fun `computeDailyExtremes prefers official extremes when present`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 72f, minTempLast24h = 40f, stationId = "KTEST"),
            observation(timestamp = dayMillis + 3_600_000, temperature = 58f, maxTempLast24h = 74f, minTempLast24h = 38f, stationId = "KTEST"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, 37.42, -122.08)

        assertEquals(1, result.size)
        val entity = result[0]
        assertEquals(74f, entity.highTemp)
        assertEquals(38f, entity.lowTemp)
        assertEquals(com.weatherwidget.data.model.WeatherSource.NWS.id, entity.source)
        assertEquals(37.42, entity.locationLat, 0.001)
    }

    @Test
    fun `computeDailyExtremes falls back to spot readings when official extremes are missing`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = null, minTempLast24h = null, stationId = "KTEST"),
            observation(timestamp = dayMillis + 3_600_000, temperature = 63f, maxTempLast24h = null, minTempLast24h = null, stationId = "KTEST"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(63f, result[0].highTemp)
        assertEquals(55f, result[0].lowTemp)
    }

    @Test
    fun `computeDailyExtremes groups NWS and Open-Meteo observations into separate entities`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 70f, minTempLast24h = 40f, stationId = "KTEST"),
            observation(timestamp = dayMillis + 1_800_000, temperature = 60f, maxTempLast24h = 68f, minTempLast24h = 42f, stationId = "OPEN_METEO_MAIN"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, 37.42, -122.08)

        assertEquals(2, result.size)
        val nwsEntity = result.first { it.source == com.weatherwidget.data.model.WeatherSource.NWS.id }
        val meteoEntity = result.first { it.source == com.weatherwidget.data.model.WeatherSource.OPEN_METEO.id }
        assertEquals(70f, nwsEntity.highTemp)
        assertEquals(68f, meteoEntity.highTemp)
    }

    private fun observation(
        timestamp: Long,
        temperature: Float,
        maxTempLast24h: Float?,
        minTempLast24h: Float?,
        stationId: String = "KTEST",
    ): ObservationEntity = ObservationEntity(
        stationId = stationId,
        stationName = "Test Station",
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        maxTempLast24h = maxTempLast24h,
        minTempLast24h = minTempLast24h,
    )

    private fun currentTemp(
        source: String,
        temperature: Float,
        fetchedAt: Long,
        observedAt: Long,
    ): CurrentTempEntity {
        return CurrentTempEntity(
            date = "2026-02-26",
            source = source,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = temperature,
            observedAt = observedAt,
            condition = "Clear",
            fetchedAt = fetchedAt,
        )
    }
}
