package com.weatherwidget.data.repository

import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.shared.observations.MetarRawSkyParser
import com.weatherwidget.shared.observations.NwsObservationMapper
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsObservationSourceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var nwsApi: NwsApi
    private lateinit var source: NwsObservationSource

    @Before
    fun setup() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
        nwsApi = mockk()
        source = NwsObservationSource(
            context,
            nwsApi,
            mockk<AppLogDao>(relaxed = true),
            metarTextApi = null,
        )
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    /**
     * The emulator failure of 2026-08-21, end to end across [NwsObservationSource],
     * [ObservationFallbackPolicy], [MetarDecoder] and the NWS web row: KNUQ's 72h window timed out
     * after 30 s. The reason must name the failure (not "empty"), and the row that covers for it
     * keeps its sky condition.
     *
     * The covering row now comes from NWS's own METAR text file, never Synoptic (user, 2026-10-15;
     * plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
     */
    @Test
    fun `a timed-out window falls back to NWS's METAR text with cloud intact and says why`() = runTest {
        val station = NwsApi.StationInfo(
            id = "KNUQ",
            name = "Moffett Federal Airfield",
            lat = 37.4161,
            lon = -122.0492,
            type = StationType.OFFICIAL,
        )
        val logDao = mockk<AppLogDao>(relaxed = true)
        val logged = mutableListOf<AppLogEntity>()
        coEvery { logDao.insert(capture(logged)) } returns Unit

        val metarTextApi = mockk<NwsMetarTextApi>()
        coEvery { metarTextApi.fetchLatest("KNUQ") } returns NwsMetarTextApi.parse(
            "2026/08/21 14:55\nKNUQ 211455Z AUTO 01005KT 10SM BKN013 16/13 A3005 RMK AO2\n",
            "KNUQ",
        )

        val sourceWithWeb = NwsObservationSource(context, nwsApi, logDao, metarTextApi = metarTextApi)
        coEvery { nwsApi.getObservations(any(), any(), any()) } throws
            IOException("Request timeout has expired")

        val result = sourceWithWeb.fetchHistorical(
            stationInfo = station,
            stationIndex = 0,
            latitude = 37.417,
            longitude = -122.089,
            startTime = "2026-08-18T17:00:09Z",
            endTime = "2026-08-21T17:00:09Z",
            webWindowMinutes = 72 * 60L,
            fallbackLogTag = "OBS_HOURLY_WEB_FALLBACK",
        )

        // The failure is named, not laundered into "this station is empty".
        val fallbackLine = logged.single { it.tag == "OBS_HOURLY_WEB_FALLBACK" }.message
        assertTrue("reason must name the failure, was: $fallbackLine", "fetch_failed" in fallbackLine)
        assertFalse("a timeout is not an empty station", "reason=empty" in fallbackLine)

        // And the row that covered for it carries sky condition all the way to the entity, filed
        // as NWS's own (web origin), not as a METAR-provider row.
        val row = result.entities.single()
        assertTrue(result.usedWebFallback)
        assertEquals("IOException", result.apiFailure)
        assertEquals(75, row.cloudCoverLow)
        assertEquals("NWS", row.api)
        assertTrue(row.isWebFallback)
        assertTrue(row.isMetar)
        assertEquals(java.time.Instant.parse("2026-08-21T14:55:00Z").toEpochMilli(), row.timestamp)
    }

    @Test(expected = CancellationException::class)
    fun `grid point cancellation propagates`() = runTest {
        coEvery { nwsApi.getGridPoint(any(), any()) } throws CancellationException("worker stopped")

        source.stationsForLocation(37.42, -122.08)
    }

    @Test(expected = CancellationException::class)
    fun `station list cancellation propagates`() = runTest {
        coEvery { nwsApi.getObservationStations(any()) } throws CancellationException("worker stopped")

        source.stationsFromUrl("https://example.test/stations")
    }

    @Test(expected = CancellationException::class)
    fun `historical observation cancellation propagates`() = runTest {
        val station = NwsApi.StationInfo(
            id = "KPAO",
            name = "Palo Alto",
            lat = 37.46,
            lon = -122.12,
            type = StationType.OFFICIAL,
        )
        coEvery { nwsApi.getObservations(any(), any(), any()) } throws
            CancellationException("worker stopped")

        source.fetchHistorical(
            stationInfo = station,
            stationIndex = 0,
            latitude = 37.42,
            longitude = -122.08,
            startTime = "2026-07-29T00:00:00Z",
            endTime = "2026-07-30T00:00:00Z",
            webWindowMinutes = 24 * 60L,
            fallbackLogTag = "TEST_FALLBACK",
        )
    }

    // The hourly backfill retries on Failed and not on an empty answer (emulator-5554 2026-10-03:
    // a 30 s /points timeout became "no_stations" and sat out the 30-minute render cooldown).
    @Test
    fun `grid point transport failure is Failed, not an empty station list`() = runTest {
        coEvery { nwsApi.getGridPoint(any(), any()) } throws IOException("Request timeout has expired")

        val outcome = source.stationsForLocationOutcome(37.42, -122.08)

        assertTrue("got $outcome", outcome is FetchOutcome.Failed)
        assertEquals(emptyList<NwsApi.StationInfo>(), source.stationsForLocation(37.42, -122.08))
    }

    @Test
    fun `station list failure with no cache is Failed`() = runTest {
        coEvery { nwsApi.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR", gridX = 93, gridY = 87, forecastUrl = "f",
            observationStationsUrl = "https://example.test/stations",
        )
        coEvery { nwsApi.getObservationStations(any()) } throws IOException("offline")

        assertTrue(source.stationsForLocationOutcome(37.42, -122.08) is FetchOutcome.Failed)
    }

    @Test
    fun `answered empty station list is not Failed`() = runTest {
        coEvery { nwsApi.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR", gridX = 93, gridY = 87, forecastUrl = "f",
            observationStationsUrl = "https://example.test/stations",
        )
        coEvery { nwsApi.getObservationStations(any()) } returns emptyList()

        val outcome = source.stationsForLocationOutcome(37.42, -122.08)

        assertFalse("got $outcome", outcome is FetchOutcome.Failed)
        assertEquals(emptyList<NwsApi.StationInfo>(), outcome.valueOrNull().orEmpty())
    }

    @Test
    fun `expired cached stations remain usable when refresh fails`() = runTest {
        val url = "https://example.test/stations"
        val station = NwsApi.StationInfo(
            id = "KPAO",
            name = "Palo Alto",
            lat = 37.46,
            lon = -122.12,
            type = StationType.OFFICIAL,
        )
        SharedPreferencesUtil.getPrefs(context, "weather_prefs")
            .edit()
            .putString(
                "observation_stations_v4_${url.hashCode()}",
                NwsApi.encodeStationInfo(station),
            )
            .putLong("observation_stations_time_v4_${url.hashCode()}", 0L)
            .commit()
        coEvery { nwsApi.getObservationStations(url) } throws IOException("offline")

        assertEquals(listOf(station), source.stationsFromUrl(url))
    }

    // fetchLatest's two cases moved to :shared NwsStationLatestFetcherTest with the step itself
    // (plans/261013-shared-current-conditions-fetch.md Phase 2).
}
