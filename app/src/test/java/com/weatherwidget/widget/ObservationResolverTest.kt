package com.weatherwidget.widget

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



import com.weatherwidget.data.model.DailyHistory
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

    @Test
    fun `resolveObservedCurrentTemp ignores cached silurian synthetic rows`() {
        val nowMs = 1_000_000L
        val observations = listOf(
            currentTempObservation(
                stationId = "SILURIAN_MAIN",
                temperature = 91f,
                fetchedAt = nowMs,
                timestamp = nowMs,
                api = WeatherSource.SILURIAN.id,
            ),
        )

        assertNull(
            ObservationResolver.resolveObservedCurrentTemp(observations, WeatherSource.SILURIAN),
        )
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
        assertEquals(58f, entity.computedHighTemp, 0.01f)
        assertEquals(55f, entity.computedLowTemp, 0.01f)
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
        assertEquals(63f, result[0].computedHighTemp, 0.01f)
        assertEquals(55f, result[0].computedLowTemp, 0.01f)
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
        assertEquals(55f, nwsEntity.computedHighTemp, 0.01f)
        assertEquals(60f, meteoEntity.computedHighTemp, 0.01f)
    }

    private fun extreme(date: java.time.LocalDate, high: Float, low: Float) = com.weatherwidget.data.model.DailyHistory(
        date = date.toEpochDay() * 86_400_000L,
        source = WeatherSource.NWS.id,
        locationLat = 37.42,
        locationLon = -122.08,
        computedHighTemp = high,
        computedLowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `mergeDailyActualsBySource preserves widest known today bounds`() {
        val today = java.time.LocalDate.of(2026, 4, 14)
        val persisted = mapOf(
            WeatherSource.NWS.id to mapOf(
                today to extreme(today, 63.82f, 46.30f)
            )
        )
        val live = mapOf(
            WeatherSource.NWS.id to mapOf(
                today to extreme(today, 63.82f, 60.53f)
            )
        )

        val merged = ObservationResolver.mergeDailyActualsBySource(
            primary = persisted,
            secondary = live,
        )

        val actual = merged[WeatherSource.NWS.id]?.get(today)
        assertNotNull(actual)
        assertEquals(63.82f, actual!!.computedHighTemp, 0.01f)
        assertEquals(46.30f, actual.computedLowTemp, 0.01f)
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
        assertTrue("Near station should dominate; expected ~78° got ${result[0].computedHighTemp}", result[0].computedHighTemp < 79f)
        assertTrue("Result should be above 78°", result[0].computedHighTemp >= 78f)
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
        assertEquals(73f, result[0].computedHighTemp, 0.1f) // (70+76)/2
        assertEquals(73f, result[0].computedLowTemp,  0.1f) // same — only one timestamp in the series
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

        assertEquals(58f, result[0].computedHighTemp, 0.01f)
        assertEquals(55f, result[0].computedLowTemp,  0.01f)
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
        assertTrue("Blended high should be ~72.5 (the actual instant), not 75 (sync-peak artifact). Got ${result[0].computedHighTemp}", result[0].computedHighTemp < 73f)
        assertTrue("Blended high should be above 72°", result[0].computedHighTemp >= 72f)
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
        assertEquals(72f, result[0].computedHighTemp, 0.01f) // NWS_BLEND excluded
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
        assertTrue("Near station should dominate; expected <71° got ${result[0].computedHighTemp}", result[0].computedHighTemp < 71f)
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

    /**
     * Pre-dawn precip (00:00–08:00) must attribute to *that* day's night bucket. Pre-fix, the
     * night window was 20:00 → next-day 08:00, so observations between midnight and 08:00 fell
     * into a gap: counted toward total but neither day nor night. Option B closes the gap by
     * defining night as (00:00–08:00) ∪ (20:00–24:00) within calendar day D.
     */
    @Test
    fun `computeDailyExtremes attributes pre-dawn precip to same-day night bucket`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 5, 25)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val obs = listOf(
            // Pre-dawn (00:00–08:00) — should land in this date's night bucket.
            observationWithPrecip(dayStartMs + 3 * 3600_000, 55f, 2.0f),   // 3 AM
            observationWithPrecip(dayStartMs + 5 * 3600_000, 56f, 1.5f),   // 5 AM
            // Daytime (08:00–20:00) — day bucket.
            observationWithPrecip(dayStartMs + 14 * 3600_000, 70f, 1.0f),  // 2 PM
            // Late-evening (20:00–24:00) — still night bucket.
            observationWithPrecip(dayStartMs + 22 * 3600_000, 60f, 0.5f),  // 10 PM
        )

        val result = ObservationResolver.computeDailyExtremes(obs, emptyList(), 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(1.0f, result[0].precipDayMm!!, 0.01f)             // 2 PM only
        assertEquals(4.0f, result[0].precipNightMm!!, 0.01f)           // 2.0 + 1.5 + 0.5
        assertEquals(5.0f, result[0].precipAmountMm!!, 0.01f)          // 1.0 + 4.0 — total = day + night
    }

    /**
     * Same invariant for the forecast-fallback branch: NWS-style observations with null precip,
     * pre-dawn forecast rain must land in night, and night = pre-dawn ∪ late-evening should sum.
     * Forecast fallback only applies to the current (incomplete) day, so this uses today's date.
     */
    @Test
    fun `computeDailyExtremes forecast fallback today attributes pre-dawn rain to night bucket`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val obs = listOf(
            observationWithPrecip(dayStartMs + 3 * 3600_000, 55f, null),
            observationWithPrecip(dayStartMs + 14 * 3600_000, 70f, null),
            observationWithPrecip(dayStartMs + 22 * 3600_000, 60f, null),
        )
        val forecasts = listOf(
            hourly(dayStartMs + 3 * 3600_000, 2.5f),   // 3 AM — pre-dawn night
            hourly(dayStartMs + 14 * 3600_000, 1.0f),  // 2 PM — day
            hourly(dayStartMs + 22 * 3600_000, 0.5f),  // 10 PM — late-evening night
        )

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(1.0f, result[0].precipDayMm!!, 0.01f)              // 2 PM
        assertEquals(3.0f, result[0].precipNightMm!!, 0.01f)            // 2.5 (pre-dawn) + 0.5 (late)
        assertEquals(4.0f, result[0].precipAmountMm!!, 0.01f)           // 1.0 + 3.0 — day + night invariant
    }

    @Test
    fun `extremesToDailyActuals maps all precip fields`() {
        val extremes = listOf(
            DailyHistoryEntity(
                date = 1_700_000_000_000L,
                source = WeatherSource.NWS.id,
                locationLat = 37.42,
                locationLon = -122.08,
                computedHighTemp = 75f,
                computedLowTemp = 55f,
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
            DailyHistoryEntity(
                date = 1_700_000_000_000L,
                source = WeatherSource.NWS.id,
                locationLat = 37.42,
                locationLon = -122.08,
                computedHighTemp = 75f,
                computedLowTemp = 55f,
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

    private fun hourly(
        dateTime: Long,
        precipAmountMm: Float?,
        source: String = WeatherSource.NWS.id,
    ): com.weatherwidget.data.local.HourlyForecastEntity =
        com.weatherwidget.data.local.HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = 60f,
            condition = "Rain",
            source = source,
            precipAmountMm = precipAmountMm,
            fetchedAt = 0L,
        )

    // --- NWS hybrid: forecast-fallback precip (measured-preferred) ---

    @Test
    fun `computeDailyExtremes falls back to forecast precip for today when observations lack precip`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        // NWS observations carry temps but no precip (precipitationLastHour null).
        val obs = listOf(
            observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, null),
            observationWithPrecip(dayStartMs + 14 * 3600_000, 72f, null),
            observationWithPrecip(dayStartMs + 22 * 3600_000, 64f, null),
        )
        // NWS hourly forecast supplies the rain: 2.0 + 3.0 daytime, 1.5 nighttime.
        val forecasts = listOf(
            hourly(dayStartMs + 10 * 3600_000, 2.0f),
            hourly(dayStartMs + 14 * 3600_000, 3.0f),
            hourly(dayStartMs + 22 * 3600_000, 1.5f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(6.5f, result[0].precipAmountMm!!, 0.01f)  // 2.0 + 3.0 + 1.5
        assertEquals(5.0f, result[0].precipDayMm!!, 0.01f)     // 2.0 + 3.0
        assertEquals(1.5f, result[0].precipNightMm!!, 0.01f)   // 1.5
    }

    @Test
    fun `computeDailyExtremes prefers measured observation precip over forecast`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.of(2026, 5, 25)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        // Observations DO report precip — these win; forecast is ignored.
        val obs = listOf(
            observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, 4.0f),
            observationWithPrecip(dayStartMs + 14 * 3600_000, 72f, null),
        )
        val forecasts = listOf(hourly(dayStartMs + 10 * 3600_000, 99.0f))

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(4.0f, result[0].precipAmountMm!!, 0.01f)  // observation, not 99.0 forecast
    }

    @Test
    fun `computeDailyExtremes forecast fallback today ignores other sources' hourly precip`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val obs = listOf(observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, null))
        val forecasts = listOf(
            hourly(dayStartMs + 10 * 3600_000, 2.0f, source = WeatherSource.NWS.id),
            hourly(dayStartMs + 11 * 3600_000, 50.0f, source = WeatherSource.OPEN_METEO.id),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(2.0f, result[0].precipAmountMm!!, 0.01f)  // only the NWS forecast row counts
    }

    /**
     * Completed past days are measured-only: when no observation reports precip, the forecast
     * fallback is suppressed so history never shows a forecast value masquerading as a measured
     * actual. (This is what made NWS and Silurian disagree — NWS had no measured precip on a
     * past day and was showing its hourly *forecast* as the "actual".)
     */
    @Test
    fun `computeDailyExtremes past day with null observation precip is measured-only`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone).minusDays(2)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        // NWS observations carry temps but no measured precip (the real NWS no-rain pattern).
        val obs = listOf(
            observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, null),
            observationWithPrecip(dayStartMs + 22 * 3600_000, 60f, null),
        )
        // Forecast predicted a trace — must NOT surface as a past-day actual.
        val forecasts = listOf(
            hourly(dayStartMs + 10 * 3600_000, 2.0f),
            hourly(dayStartMs + 22 * 3600_000, 1.5f),
        )

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertNull(result[0].precipAmountMm)
        assertNull(result[0].precipDayMm)
        assertNull(result[0].precipNightMm)
    }

    /**
     * Past-day measured precip is still honored — the measured-only rule suppresses only the
     * forecast fallback, not real station measurements.
     */
    @Test
    fun `computeDailyExtremes past day still uses measured observation precip`() {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone).minusDays(2)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val obs = listOf(
            observationWithPrecip(dayStartMs + 10 * 3600_000, 70f, 4.0f),  // measured day rain
            observationWithPrecip(dayStartMs + 22 * 3600_000, 60f, 1.0f),  // measured night rain
        )
        val forecasts = listOf(hourly(dayStartMs + 10 * 3600_000, 99.0f))  // ignored

        val result = ObservationResolver.computeDailyExtremes(obs, forecasts, 37.42, -122.08)

        assertEquals(1, result.size)
        assertEquals(5.0f, result[0].precipAmountMm!!, 0.01f)  // 4.0 + 1.0 measured, not forecast
        assertEquals(4.0f, result[0].precipDayMm!!, 0.01f)
        assertEquals(1.0f, result[0].precipNightMm!!, 0.01f)
    }
}
