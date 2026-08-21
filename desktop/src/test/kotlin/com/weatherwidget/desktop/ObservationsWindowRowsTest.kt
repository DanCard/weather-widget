package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Covers [visibleStationRows], the transform behind the Observations window's station list.
 *
 * This was extracted from inside the `@Composable` precisely because it had no seam: the window
 * loaded once and never reloaded, and nothing could assert on what it displayed.
 */
@Category(ShortDuration::class)
class ObservationsWindowRowsTest {

    private fun obs(
        stationId: String,
        timestamp: Long,
        api: String = WeatherSource.NWS.id,
        temperature: Float = 60f,
        distanceKm: Float = 1f,
        stationType: String = "OFFICIAL",
    ) = DesktopObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = temperature,
        condition = "Clear",
        locationLat = 37.42,
        locationLon = -122.08,
        distanceKm = distanceKm,
        stationType = stationType,
        fetchedAt = timestamp,
        api = api,
    )

    @Test
    fun `keeps the newest reading per station`() {
        val rows = visibleStationRows(
            listOf(
                obs("KPAO", timestamp = 3000, temperature = 72f),
                obs("KPAO", timestamp = 2000, temperature = 65f),
                obs("KPAO", timestamp = 1000, temperature = 60f),
            ),
            WeatherSource.NWS,
        )

        assertEquals(1, rows.size)
        assertEquals(72f, rows.single().temperature, 0.001f)
    }

    /**
     * Regression guard for the order dependency: the original code took `first()` per group, which
     * is only "newest" while [com.weatherwidget.data.local.desktop.DesktopWeatherDao
     * .getRecentObservations] returns `ORDER BY timestamp DESC`. Fed ascending input, `first()`
     * would return the OLDEST reading — showing an up-to-24h-old temperature as current.
     */
    @Test
    fun `picks the newest reading even when input is ordered oldest-first`() {
        val rows = visibleStationRows(
            listOf(
                obs("KPAO", timestamp = 1000, temperature = 60f),
                obs("KPAO", timestamp = 2000, temperature = 65f),
                obs("KPAO", timestamp = 3000, temperature = 72f),
            ),
            WeatherSource.NWS,
        )

        assertEquals(72f, rows.single().temperature, 0.001f)
    }

    @Test
    fun `excludes synthetic blend and backfill rows under NWS`() {
        val rows = visibleStationRows(
            listOf(
                obs("NWS_BLEND", timestamp = 3000),
                obs("NWS_MAIN", timestamp = 3000),
                obs("KPAO", timestamp = 3000),
            ),
            WeatherSource.NWS,
        )

        assertEquals(listOf("KPAO"), rows.map { it.stationId })
    }

    @Test
    fun `excludes rows explicitly marked BLENDED`() {
        val rows = visibleStationRows(
            listOf(
                obs("KSJC", timestamp = 3000, stationType = "BLENDED"),
                obs("KPAO", timestamp = 3000),
            ),
            WeatherSource.NWS,
        )

        assertEquals(listOf("KPAO"), rows.map { it.stationId })
    }

    @Test
    fun `excludes rows from a source other than the selected one`() {
        val rows = visibleStationRows(
            listOf(
                obs("KPAO", timestamp = 3000),
                obs("OPEN_METEO_MAIN", timestamp = 3000, api = WeatherSource.OPEN_METEO.id),
            ),
            WeatherSource.NWS,
        )

        assertEquals(listOf("KPAO"), rows.map { it.stationId })
    }

    @Test
    fun `keeps approved provider history and hides forecast-only model rows`() {
        val rows = visibleStationRows(
            listOf(obs("WEATHER_API_MAIN", timestamp = 3000, api = WeatherSource.WEATHER_API.id)),
            WeatherSource.WEATHER_API,
        )

        assertEquals(listOf("WEATHER_API_MAIN"), rows.map { it.stationId })
        assertTrue(
            visibleStationRows(
                listOf(obs("OPEN_METEO_MAIN", timestamp = 3000, api = WeatherSource.OPEN_METEO.id)),
                WeatherSource.OPEN_METEO,
            ).isEmpty(),
        )
    }

    @Test
    fun `sorts nearest station first`() {
        val rows = visibleStationRows(
            listOf(
                obs("KSJC", timestamp = 3000, distanceKm = 20f),
                obs("KPAO", timestamp = 3000, distanceKm = 3f),
                obs("KNUQ", timestamp = 3000, distanceKm = 9f),
            ),
            WeatherSource.NWS,
        )

        assertEquals(listOf("KPAO", "KNUQ", "KSJC"), rows.map { it.stationId })
    }
}
