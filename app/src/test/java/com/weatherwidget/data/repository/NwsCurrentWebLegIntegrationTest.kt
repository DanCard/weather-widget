package com.weatherwidget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.NwsMetarTextApi
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * The Android NWS web leg end to end: [NwsCurrentObservationUpdater] + [NwsObservationSource] +
 * shared NwsMetarTextWeb + the real [NwsMetarTextApi] over a mock HTTP engine + Room.
 *
 * KNUQ, 2026-10-15: api.weather.gov lagged at 11:55 while NWS's own METAR text file had 12:35. The
 * stored KNUQ row must be the text file's, filed as NWS's; stations without a METAR file keep their
 * API reading; and the NWS path asks no web host but NWS's (user: "NWS for NWS", no token —
 * plans/261015-nws-web-leg-from-nws-tgftp-metar-text.md).
 */
@Category(LongDuration::class)
class NwsCurrentWebLegIntegrationTest : RobolectricTest() {
    private lateinit var db: WeatherDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val lat = 37.4168
    private val lon = -122.0891

    private val stations = listOf(
        NwsApi.StationInfo("AW020", "AW020", 37.43, -122.08, StationType.PERSONAL),
        NwsApi.StationInfo("KNUQ", "Moffett", 37.41, -122.05, StationType.OFFICIAL),
        NwsApi.StationInfo("KPAO", "Palo Alto", 37.46, -122.12, StationType.OFFICIAL),
        NwsApi.StationInfo("LOAC1", "LOAC1", 37.36, -122.03, StationType.RAWS),
        NwsApi.StationInfo("KSJC", "San Jose", 37.36, -121.93, StationType.OFFICIAL),
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() = db.close()

    private val now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0)
    private fun minutesAgo(m: Long) = now.minusMinutes(m)
    private fun iso(t: ZonedDateTime) = DateTimeFormatter.ISO_INSTANT.format(t.toInstant())

    /** NWS's file for [id] reported [m] minutes ago at [tempC] (T-group tenths). */
    private fun metarFile(id: String, m: Long, tempC: Float): String {
        val t = minutesAgo(m)
        val tenths = String.format("%04d", Math.round(tempC * 10))
        return t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")) + "\n" +
            "$id ${t.format(DateTimeFormatter.ofPattern("ddHHmm"))}Z AUTO 00000KT 9SM CLR 23/03 A3011 RMK AO2 T0${tenths.drop(1)}0030\n"
    }

    @Test
    fun `KNUQ's newer NWS text report is stored as NWS's, others keep the API, no third-party host`() = runBlocking {
        val api = mockk<NwsApi>()
        coEvery { api.getGridPoint(any(), any()) } returns NwsApi.GridPointInfo(
            gridId = "MTR", gridX = 1, gridY = 1,
            forecastUrl = "https://example.invalid/forecast",
            observationStationsUrl = "https://example.invalid/stations",
        )
        coEvery { api.getObservationStations(any()) } returns stations
        // The API: every station 40 minutes behind.
        coEvery { api.getLatestObservationDetailedResult(any(), any()) } answers {
            FetchOutcome.Success(
                NwsApi.Observation(timestamp = iso(minutesAgo(45)), temperatureCelsius = 22f, textDescription = "Clear"),
            )
        }

        val files = mapOf(
            "KNUQ" to metarFile("KNUQ", 5, 23.0f),
            // KPAO's file is older than its API reading: the API keeps it.
            "KPAO" to metarFile("KPAO", 70, 21.0f),
        )
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val http = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            val id = request.url.encodedPath.substringAfterLast('/').removeSuffix(".TXT")
            val body = files[id]
            if (request.url.host == "tgftp.nws.noaa.gov" && body != null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }) { followRedirects = false }

        val dailyStore = DailyActualsStore(
            db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(),
            PersonalStationWeightProvider { 1.0 }, InMemoryReducedSignatureStore(),
        )
        val source = NwsObservationSource(context, api, db.appLogDao(), metarTextApi = null)
        val updater = NwsCurrentObservationUpdater(
            source, db.observationDao(), db.appLogDao(), dailyStore,
            NwsMetarTextApi(http), PersonalStationWeightProvider { 1.0 },
        )

        updater.fetchNwsCurrent(lat, lon)

        val rows = db.observationDao().getRecentObservations(0L).filter { it.api == WeatherSource.NWS.id }
        val knuq = rows.filter { it.stationId == "KNUQ" }.maxBy { it.timestamp }
        assertEquals(minutesAgo(5).toInstant().toEpochMilli(), knuq.timestamp)
        assertEquals(73.4f, knuq.temperature, 0.05f)
        assertTrue(knuq.isWebFallback)
        assertTrue(knuq.isMetar)

        val kpao = rows.filter { it.stationId == "KPAO" }.maxBy { it.timestamp }
        assertFalse("KPAO's API reading is newer than its file", kpao.isWebFallback)
        val loac1 = rows.single { it.stationId == "LOAC1" }
        assertFalse("no METAR file for a RAWS station", loac1.isWebFallback)

        assertTrue(requested.toString(), requested.all { it.startsWith(NwsMetarTextApi.BASE_URL + "/") })
        assertEquals(stations.map { it.id }.toSet(), requested.map { it.substringAfterLast('/').removeSuffix(".TXT") }.toSet())

        val deltas = db.appLogDao().getLogsByTag("OBS_WEB_API_DELTA", 20).map { it.message }
        assertTrue(deltas.toString(), deltas.any { "station=KNUQ" in it && "transport=nws_metar_text" in it && "chosen=web" in it })
        Unit
    }
}
