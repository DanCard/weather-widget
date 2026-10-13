package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The per-station history step both platforms run (Phase 4 of plans/261013-shared-current-conditions-fetch.md). */
@Category(ShortDuration::class)
class NwsStationHistoryFetcherTest {
    private val station = NwsApi.StationInfo("KNUQ", "Mountain View, Moffett Field", 37.4161, -122.0492, StationType.OFFICIAL)
    private val now = 1_787_700_000_000L
    private val logs = mutableListOf<String>()
    private var webAsked = 0

    private fun obs(minutesAgo: Long) = NwsApi.Observation(
        timestamp = java.time.Instant.ofEpochMilli(now - minutesAgo * 60_000L).toString(),
        temperatureCelsius = 20f,
        textDescription = "Clear",
    )

    private fun webRow(minutesAgo: Long) = ObservationReading(
        stationId = "KNUQ", stationName = "MOFFETT FLD", timestamp = now - minutesAgo * 60_000L,
        temperature = 70f, condition = "", locationLat = 37.417, locationLon = -122.089,
        distanceKm = 2.4f, api = "METAR", isMetar = true,
    )

    private fun fetcher(window: () -> List<NwsApi.Observation>) =
        NwsStationHistoryFetcher({ _, _, _ -> window() }, { tag, msg, _ -> logs += "$tag $msg" }) { now }

    private suspend fun NwsStationHistoryFetcher.fetchAt(index: Int, web: List<ObservationReading>?) =
        fetch(station, index, 37.417, -122.089, "s", "e", web = { webAsked++; web }, fallbackLogTag = "FB")

    @Test
    fun `fresh API history is kept and the web is never asked`() = runBlocking {
        val result = fetcher { listOf(obs(30), obs(10)) }.fetchAt(0, listOf(webRow(5)))
        assertEquals(2, result.rows.size)
        assertFalse(result.usedWebFallback)
        assertEquals(0, webAsked)
        assertTrue(result.apiOutcome is FetchOutcome.Success)
    }

    @Test
    fun `stale API history falls back to web rows stamped as the NWS station's`() = runBlocking {
        val result = fetcher { listOf(obs(600)) }.fetchAt(0, listOf(webRow(5)))
        assertTrue(result.usedWebFallback)
        val row = result.rows.single()
        assertEquals("NWS", row.api)
        assertTrue(row.isWebFallback)
        assertEquals(station.name, row.stationName)
        assertEquals(listOf("FB station=KNUQ reason=stale"), logs)
    }

    @Test
    fun `an empty web answer keeps the stale API rows rather than storing nothing`() = runBlocking {
        val result = fetcher { listOf(obs(600)) }.fetchAt(0, emptyList())
        assertFalse(result.usedWebFallback)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `a failed window says so, keeps its class name, and falls back`() = runBlocking {
        val result = fetcher { throw java.net.SocketTimeoutException("30 s") }.fetchAt(0, listOf(webRow(5)))
        assertEquals("SocketTimeoutException", result.apiFailure)
        assertTrue(result.apiOutcome is FetchOutcome.Failed)
        assertTrue(result.usedWebFallback)
        assertTrue(logs.contains("NWS_HISTORY_FETCH_FAIL station=KNUQ error=SocketTimeoutException:30 s"))
        assertTrue(logs.contains("FB station=KNUQ reason=fetch_failed:SocketTimeoutException"))
    }

    @Test
    fun `an answered empty window is NoData, not a failure`() = runBlocking {
        val result = fetcher { emptyList() }.fetchAt(ObservationFallbackPolicy.MAX_WEB_FALLBACK_STATIONS, null)
        assertTrue(result.apiOutcome is FetchOutcome.NoData)
        assertNull(result.apiFailure)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `stations past the fallback limit never ask the web`() = runBlocking {
        val result = fetcher { listOf(obs(600)) }.fetchAt(ObservationFallbackPolicy.MAX_WEB_FALLBACK_STATIONS, listOf(webRow(5)))
        assertFalse(result.usedWebFallback)
        assertEquals(0, webAsked)
    }
}
