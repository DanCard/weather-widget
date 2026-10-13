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

/**
 * The one per-station latest-observation step both platforms run (plans/261013-shared-current-conditions-fetch.md
 * Phase 2). The first two cases are Android's `NwsObservationSourceTest.fetchLatest` cases, moved here.
 */
@Category(ShortDuration::class)
class NwsStationLatestFetcherTest {
    private val station = NwsApi.StationInfo(
        id = "KNUQ",
        name = "Mountain View, Moffett Field",
        lat = 37.4161,
        lon = -122.0492,
        type = StationType.OFFICIAL,
    )
    private val apiMs = 1_787_645_700_000L
    private val logs = mutableListOf<String>()

    private fun apiObservation(ms: Long = apiMs, rawMessage: String? = "KNUQ API", lowCloud: Boolean = false) =
        NwsApi.Observation(
            timestamp = java.time.Instant.ofEpochMilli(ms).toString(),
            temperatureCelsius = 17f,
            textDescription = "Cloudy",
            stationName = station.name,
            isMetar = rawMessage != null,
            rawMessage = rawMessage,
            cloudLayers = if (lowCloud) listOf(NwsApi.CloudLayer("OVC", 120.0)) else emptyList(),
        )

    private fun webRow(ms: Long, temp: Float = 62.6f, qcFailed: Boolean = false, stationId: String = "KNUQ") =
        ObservationReading(
            stationId = stationId,
            stationName = "MOFFETT FLD",
            timestamp = ms,
            temperature = temp,
            condition = "",
            locationLat = 37.417,
            locationLon = -122.089,
            distanceKm = 2.4f,
            stationType = StationType.UNKNOWN,
            api = "METAR",
            isMetar = true,
            rawMetar = "METAR KNUQ WEB",
            qcFailed = qcFailed,
        )

    private fun fetcher(api: FetchOutcome<NwsApi.Observation>) =
        NwsStationLatestFetcher({ api }, { tag, msg, _ -> logs += "$tag $msg" }) { apiMs + 90 * 60_000L }

    private fun web(outcome: FetchOutcome<List<ObservationReading>>) =
        NwsStationLatestFetcher.WebSource("aviation_weather") { _, _, _ -> outcome }

    @Test
    fun `newer parallel Aviation Weather METAR wins and is stamped as the NWS station's web row`() = runBlocking {
        val webMs = apiMs + 80 * 60_000L
        val result = fetcher(FetchOutcome.Success(apiObservation()))
            .fetch(station, 1, 37.417, -122.089, web(FetchOutcome.Success(listOf(webRow(webMs)))))

        val chosen = result.chosen!!
        assertEquals(webMs, chosen.timestamp)
        assertEquals("NWS", chosen.api)
        assertTrue(chosen.isWebFallback)
        assertEquals("METAR KNUQ WEB", chosen.rawMetar)
        assertEquals(station.name, chosen.stationName)
        assertEquals(StationType.OFFICIAL, chosen.stationType)
        assertEquals(62.6f, chosen.temperature)
        // Filed at the NWS station's distance, not the METAR feed's 2.4 km: one station, one IDW weight.
        assertEquals(NwsObservationMapper.distanceKm(37.417, -122.089, station.lat, station.lon).toFloat(), chosen.distanceKm)
        assertEquals(37.417, chosen.locationLat, 0.0)
        assertTrue(logs.single().startsWith("OBS_WEB_API_DELTA station=KNUQ index=1 tier=use transport=aviation_weather outcome=success"))
        assertTrue(logs.single(), logs.single().endsWith("deltaMin=80 apiTempF=62.6 webTempF=62.6 webQcFailed=false chosen=web"))
    }

    @Test
    fun `a failed web transport stays distinct and keeps the usable API row`() = runBlocking {
        val result = fetcher(FetchOutcome.Success(apiObservation()))
            .fetch(station, 1, 37.417, -122.089, web(FetchOutcome.Failed("HTTP 503")))

        assertEquals(apiMs, result.chosen?.timestamp)
        assertFalse(result.chosen!!.isWebFallback)
        assertEquals("HTTP 503", result.webFailureReason)
        assertFalse(result.shouldTouchFetchedAt)
    }

    @Test
    fun `a web win keeps the API row as cloud carrier when it reports low cloud`() = runBlocking {
        val result = fetcher(FetchOutcome.Success(apiObservation(lowCloud = true)))
            .fetch(station, 0, 37.417, -122.089, web(FetchOutcome.Success(listOf(webRow(apiMs + 30 * 60_000L)))))

        assertTrue(result.chosen!!.isWebFallback)
        val carrier = result.cloudCarrier!!
        assertEquals(apiMs, carrier.timestamp)
        assertFalse(carrier.isWebFallback)
        assertTrue(carrier.cloudCoverLow != null)
    }

    @Test
    fun `a QC-flagged newest web row never wins, but is still handed back`() = runBlocking {
        val result = fetcher(FetchOutcome.Success(apiObservation()))
            .fetch(station, 0, 37.417, -122.089, web(FetchOutcome.Success(listOf(webRow(apiMs + 30 * 60_000L, qcFailed = true)))))

        assertFalse(result.chosen!!.isWebFallback)
        assertEquals(1, result.webRows.size)
        assertTrue(result.webRows.single().qcFailed)
        assertTrue(logs.single().endsWith("webTempF=null webQcFailed=true chosen=api"))
    }

    @Test
    fun `metrics-tier stations log the web row but never take it`() = runBlocking {
        val metricsIndex = (0..10).first { !ObservationFallbackPolicy.shouldFetchWeb(it) && ObservationFallbackPolicy.shouldLogWebMetrics(it) }
        val result = fetcher(FetchOutcome.Success(apiObservation()))
            .fetch(station, metricsIndex, 37.417, -122.089, web(FetchOutcome.Success(listOf(webRow(apiMs + 30 * 60_000L)))))

        assertFalse(result.chosen!!.isWebFallback)
        assertTrue(logs.single().contains("tier=metrics"))
    }

    @Test
    fun `without a web source the API row is used and an API NoData is a touch`() = runBlocking {
        val noData = fetcher(FetchOutcome.NoData).fetch(station, 0, 37.417, -122.089, web = null)
        assertNull(noData.chosen)
        assertTrue(noData.shouldTouchFetchedAt)
        assertTrue(logs.isEmpty())

        val failed = fetcher(FetchOutcome.Failed("timeout")).fetch(station, 0, 37.417, -122.089, web = null)
        assertFalse(failed.shouldTouchFetchedAt)
        assertEquals("timeout", failed.nwsFailureReason)
    }

    @Test
    fun `batch rows - this station only, minus rows stamped too far ahead`() {
        val now = apiMs
        val batch = FetchOutcome.Success(
            listOf(
                webRow(now - 60_000),
                webRow(now - 60_000, stationId = "KSJC"),
                webRow(now + ObservationFallbackPolicy.MAX_WEB_FUTURE_SKEW_MS + 60_000),
            ),
        )
        val rows = NwsStationLatestFetcher.batchRows(batch, "KNUQ", now).valueOrNull()!!
        assertEquals(listOf(now - 60_000), rows.map { it.timestamp })
        assertTrue(NwsStationLatestFetcher.batchRows(batch, "KPAO", now) is FetchOutcome.NoData)
        assertEquals("down", (NwsStationLatestFetcher.batchRows(FetchOutcome.Failed("down"), "KNUQ", now) as FetchOutcome.Failed).reason)
    }
}
