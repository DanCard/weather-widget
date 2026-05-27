package com.weatherwidget.widget

import com.weatherwidget.data.local.DailyExtremeEntity
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
                    api = WeatherSource.NWS.id,
                ),
                currentTempObservation(
                    stationId = "NWS_BLEND",
                    temperature = 54.0f,
                    fetchedAt = 2_000L,
                    timestamp = 1_800L,
                    api = WeatherSource.NWS.id,
                ),
                currentTempObservation(
                    stationId = "OPEN_METEO_MAIN",
                    temperature = 60.0f,
                    fetchedAt = 3_000L,
                    timestamp = 3_000L,
                    api = WeatherSource.OPEN_METEO.id,
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
                    api = WeatherSource.NWS.id,
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
                    api = WeatherSource.OPEN_METEO.id,
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
            currentTempObservation(stationId = "AW020",     temperature = 81.0f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.NWS.id),
            currentTempObservation(stationId = "NWS_BLEND", temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.NWS.id),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        assertNotNull(resolved)
        assertEquals("NWS_BLEND should be preferred over single station", 77.8f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp falls back to single station when no NWS_BLEND present`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "AW020", temperature = 81.0f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.NWS.id),
            currentTempObservation(stationId = "KNUQ",  temperature = 73.0f, fetchedAt = nowMs, timestamp = nowMs - 100, api = WeatherSource.NWS.id),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.NWS)

        assertNotNull(resolved)
        assertEquals("Should fall back to most recent station", 81.0f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp ignores NWS_BLEND when display source is Open-Meteo`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "OPEN_METEO_MAIN", temperature = 74.0f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.OPEN_METEO.id),
            currentTempObservation(stationId = "NWS_BLEND",       temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.NWS.id),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.OPEN_METEO)

        assertNotNull(resolved)
        assertEquals("NWS_BLEND must not bleed into Open-Meteo source", 74.0f, resolved!!.temperature)
    }

    @Test
    fun `resolveObservedCurrentTemp correctly resolves Tomorrow-io observations`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(stationId = "TOMORROW_IO_MAIN", temperature = 72.5f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.TOMORROW_IO.id),
            currentTempObservation(stationId = "NWS_BLEND",        temperature = 77.8f, fetchedAt = nowMs, timestamp = nowMs, api = WeatherSource.NWS.id),
        )

        val resolved = ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.TOMORROW_IO)

        assertNotNull(resolved)
        assertEquals(WeatherSource.TOMORROW_IO.id, resolved!!.source)
        assertEquals(72.5f, resolved.temperature)
    }

    // --- computeDailyExtremes tests (time-aligned IDW algorithm) ---

    @Test
    fun `computeDailyExtremes uses spot temperatures, not official 24h extremes`() {
        // Time-aligned blender: high/low come from the per-timestamp IDW series, NOT from
        // maxTempLast24h. The user's evening view (spot-based) must match tomorrow's history.
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 72f, minTempLast24h = 40f, stationId = "KTEST"),
            observation(timestamp = dayMillis + 3_600_000, temperature = 58f, maxTempLast24h = 74f, minTempLast24h = 38f, stationId = "KTEST"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        val entity = result[0]
        assertEquals(58f, entity.highTemp, 0.01f)
        assertEquals(55f, entity.lowTemp, 0.01f)
        assertEquals(WeatherSource.NWS.id, entity.source)
        assertEquals(37.42, entity.locationLat, 0.001)
    }

    @Test
    fun `computeDailyExtremes falls back to spot readings when official extremes are missing`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = null, minTempLast24h = null, stationId = "KTEST"),
            observation(timestamp = dayMillis + 3_600_000, temperature = 63f, maxTempLast24h = null, minTempLast24h = null, stationId = "KTEST"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(63f, result[0].highTemp, 0.01f)
        assertEquals(55f, result[0].lowTemp, 0.01f)
    }

    @Test
    fun `computeDailyExtremes groups NWS and Open-Meteo observations into separate entities`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 70f, minTempLast24h = 40f, stationId = "KTEST", api = WeatherSource.NWS.id),
            observation(timestamp = dayMillis + 1_800_000, temperature = 60f, maxTempLast24h = 68f, minTempLast24h = 42f, stationId = "OPEN_METEO_MAIN", api = WeatherSource.OPEN_METEO.id),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(2, result.size)
        val nwsEntity = result.first { it.source == WeatherSource.NWS.id }
        val meteoEntity = result.first { it.source == WeatherSource.OPEN_METEO.id }
        // Time-aligned: each source's high = max over its own per-timestamp series, using spot temps
        assertEquals(55f, nwsEntity.highTemp, 0.01f)
        assertEquals(60f, meteoEntity.highTemp, 0.01f)
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
        api: String = WeatherSource.NWS.id,
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
        api = api,
    )

    // --- multi-station IDW blending tests (time-aligned algorithm) ---

    @Test
    fun `computeDailyExtremes IDW near station dominates over far station`() {
        // KNEAR at 1km spot=78°, KFAR at 10km spot=88°.
        // Time-aligned IDW at the shared timestamp: w_near=1, w_far=0.01 → ~(78 + 0.88)/1.01 ≈ 78.1°
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 78f, maxTempLast24h = 80f, minTempLast24h = 50f, stationId = "KNEAR", distanceKm = 1f),
            observation(timestamp = dayMillis, temperature = 88f, maxTempLast24h = 90f, minTempLast24h = 60f, stationId = "KFAR",  distanceKm = 10f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertTrue("Near station should dominate; expected ~78° got ${result[0].highTemp}", result[0].highTemp < 79f)
        assertTrue("Result should be above 78°", result[0].highTemp >= 78f)
    }

    @Test
    fun `computeDailyExtremes IDW two equidistant stations average their spot temps`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 70f, maxTempLast24h = 72f, minTempLast24h = 50f, stationId = "KA", distanceKm = 5f),
            observation(timestamp = dayMillis, temperature = 76f, maxTempLast24h = 80f, minTempLast24h = 44f, stationId = "KB", distanceKm = 5f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        // Equidistant IDW at the single shared timestamp = average of spot temps
        assertEquals(73f, result[0].highTemp, 0.1f) // (70+76)/2
        assertEquals(73f, result[0].lowTemp,  0.1f) // same — only one timestamp in the series
    }

    @Test
    fun `computeDailyExtremes time-aligned max comes from highest single-station reading over time`() {
        // Two readings from the same station at different times. Distance=0 means the IDW at each
        // timestamp returns that station's reading verbatim. Series max = max(55, 58) = 58.
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis,             temperature = 55f, maxTempLast24h = 72f, minTempLast24h = 40f, stationId = "KTEST", distanceKm = 0f),
            observation(timestamp = dayMillis + 3_600_000, temperature = 58f, maxTempLast24h = 74f, minTempLast24h = 38f, stationId = "KTEST", distanceKm = 0f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(58f, result[0].highTemp, 0.01f)
        assertEquals(55f, result[0].lowTemp,  0.01f)
    }

    @Test
    fun `computeDailyExtremes async peaks across stations do not over-count`() {
        // Bug regression: when stations peak at DIFFERENT times, the old per-station-spot-max
        // algorithm blended those independent peaks as if synchronous, producing an inflated
        // high. The time-aligned blender computes IDW at each candidate timestamp, so the max
        // over the series reflects an instant that actually existed.
        val t0 = 1_700_000_000_000L
        val obs = listOf(
            // Station A (close) peaks at t0
            observation(timestamp = t0,             temperature = 75f, maxTempLast24h = null, minTempLast24h = null, stationId = "KA", distanceKm = 2f),
            observation(timestamp = t0 + 7_200_000, temperature = 70f, maxTempLast24h = null, minTempLast24h = null, stationId = "KA", distanceKm = 2f),
            // Station B (close) peaks 2 hours later at t0+2h
            observation(timestamp = t0,             temperature = 70f, maxTempLast24h = null, minTempLast24h = null, stationId = "KB", distanceKm = 2f),
            observation(timestamp = t0 + 7_200_000, temperature = 75f, maxTempLast24h = null, minTempLast24h = null, stationId = "KB", distanceKm = 2f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        // Per-station-spot-max (old) would give IDW(75, 75) ≈ 75° — non-existent in reality.
        // Time-aligned (new): each timestamp blends to (75+70)/2 ≈ 72.5°. Max over series ≈ 72.5°.
        assertEquals(1, result.size)
        assertTrue("Blended high should be ~72.5 (the actual instant), not 75 (sync-peak artifact). Got ${result[0].highTemp}", result[0].highTemp < 73f)
        assertTrue("Blended high should be above 72°", result[0].highTemp >= 72f)
    }

    @Test
    fun `computeDailyExtremes excludes NWS_BLEND synthetic observation`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 72f, maxTempLast24h = 72f, minTempLast24h = 50f, stationId = "KREAL"),
            observation(timestamp = dayMillis, temperature = 99f, maxTempLast24h = 99f, minTempLast24h = 10f, stationId = "NWS_BLEND"),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(72f, result[0].highTemp, 0.01f) // NWS_BLEND excluded
    }

    @Test
    fun `computeDailyExtremes spot-temp IDW with no official extremes`() {
        // Near station spot=70°, far station spot=90°. IDW should weight near heavily → ~70.2°.
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observation(timestamp = dayMillis, temperature = 70f, maxTempLast24h = null, minTempLast24h = null, stationId = "KNEAR", distanceKm = 1f),
            observation(timestamp = dayMillis, temperature = 90f, maxTempLast24h = null, minTempLast24h = null, stationId = "KFAR",  distanceKm = 10f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertTrue("Near station should dominate; expected <71° got ${result[0].highTemp}", result[0].highTemp < 71f)
    }

    private fun currentTempObservation(
        stationId: String,
        temperature: Float,
        fetchedAt: Long,
        timestamp: Long,
        api: String = WeatherSource.NWS.id,
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
            api = api,
        )
    }

    // --- Precipitation aggregation tests ---

    @Test
    fun `computeDailyExtremes sums hourly precip into daily total`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 5, 25)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        // Create observations spread across the day with different precip amounts
        val obs = listOf(
            observationWithPrecip(dayStartMs + 3600_000, 70f, 1.5f),       // 1 AM
            observationWithPrecip(dayStartMs + 7200_000, 72f, 2.3f),       // 2 AM
            observationWithPrecip(dayStartMs + 10800_000, 68f, null),      // 3 AM - null precip
            observationWithPrecip(dayStartMs + 14400_000, 65f, 0.0f),      // 4 AM - zero precip
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        // 1.5 + 2.3 = 3.8 (null and 0.0 are excluded from sum)
        assertEquals(3.8f, result[0].precipAmountMm!!, 0.01f)
    }

    @Test
    fun `computeDailyExtremes splits precip into day and night buckets`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 5, 25)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        // Daytime: 8AM-8PM = 8h to 20h
        // Nighttime: 8PM-12AM = 20h to 24h (stays within same day for grouping)
        val obs = listOf(
            // Daytime observations
            observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, 2.0f),  // 10 AM - day
            observationWithPrecip(dayStartMs + 14 * 3600_000, 72f, 3.5f),  // 2 PM - day
            // Nighttime observations (before midnight)
            observationWithPrecip(dayStartMs + 22 * 3600_000, 65f, 1.0f),  // 10 PM - night
            observationWithPrecip(dayStartMs + 23 * 3600_000, 63f, 4.2f),  // 11 PM - night
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(5.5f, result[0].precipDayMm!!, 0.01f)   // 2.0 + 3.5
        assertEquals(5.2f, result[0].precipNightMm!!, 0.01f)  // 1.0 + 4.2
        assertEquals(10.7f, result[0].precipAmountMm!!, 0.01f) // total
    }

    @Test
    fun `computeDailyExtremes handles null precip for all observations`() {
        val dayMillis = 1_700_000_000_000L
        val obs = listOf(
            observationWithPrecip(dayMillis, 70f, null),
            observationWithPrecip(dayMillis + 3600_000, 72f, null),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertNull(result[0].precipAmountMm)
        assertNull(result[0].precipDayMm)
        assertNull(result[0].precipNightMm)
    }

    @Test
    fun `extremesToDailyActuals maps all precip fields`() {
        val extremes = listOf(
            DailyExtremeEntity(
                date = 1_700_000_000_000L,
                source = WeatherSource.NWS.id,
                locationLat = 37.42,
                locationLon = -122.08,
                highTemp = 75f,
                lowTemp = 55f,
                condition = "Rain",
                updatedAt = 1_700_000_000_000L,
                precipAmountMm = 10.5f,
                precipDayMm = 6.2f,
                precipNightMm = 4.3f,
            )
        )

        val result = ObservationResolver.extremesToDailyActuals(extremes)

        assertEquals(1, result.size)
        assertEquals(10.5f, result[0].precipAmountMm!!, 0.01f)
        assertEquals(6.2f, result[0].precipDayMm!!, 0.01f)
        assertEquals(4.3f, result[0].precipNightMm!!, 0.01f)
    }

    @Test
    fun `extremesToDailyActualsBySource maps all precip fields`() {
        val extremes = listOf(
            DailyExtremeEntity(
                date = 1_700_000_000_000L,
                source = WeatherSource.NWS.id,
                locationLat = 37.42,
                locationLon = -122.08,
                highTemp = 75f,
                lowTemp = 55f,
                condition = "Rain",
                updatedAt = 1_700_000_000_000L,
                precipAmountMm = 8.1f,
                precipDayMm = 5.0f,
                precipNightMm = 3.1f,
            )
        )

        val result = ObservationResolver.extremesToDailyActualsBySource(extremes, 37.42, -122.08)

        val nwsActuals = result[WeatherSource.NWS.id]
        assertNotNull(nwsActuals)
        val actual = nwsActuals!!.values.first()
        assertEquals(8.1f, actual.precipAmountMm!!, 0.01f)
        assertEquals(5.0f, actual.precipDayMm!!, 0.01f)
        assertEquals(3.1f, actual.precipNightMm!!, 0.01f)
    }

    private fun observationWithPrecip(
        timestamp: Long,
        temperature: Float,
        precipAmountMm: Float?,
        stationId: String = "KTEST",
        api: String = WeatherSource.NWS.id,
    ): ObservationEntity = ObservationEntity(
        stationId = stationId,
        stationName = "Test Station",
        timestamp = timestamp,
        temperature = temperature,
        condition = "Rain",
        locationLat = 37.42,
        locationLon = -122.08,
        api = api,
        precipAmountMm = precipAmountMm,
    )
}
