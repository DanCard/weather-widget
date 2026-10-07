package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.SourceQuotaBlocks
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DataFreshnessTest {

    // NWS displayed: due after 4 h on a charger with the screen on; others after 8 h (ForecastCadence).
    private val chargingScreenOn = ForecastFetchContext(
        isCharging = true,
        isScreenInteractive = true,
        batteryLevel = 100,
        activeSourceIds = setOf(WeatherSource.NWS.id),
    )

    @org.junit.After
    fun clearBlocks() = SourceQuotaBlocks.reset()

    @Test
    fun `the displayed source at 70 minutes is not stale - the old rank threshold was 60`() {
        val now = System.currentTimeMillis()
        assertFalse(
            DataFreshness.isStaleForSources(
                listOf(WeatherSource.NWS),
                mapOf(WeatherSource.NWS.id to now - 70 * 60_000L),
                now,
                chargingScreenOn,
            ),
        )
    }

    @Test
    fun `a non-displayed source at 5 hours is not stale - it waits 8 hours`() {
        val now = System.currentTimeMillis()
        assertFalse(
            DataFreshness.isStaleForSources(
                listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
                mapOf(WeatherSource.NWS.id to now - 10 * 60_000L, WeatherSource.OPEN_METEO.id to now - 5 * 3_600_000L),
                now,
                chargingScreenOn,
            ),
        )
    }

    /**
     * 2026-10-07: Google stayed stale behind a daily-quota 429 and every refresh action forced a
     * fetch of all five sources. A refused source's age must not make the data "stale".
     */
    @Test
    fun `a quota-blocked source is not stale however old`() {
        val now = System.currentTimeMillis()
        val google = WeatherSource.GOOGLE_WEATHER
        SourceQuotaBlocks.block(google.id, now + 3_600_000L)
        val context = chargingScreenOn.copy(activeSourceIds = setOf(google.id))
        assertFalse(
            DataFreshness.isStaleForSources(
                listOf(google, WeatherSource.NWS),
                mapOf(google.id to now - 7 * 3_600_000L, WeatherSource.NWS.id to now - 10 * 60_000L),
                now,
                context,
            ),
        )
        SourceQuotaBlocks.reset()
        assertTrue(
            DataFreshness.isStaleForSources(
                listOf(google, WeatherSource.NWS),
                mapOf(google.id to now - 7 * 3_600_000L, WeatherSource.NWS.id to now - 10 * 60_000L),
                now,
                context,
            ),
        )
    }

    @Test
    fun `isStaleForSources returns true when NWS is primary and stale but Open-Meteo is fresh`() {
        val now = System.currentTimeMillis()
        val staleTime = now - (5 * 60 * 60 * 1000L)
        val freshTime = now - (10 * 60 * 1000L)
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)

        val batchFetchedAtBySource = mapOf(
            WeatherSource.NWS.id to staleTime,
            WeatherSource.OPEN_METEO.id to freshTime,
        )

        assertTrue(
            "Should be stale because NWS is stale",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, now, chargingScreenOn)
        )
    }

    @Test
    fun `isStaleForSources returns false when all visible sources are fresh`() {
        val now = System.currentTimeMillis()
        val freshTime = now - (10 * 60 * 1000L)
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)

        val batchFetchedAtBySource = mapOf(
            WeatherSource.NWS.id to freshTime,
            WeatherSource.OPEN_METEO.id to freshTime,
        )

        assertFalse(
            "Should not be stale when all sources are fresh",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, now, chargingScreenOn)
        )
    }

    @Test
    fun `isStaleForSources returns true when visible source has no data`() {
        val visibleSources = listOf(WeatherSource.NWS)

        val batchFetchedAtBySource = emptyMap<String, Long>()

        assertTrue(
            "Should be stale when source has no data",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, System.currentTimeMillis(), chargingScreenOn)
        )
    }

    @Test
    fun `isStaleForSources returns false when no sources are visible`() {
        val visibleSources = emptyList<WeatherSource>()
        val batchFetchedAtBySource = emptyMap<String, Long>()

        assertFalse(
            "Should not be stale when no sources are visible",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, System.currentTimeMillis(), chargingScreenOn)
        )
    }
}