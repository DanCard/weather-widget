package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.DailyActual
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.NwsApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopWeatherDaoTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `test hourly forecast round-trip`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val now = System.currentTimeMillis()
        val hourly = listOf(
            HourlyForecast(now, 72f, "Sunny"),
            HourlyForecast(now + 3600000, 75f, "Cloudy")
        )

        dao.upsertHourlyForecasts(lat, lon, source, hourly)
        
        val cached = dao.getLatestHourly(lat, lon, source, 10000)
        assertEquals(2, cached.size)
        assertEquals(72f, cached[0].temperature)
        assertEquals("Cloudy", cached[1].condition)
    }

    @Test
    fun `test daily forecast round-trip`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val daily = listOf(
            DailyForecast("2026-06-02", 80f, 60f, "Sunny"),
            DailyForecast("2026-06-03", 82f, 62f, "Partly Cloudy")
        )

        dao.upsertForecasts(lat, lon, source, daily)
        
        val cached = dao.getDailyForecasts(lat, lon, source)
        assertEquals(2, cached.size)
        assertEquals("2026-06-02", cached[0].date)
        assertEquals(82f, cached[1].highTemp)
    }

    @Test
    fun `test daily forecast snapshots exclude latest batch`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val date = java.time.LocalDate.parse("2026-06-02")
        val epoch = date.toEpochDay() * 86_400_000L

        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast("2026-06-02", 78f, 59f, "Cloudy")))
        Thread.sleep(5)
        dao.upsertForecasts(lat, lon, source, listOf(DailyForecast("2026-06-02", 81f, 61f, "Sunny")))

        val latest = dao.getDailyForecasts(lat, lon, source)
        val snapshots = dao.getDailyForecastSnapshots(epoch, epoch, lat, lon, source)

        assertEquals(81f, latest.single().highTemp)
        assertEquals(1, snapshots["2026-06-02"]?.size)
        assertEquals(78f, snapshots["2026-06-02"]?.single()?.highTemp)
    }

    @Test
    fun `getForecastEvolution returns every snapshot for the target date, filtered by location`() {
        val lat = 40.0
        val lon = -75.0
        val date = java.time.LocalDate.parse("2026-06-20")
        val epoch = date.toEpochDay() * 86_400_000L

        // Two NWS snapshots (distinct fetchedAt) plus one Open-Meteo snapshot for the target day.
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast("2026-06-20", 80f, 60f, "Sunny")))
        Thread.sleep(5)
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast("2026-06-20", 83f, 61f, "Sunny")))
        dao.upsertForecasts(lat, lon, "OPEN_METEO", listOf(DailyForecast("2026-06-20", 81f, 62f, "Cloudy")))

        // Noise that must be excluded: a different target day, and a far-away location.
        dao.upsertForecasts(lat, lon, "NWS", listOf(DailyForecast("2026-06-21", 70f, 50f, "Rain")))
        dao.upsertForecasts(10.0, 10.0, "NWS", listOf(DailyForecast("2026-06-20", 99f, 88f, "Hot")))

        val evolution = dao.getForecastEvolution(epoch, lat, lon)

        assertEquals(3, evolution.size)
        assertTrue(evolution.all { it.targetDate == epoch })
        assertTrue(evolution.none { it.highTemp == 70f || it.highTemp == 99f })
        assertEquals(setOf("NWS", "OPEN_METEO"), evolution.map { it.source }.toSet())
    }

    @Test
    fun `test observation round-trip`() {
        val lat = 40.0
        val lon = -75.0
        val now = System.currentTimeMillis()
        val obs = DesktopObservationEntity(
            stationId = "KPHL",
            stationName = "Philadelphia Intl",
            timestamp = now,
            temperature = 74f,
            condition = "Fair",
            locationLat = lat,
            locationLon = lon,
            api = "NWS"
        )

        dao.upsertObservations(listOf(obs))
        
        val cached = dao.getLatestObservation(lat, lon, 10000)
        assertNotNull(cached)
        assertEquals("KPHL", cached?.stationId)
        assertEquals(74f, cached?.temperature)
    }

    @Test
    fun `test app log round-trip and cleanup`() {
        dao.log("REFRESH", "obs=500 extremes=5")
        dao.log("REFRESH", "obs=0 extremes=0", level = "WARN")

        val recent = dao.getRecentLogs(10)
        assertEquals(2, recent.size)
        // Most recent first.
        assertEquals("obs=0 extremes=0", recent[0].message)
        assertEquals("WARN", recent[0].level)
        assertEquals("REFRESH", recent[1].tag)

        // app_logs is pruned by cleanup like the other tables.
        dao.cleanup(System.currentTimeMillis() + 10_000)
        assertEquals(0, dao.getRecentLogs(10).size)
    }

    @Test
    fun `test getRecentLogsByTags filters by tag and caps matching rows`() {
        // A flood of an unrelated verbose tag (mirrors CurrentTempResolver swamping app_logs)
        // interleaved with a few fetch rows.
        repeat(20) { dao.log("CurrentTempResolver", "noise $it") }
        dao.log("OBS_REFRESH", "obs=500 extremes=5")
        dao.log("REFRESH", "hourly=120 daily=7")
        dao.log("REFRESH_FAIL", "offline", level = "WARN")
        repeat(20) { dao.log("CurrentTempResolver", "more noise $it") }

        // All-tag read is dominated by noise — the fetch rows are buried (the original bug).
        assertEquals(0, dao.getRecentLogs(5).count { it.tag in setOf("OBS_REFRESH", "REFRESH", "REFRESH_FAIL") })

        // Tag-filtered read returns only the requested tags, regardless of noise volume.
        val fetches = dao.getRecentLogsByTags(listOf("OBS_REFRESH", "REFRESH", "REFRESH_FAIL"), 100)
        assertEquals(setOf("OBS_REFRESH", "REFRESH", "REFRESH_FAIL"), fetches.map { it.tag }.toSet())
        assertEquals(3, fetches.size)

        // The limit counts only matching rows, not the noise.
        assertEquals(2, dao.getRecentLogsByTags(listOf("CurrentTempResolver"), 2).size)

        // Empty tag list returns nothing.
        assertEquals(0, dao.getRecentLogsByTags(emptyList(), 100).size)
    }

    @Test
    fun `test station cache round-trip`() {
        val stations = listOf(
            NwsApi.StationInfo("KNUQ", "Moffett", 37.4, -122.0, NwsApi.StationType.OFFICIAL),
            NwsApi.StationInfo("AW020", "Personal", 37.3, -122.1, NwsApi.StationType.PERSONAL),
        )

        dao.upsertStationCache("stations_test", stations)

        val cached = dao.getCachedStations("stations_test", 10_000)
        assertEquals(listOf("KNUQ", "AW020"), cached?.map { it.id })
        assertEquals(NwsApi.StationType.OFFICIAL, cached?.first()?.type)
    }

    @Test
    fun `test daily actuals read from extremes`() {
        val lat = 40.0
        val lon = -75.0
        val date = java.time.LocalDate.parse("2026-06-02")
            .toEpochDay() * 86_400_000L
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = date,
                    source = "NWS",
                    locationLat = lat,
                    locationLon = lon,
                    highTemp = 81f,
                    lowTemp = 59f,
                    condition = "Fair",
                    updatedAt = System.currentTimeMillis(),
                )
            )
        )

        val actuals = dao.getDailyActuals(date, date, lat, lon, "NWS")
        assertEquals(81f, actuals["2026-06-02"]?.highTemp)
        assertEquals(59f, actuals["2026-06-02"]?.lowTemp)
    }

    @Test
    fun `test getLastSuccessfulFetch returns null when no logs`() {
        assertNull(dao.getLastSuccessfulFetch())
    }

    @Test
    fun `test getLastSuccessfulFetch returns latest REFRESH INFO timestamp`() {
        dao.log("REFRESH", "first", "INFO")
        Thread.sleep(10)
        dao.log("REFRESH_FAIL", "failure", "WARN")
        Thread.sleep(10)
        dao.log("REFRESH", "second", "INFO")

        val result = dao.getLastSuccessfulFetch()
        assertNotNull(result)
        // Should be the timestamp of the second REFRESH INFO, not the WARN REFRESH_FAIL
        val logs = dao.getRecentLogs(10)
        val secondRefresh = logs.reversed().first { it.tag == "REFRESH" && it.level == "INFO" && it.message == "second" }
        assertEquals(secondRefresh.timestamp, result)
    }

    @Test
    fun `desktop DAO location matching satisfies the shared LocationMatch contract`() {
        val source = "NWS"
        val hour = 1_780_682_400_000L
        for (case in com.weatherwidget.data.local.LocationMatchContract.CASES) {
            // Isolate each case so a prior row can't satisfy a later "should not match" query.
            dao.cleanup(System.currentTimeMillis() + 1_000_000_000L)
            dao.upsertHourlyForecasts(
                case.storedLat, case.storedLon, source,
                listOf(HourlyForecast(hour, 61f, "Cloudy", cloudCover = 80)),
            )
            val rows = dao.getLatestHourly(case.queryLat, case.queryLon, source, 10_000)
            assertEquals(
                "${case.name}: stored(${case.storedLat},${case.storedLon}) " +
                    "query(${case.queryLat},${case.queryLon})",
                case.shouldMatch,
                rows.isNotEmpty(),
            )
        }
    }

    @Test
    fun `getHourlyHistory keeps the freshest snapshot temp and coalesces precip from an older one`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val hour = 1_780_682_400_000L

        // Freshest snapshot = the latest forecast for this hour (matching the live line). Here it
        // carries temp/condition/cloud but dropped precip probability; an older snapshot supplies the
        // precip, which is coalesced in.
        dao.upsertHourlyForecastHistory(
            lat, lon, source, snapshotBucket = 1_780_500_000_000L,
            listOf(HourlyForecast(hour, 60f, "Cloudy", precipProbability = 20, cloudCover = null)),
        )
        dao.upsertHourlyForecastHistory(
            lat, lon, source, snapshotBucket = 1_780_675_200_000L,
            listOf(HourlyForecast(hour, 64f, "Sunny", precipProbability = null, cloudCover = 75)),
        )

        val history = dao.getHourlyHistory(lat, lon, source, hour - 1, hour + 1)

        assertEquals(1, history.size)
        // Temperature/condition/cloud come from the freshest snapshot (the latest forecast)...
        assertEquals(64f, history[0].temperature)
        assertEquals("Sunny", history[0].condition)
        assertEquals(75, history[0].cloudCover)
        // ...while the missing precip probability is coalesced from the older snapshot.
        assertEquals(20, history[0].precipProbability)
    }

    @Test
    fun `test cleanup`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val now = System.currentTimeMillis()
        
        val daily = listOf(DailyForecast("2026-06-02", 80f, 60f, "Sunny"))
        dao.upsertForecasts(lat, lon, source, daily)
        
        // Assert it exists
        assertEquals(1, dao.getDailyForecasts(lat, lon, source).size)
        
        // Cleanup with a future timestamp (should delete everything)
        dao.cleanup(now + 10000)
        
        assertEquals(0, dao.getDailyForecasts(lat, lon, source).size)
    }
}
