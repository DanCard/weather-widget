package com.weatherwidget.widget

import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class ObservationWatermarkTest {

    private val base = 1_787_096_100_000L // 2026-08-18 16:35 PDT, the stale KNUQ reading

    private fun obs(
        stationId: String,
        timestamp: Long,
        api: String = WeatherSource.NWS.id,
        fetchedAt: Long = timestamp,
    ) = ObservationEntity(
        stationId = stationId,
        stationName = stationId,
        timestamp = timestamp,
        temperature = 71.6f,
        condition = "Clear",
        locationLat = 37.417,
        locationLon = -122.089,
        api = api,
        fetchedAt = fetchedAt,
    )

    @Test
    fun `empty list is NONE`() {
        assertEquals(ObservationWatermark.NONE, ObservationWatermark.of(emptyList(), WeatherSource.NWS.id))
    }

    @Test
    fun `single row is its own timestamp`() {
        val rows = listOf(obs("KNUQ", base))
        assertEquals(base, ObservationWatermark.of(rows, WeatherSource.NWS.id))
    }

    @Test
    fun `takes the newest across stations`() {
        val rows = listOf(
            obs("KNUQ", base),
            obs("AW020", base + 15 * 60_000L),
            obs("KPAO", base - 20 * 60_000L),
        )
        assertEquals(base + 15 * 60_000L, ObservationWatermark.of(rows, WeatherSource.NWS.id))
    }

    /**
     * One fetch cycle refreshes every source together. If the watermark ignored the display source,
     * an Open-Meteo row landing would rebuild the NWS bitmap for nothing — on every fetch.
     */
    @Test
    fun `ignores rows from other sources`() {
        val rows = listOf(
            obs("KNUQ", base, api = WeatherSource.NWS.id),
            obs("OPEN_METEO_MAIN", base + 60 * 60_000L, api = WeatherSource.OPEN_METEO.id),
        )
        assertEquals(base, ObservationWatermark.of(rows, WeatherSource.NWS.id))
    }

    /** GENERIC_GAP rows are drawn alongside the display source, so they count. Mirrors ObservationResolver. */
    @Test
    fun `includes generic gap rows`() {
        val rows = listOf(
            obs("KNUQ", base, api = WeatherSource.NWS.id),
            obs("GAP", base + 30 * 60_000L, api = WeatherSource.GENERIC_GAP.id),
        )
        assertEquals(base + 30 * 60_000L, ObservationWatermark.of(rows, WeatherSource.NWS.id))
    }

    @Test
    fun `no rows for the display source is NONE`() {
        val rows = listOf(obs("OPEN_METEO_MAIN", base, api = WeatherSource.OPEN_METEO.id))
        assertEquals(ObservationWatermark.NONE, ObservationWatermark.of(rows, WeatherSource.NWS.id))
    }

    /**
     * The load-bearing one. `fetchedAt` carries *attempt* semantics — `INSERT OR REPLACE` refreshes
     * it for a byte-identical repeat, and `touchLatestFetchedAt` bumps it on an empty attempt — so a
     * watermark keyed on it would advance every fetch cycle and restore blind periodic rebuilds
     * under a new name. If someone "simplifies" [ObservationWatermark] to use `fetchedAt`, this is
     * the test that catches it.
     */
    @Test
    fun `advancing fetchedAt alone does not move the watermark`() {
        val before = listOf(obs("KNUQ", base, fetchedAt = base + 60_000L))
        val afterRefetch = listOf(obs("KNUQ", base, fetchedAt = base + 20 * 60_000L))

        assertEquals(
            ObservationWatermark.of(before, WeatherSource.NWS.id),
            ObservationWatermark.of(afterRefetch, WeatherSource.NWS.id),
        )
    }
}
