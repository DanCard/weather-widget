package com.weatherwidget.data.local.desktop

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
        val epoch = date.toEpochDay() * DailyExtremesComputer.MS_IN_A_DAY

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
            .toEpochDay() * DailyExtremesComputer.MS_IN_A_DAY
        dao.upsertDailyExtremes(
            listOf(
                DesktopDailyExtremeEntity(
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
