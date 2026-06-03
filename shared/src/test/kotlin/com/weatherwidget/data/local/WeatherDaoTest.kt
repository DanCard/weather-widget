package com.weatherwidget.data.local

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.HourlyForecast
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WeatherDaoTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: WeatherDatabase
    private lateinit var dao: WeatherDao

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_test", ".db")
        db = WeatherDatabase(tempDbPath)
        db.initialize()
        dao = WeatherDao(db)
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
    fun `test observation round-trip`() {
        val lat = 40.0
        val lon = -75.0
        val now = System.currentTimeMillis()
        val obs = ObservationEntity(
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
    fun `test cleanup`() {
        val lat = 40.0
        val lon = -75.0
        val source = "NWS"
        val now = System.currentTimeMillis()
        
        // This is tricky because fetchedAt is set to now in the DAO.
        // I might need to wait or mock time if I really want to test cleanup based on fetchedAt.
        // For Tier 1, I'll trust the SQL DELETE.
        
        val daily = listOf(DailyForecast("2026-06-02", 80f, 60f, "Sunny"))
        dao.upsertForecasts(lat, lon, source, daily)
        
        // Assert it exists
        assertEquals(1, dao.getDailyForecasts(lat, lon, source).size)
        
        // Cleanup with a future timestamp (should delete everything)
        dao.cleanup(now + 10000)
        
        assertEquals(0, dao.getDailyForecasts(lat, lon, source).size)
    }
}
