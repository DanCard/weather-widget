package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.toReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.fog.FogLevel
import com.weatherwidget.shared.fog.MorningFog
import com.weatherwidget.shared.fog.ObservedMorningFog
import com.weatherwidget.shared.observations.NwsGapFillRun
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The reconcile end to end on desktop: the real [AviationWeatherApi] parse on a Ktor [MockEngine]
 * serving KNUQ's real 2026-11-17 METARs, the shared [NwsGapFillRun.reconcileMetars], the repository's
 * gap-fill host and its observation write into real SQLite, then [ObservedMorningFog] on the stored NWS
 * rows. KNUQ (3.8 km) had NWS rows at 05:55 and 08:55 only, KSJC (15.9 km) stood in with its fog, and
 * the NWS Today column drew dense fog. plans/261117-nws-station-rows-reconciled-with-own-metars.md
 */
@Category(ShortDuration::class)
class DesktopMetarReconcileIntegrationTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val date = LocalDate.parse("2026-11-17")
    private val lat = 37.4166
    private val lon = -122.0889
    private val now = Instant.parse("2026-11-17T17:28:35Z").toEpochMilli()
    private val paths = mutableListOf<Path>()

    private val knuq = NwsApi.StationInfo("KNUQ", "Mountain View, Moffett Field", 37.4161, -122.0492, StationType.OFFICIAL)
    private val ksjc = NwsApi.StationInfo("KSJC", "San Jose International Airport", 37.3626, -121.9291, StationType.OFFICIAL)

    @After
    fun deleteDatabases() = paths.forEach { Files.deleteIfExists(it) }

    private fun local(hhmm: String) = date.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant().toEpochMilli()

    private fun nwsRow(station: String, hhmm: String, body: String, km: Float): DesktopObservationEntity {
        val at = local(hhmm)
        val z = DateTimeFormatter.ofPattern("ddHHmm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(at))
        return DesktopObservationEntity(
            stationId = station, stationName = station, timestamp = at, temperature = 43f, condition = "",
            locationLat = lat, locationLon = lon, distanceKm = km, stationType = StationType.OFFICIAL,
            fetchedAt = now, api = WeatherSource.NWS.id, isMetar = true, rawMetar = "$station ${z}Z 16004KT $body A3010",
        )
    }

    private fun observedFog(dao: DesktopWeatherDao): MorningFog? {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = dao.getObservationsInRange(start, start + 86_400_000L, lat, lon)
            .map { it.toReading() }
            .filter { it.api == WeatherSource.NWS.id }
        return ObservedMorningFog.forDate(rows, date, zone, now)
    }

    @Test
    fun `KNUQ's own METARs fill its NWS hole and the morning reads clear`() = runBlocking {
        val path = Files.createTempFile("weather_reconcile", ".db").also { paths.add(it) }
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        dao.upsertObservations(
            listOf(
                nwsRow("KNUQ", "05:55", "10SM CLR 06/06", 3.5f),
                nwsRow("KNUQ", "08:55", "10SM CLR 09/08", 3.5f),
            ) + listOf(
                "05:55" to "10SM CLR 06/05", "06:23" to "1/2SM FG SCT000 06/05", "06:35" to "1/4SM FG BKN000 06/05",
                "06:53" to "1/4SM FG BKN000 06/06", "07:05" to "M1/4SM FG VV001 06/06", "07:30" to "1/4SM FG 06/06",
                "07:50" to "1/4SM FG 07/07", "07:53" to "4SM BR FEW000 SCT002 07/07", "08:05" to "10SM FEW028 07/07",
                "08:55" to "10SM CLR 09/08",
            ).map { (t, body) -> nwsRow("KSJC", t, body, 15.9f) },
        )
        val before = observedFog(dao)
        println("before: $before")
        assertEquals(FogLevel.DENSE, before?.level)

        val fixture = checkNotNull(javaClass.getResourceAsStream("/aviationweather/KNUQ_261117.json")).bufferedReader().use { it.readText() }
        val requests = mutableListOf<String>()
        val awc = AviationWeatherApi(
            HttpClient(
                MockEngine { request ->
                    requests += request.url.toString()
                    respond(fixture, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            Json { ignoreUnknownKeys = true },
        )
        val service = mockk<WeatherApiClient>(relaxed = true)
        coEvery { service.nwsObservationStations() } returns FetchOutcome.Success(listOf(knuq, ksjc))
        coEvery { service.fetchMetarHistory(any(), any()) } coAnswers { awc.fetchMetars(firstArg(), secondArg()) }
        val repo = DesktopWeatherRepository(service, dao, lat, lon, "NWS", currentTimeMillis = { now })

        val added = NwsGapFillRun.reconcileMetars(repo.nwsGapFillHost(now), lat, lon, now, zone)

        val knuqTimes = dao.getObservationsInRange(local("05:00"), local("09:30"), lat, lon)
            .filter { it.api == WeatherSource.NWS.id && it.stationId == "KNUQ" }
            .map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalTime().toString() }
        val after = observedFog(dao)
        println("added=$added requests=$requests KNUQ NWS=$knuqTimes after: $after")
        assertEquals(8, added) // 06:15-08:35 every 20 min; 05:55 was already an NWS row
        assertTrue(requests.single().contains("ids=KNUQ%2CKSJC") || requests.single().contains("ids=KNUQ,KSJC"))
        assertEquals(listOf("05:55", "06:15", "06:35", "06:55", "07:15", "07:35", "07:55", "08:15", "08:35", "08:55"), knuqTimes)
        assertEquals(MorningFog.NONE, after)
    }

    @Test
    fun `two catch-ups in one boot send one METAR request, and a new boot sends another`() = runBlocking {
        // plans/261117-metar-reconcile-once-per-device-boot.md: the real repository, its gap-fill host, the
        // real marker file beside a real SQLite database, and the boot read from stand-in /proc files.
        val path = Files.createTempFile("weather_reconcile_boot", ".db").also { paths.add(it) }
        val marker = DesktopMetarReconcileBootStore.markerFor(path).also { paths.add(it) }
        val proc = Files.createTempDirectory("proc")
        val bootId = proc.resolve("boot_id").also { Files.writeString(it, "boot-a\n") }
        val uptime = proc.resolve("uptime").also { Files.writeString(it, "${3 * 3600}.00 0.00\n") }
        paths.addAll(listOf(bootId, uptime, proc))
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val fixture = checkNotNull(javaClass.getResourceAsStream("/aviationweather/KNUQ_261117.json")).bufferedReader().use { it.readText() }
        val requests = mutableListOf<String>()
        val awc = AviationWeatherApi(
            HttpClient(MockEngine { request -> requests += request.url.toString(); respond(fixture, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
            Json { ignoreUnknownKeys = true },
        )
        val service = mockk<WeatherApiClient>(relaxed = true)
        coEvery { service.nwsObservationStations() } returns FetchOutcome.Success(listOf(knuq, ksjc))
        coEvery { service.fetchMetarHistory(any(), any()) } coAnswers { awc.fetchMetars(firstArg(), secondArg()) }
        val bootStore = DesktopMetarReconcileBootStore({ marker }, bootId, uptime)
        fun repo() = DesktopWeatherRepository(service, dao, lat, lon, "NWS", currentTimeMillis = { now }, metarReconcileBootStore = bootStore)

        repo().refreshObservationWindow(listOf(WeatherSource.NWS), "launch:wake", now)
        repo().refreshObservationWindow(listOf(WeatherSource.NWS), "launch:wake", now + 600_000L)
        println("one boot: requests=${requests.size} marker=${Files.readAllLines(marker).filterNot { it.startsWith("#") }}")
        assertEquals(1, requests.size)
        assertTrue(Files.readString(marker).contains("=boot-a"))

        Files.writeString(bootId, "boot-b\n")
        repo().refreshObservationWindow(listOf(WeatherSource.NWS), "launch:startup", now + 1_200_000L)
        assertEquals(2, requests.size)
        assertTrue(Files.readString(marker).contains("=boot-b"))
    }
}
