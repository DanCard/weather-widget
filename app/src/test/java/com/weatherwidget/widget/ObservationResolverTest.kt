package com.weatherwidget.widget

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class ObservationResolverTest {

    @Test
    fun `resolveObservedCurrentTemp picks newest observation for active source`() {
        val observations =
            listOf(
                currentTempObservation(
                    stationId = "NWS_BLEND",
                    temperature = 53.0f,
                    fetchedAt = 1_000L,
                    timestamp = 900L,
                ),
                currentTempObservation(
                    stationId = "NWS_BLEND",
                    temperature = 54.0f,
                    fetchedAt = 2_000L,
                    timestamp = 1_800L,
                ),
                currentTempObservation(
                    stationId = "OPEN_METEO_MAIN",
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    timestamp = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                observations = observations,
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
        val observations =
            listOf(
                currentTempObservation(
                    stationId = "NWS_BLEND",
                    temperature = 51.0f,
                    fetchedAt = 7_000L,
                    timestamp = 5_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                observations = observations,
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
        val observations =
            listOf(
                currentTempObservation(
                    stationId = "OPEN_METEO_MAIN",
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    timestamp = 3_000L,
                ),
            )

        val resolved =
            ObservationResolver.resolveObservedCurrentTemp(
                observations = observations,
                displaySource = WeatherSource.NWS,
            )

        assertNull(resolved)
    }

    @Test
    fun `resolveObservedCurrentTemp prefers NWS_BLEND over single station for NWS source`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "AW020",     temperature = 81.0f, fetchedAt = nowMs, timestamp = nowMs),
            currentTempObservation(stationId = "NWS_BLEND", temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        assertNotNull(resolved)
        assertEquals("NWS_BLEND should be preferred over single station", 77.8f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp falls back to single station when no NWS_BLEND present`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "AW020", temperature = 81.0f, fetchedAt = nowMs, timestamp = nowMs),
            currentTempObservation(stationId = "KNUQ",  temperature = 73.0f, fetchedAt = nowMs, timestamp = nowMs - 100),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        assertNotNull(resolved)
        assertEquals("Should fall back to most recent station", 81.0f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp ignores NWS_BLEND when display source is Open-Meteo`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "OPEN_METEO_MAIN", temperature = 74.0f, fetchedAt = nowMs, timestamp = nowMs),
            currentTempObservation(stationId = "NWS_BLEND",       temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.OPEN_METEO)

        assertNotNull(resolved)
        assertEquals("NWS_BLEND must not bleed into Open-Meteo source", 74.0f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp correctly resolves Tomorrow-io observations`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "TOMORROW_IO_MAIN", temperature = 72.5f, fetchedAt = nowMs, timestamp = nowMs),
            currentTempObservation(stationId = "NWS_BLEND",        temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.TOMORROW_IO)

        assertNotNull(resolved)
        assertEquals(WeatherSource.TOMORROW_IO.id, resolved!!.source)
        assertEquals(72.5f, resolved.temperature)
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

    @Test
    fun `mergeDailyActualsBySource preserves widest known today bounds`() {
        val today = java.time.LocalDate.of(2026, 4, 14)
        val persisted = mapOf(
            WeatherSource.NWS.id to mapOf(
                today to ObservationResolver.DailyActual(
                    date = today,
                    highTemp = 63.82f,
                    lowTemp = 46.30f,
                    condition = "Clear",
                )
            )
        )
        val live = mapOf(
            WeatherSource.NWS.id to mapOf(
                today to ObservationResolver.DailyActual(
                    date = today,
                    highTemp = 63.82f,
                    lowTemp = 60.53f,
                    condition = "Clear",
                )
            )
        )

        val merged = ObservationResolver.mergeDailyActualsBySource(
            primary = persisted,
            secondary = live,
        )

        val actual = merged[WeatherSource.NWS.id]?.get(today)
        assertNotNull(actual)
        assertEquals(63.82f, actual!!.highTemp, 0.01f)
        assertEquals(46.30f, actual.lowTemp, 0.01f)
    }

    private fun observation(
        timestamp: Long,
        temperature: Float,
        maxTempLast24h: Float?,
        minTempLast24h: Float?,
        stationId: String = "KTEST",
        distanceKm: Float = 0f,
    ): ObservationEntity = ObservationEntity(
        stationId = stationId,
        stationName = "Test Station",
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        maxTempLast24h = maxTempLast24h,
        minTempLast24h = minTempLast24h,
        api = "NWS",
    )

    // --- multi-station IDW blending tests ---

    @Test
    fun `blendExtremes IDW near station dominates over far station`() {
        // Station NEAR at 1 km with max=80°, KFAR at 10 km with max=90°.
        // Old raw-max behavior would return 90°. IDW should return ~80° (1km has 100x the weight).
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 78f, maxTempLast24h = 80f, minTempLast24h = 50f, stationId = "KNEAR", distanceKm = 1f),
            observation(timestamp = dayMillis, temperature = 88f, maxTempLast24h = 90f, minTempLast24h = 60f, stationId = "KFAR",  distanceKm = 10f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        // w_near = 1/1² = 1.0, w_far = 1/100 = 0.01 → blend ≈ (80*1 + 90*0.01) / 1.01 ≈ 80.1°
        assertTrue("Near station should dominate; expected ~80° got ${result[0].highTemp}", result[0].highTemp < 81f)
        assertTrue("Result should be above 80°", result[0].highTemp >= 80f)
    }

    @Test
    fun `blendExtremes IDW two equidistant stations average their extremes`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 70f, maxTempLast24h = 72f, minTempLast24h = 50f, stationId = "KA", distanceKm = 5f),
            observation(timestamp = dayMillis, temperature = 76f, maxTempLast24h = 80f, minTempLast24h = 44f, stationId = "KB", distanceKm = 5f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        assertEquals(76f, result[0].highTemp, 0.1f) // (72+80)/2
        assertEquals(47f, result[0].lowTemp,  0.1f) // (50+44)/2
    }

    @Test
    fun `blendExtremes per-station aggregation same station multiple readings uses max extreme`() {
        // Two readings from the same station at different times — only one IDW entry should result.
        // The station's extreme is max(72, 74) = 74°, not 72° (first) or an average.
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 72f, minTempLast24h = 40f, stationId = "KTEST", distanceKm = 2f),
            observation(timestamp = dayMillis + 3_600_000, temperature = 58f, maxTempLast24h = 74f, minTempLast24h = 38f, stationId = "KTEST", distanceKm = 2f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(74f, result[0].highTemp, 0.01f)
        assertEquals(38f, result[0].lowTemp,  0.01f)
    }

    @Test
    fun `computeDailyExtremes excludes NWS_BLEND synthetic observation`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 72f, maxTempLast24h = 72f, minTempLast24h = 50f, stationId = "KREAL"),
            observation(timestamp = dayMillis, temperature = 99f, maxTempLast24h = 99f, minTempLast24h = 10f, stationId = "NWS_BLEND"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(72f, result[0].highTemp, 0.01f) // NWS_BLEND excluded
    }

    @Test
    fun `blendExtremes spot-temp fallback also uses IDW when no official extremes`() {
        // Near station has spot temp 70°, far station has spot temp 90°. No official extremes.
        // IDW should weight near station heavily → result near 70°, not 90°.
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 70f, maxTempLast24h = null, minTempLast24h = null, stationId = "KNEAR", distanceKm = 1f),
            observation(timestamp = dayMillis, temperature = 90f, maxTempLast24h = null, minTempLast24h = null, stationId = "KFAR",  distanceKm = 10f),
        )

        val result = ObservationResolver.aggregateObservationsToDaily(obs)

        assertEquals(1, result.size)
        assertTrue("Spot-temp fallback should weight near station; expected <71° got ${result[0].highTemp}", result[0].highTemp < 71f)
    }

    private fun currentTempObservation(
        stationId: String,
        temperature: Float,
        fetchedAt: Long,
        timestamp: Long,
    ): ObservationEntity {
        return ObservationEntity(
            stationId = stationId,
            stationName = "Test Station",
            timestamp = timestamp,
            temperature = temperature,
            condition = "Clear",
            locationLat = 37.42,
            locationLon = -122.08,
            fetchedAt = fetchedAt,
            api = "NWS",
        )
    }
}
