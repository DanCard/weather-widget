package com.weatherwidget.widget

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DataFreshnessTest {

    @Test
    fun `isStaleForSources returns true when NWS is primary and stale but Open-Meteo is fresh`() {
        val now = System.currentTimeMillis()
        val staleTime = now - (70 * 60 * 1000L)
        val freshTime = now - (10 * 60 * 1000L)
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)

        val batchFetchedAtBySource = mapOf(
            WeatherSource.NWS.id to staleTime,
            WeatherSource.OPEN_METEO.id to freshTime,
        )

        assertTrue(
            "Should be stale because NWS is stale",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, now)
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
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, now)
        )
    }

    @Test
    fun `isStaleForSources returns true when visible source has no data`() {
        val visibleSources = listOf(WeatherSource.NWS)

        val batchFetchedAtBySource = emptyMap<String, Long>()

        assertTrue(
            "Should be stale when source has no data",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, System.currentTimeMillis())
        )
    }

    @Test
    fun `isStaleForSources returns false when no sources are visible`() {
        val visibleSources = emptyList<WeatherSource>()
        val batchFetchedAtBySource = emptyMap<String, Long>()

        assertFalse(
            "Should not be stale when no sources are visible",
            DataFreshness.isStaleForSources(visibleSources, batchFetchedAtBySource, System.currentTimeMillis())
        )
    }
}