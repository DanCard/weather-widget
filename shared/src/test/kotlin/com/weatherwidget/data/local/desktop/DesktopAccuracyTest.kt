package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.stats.desktop.DesktopAccuracyCalculator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DesktopAccuracyTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    private val lat = 40.0
    private val lon = -75.0
    private val MS_IN_A_DAY = 86_400_000L

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_acc_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    // --- ActualsAggregator (Unified Logic) -------------------------------------

    @Test
    fun `computes daily high low and day-night precip from observations`() {
        val day = LocalDate.of(2026, 3, 15)
        fun atUtc(hour: Int) = day.atTime(hour, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

        val obs = listOf(
            obsAt(atUtc(3), temp = 50f, condition = "Sunny", precip = 0.5f),   // night
            obsAt(atUtc(9), temp = 60f, condition = "Cloudy", precip = 1.0f),  // day
            obsAt(atUtc(15), temp = 70f, condition = "Sunny", precip = 2.0f),  // day (warmest)
        )

        // Note: ActualsAggregator uses IDW blending. With only one station, it returns the station temp.
        val extremes = ActualsAggregator.aggregate(
            observations = obs.map { it.toReading() },
            hourlyForecasts = emptyList(),
            locationLat = lat,
            locationLon = lon,
            zoneId = ZoneOffset.UTC,
            updatedAtMs = 123L
        )

        assertEquals(1, extremes.size)
        val e = extremes.first()
        assertEquals(day.toEpochDay() * MS_IN_A_DAY, e.date)
        assertEquals("NWS", e.source)
        assertEquals(70f, e.highTemp)
        assertEquals(50f, e.lowTemp)
        assertEquals("Sunny", e.condition) // condition of the warmest reading
        assertEquals(3.5f, e.precipAmountMm!!, 0.001f)
        assertEquals(3.0f, e.precipDayMm!!, 0.001f)
        assertEquals(0.5f, e.precipNightMm!!, 0.001f)
        assertEquals(123L, e.updatedAt)
    }

    @Test
    fun `groups observations into separate days`() {
        val d1 = LocalDate.of(2026, 3, 15)
        val d2 = LocalDate.of(2026, 3, 16)
        val obs = listOf(
            obsAt(d1.atTime(12, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli(), 65f),
            obsAt(d2.atTime(12, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli(), 80f),
        )
        val extremes = ActualsAggregator.aggregate(
            observations = obs.map { it.toReading() },
            hourlyForecasts = emptyList(),
            locationLat = lat,
            locationLon = lon,
            zoneId = ZoneOffset.UTC
        ).sortedBy { it.date }
        
        assertEquals(2, extremes.size)
        assertEquals(65f, extremes[0].highTemp)
        assertEquals(80f, extremes[1].highTemp)
    }

    // --- Range queries + accuracy calculator -----------------------------------

    @Test
    fun `accuracy stats reflect seeded forecast-vs-actual pairs`() {
        val today = LocalDate.now()
        val pairs = listOf(
            Triple(today.minusDays(2), Pair(73f, 51f) /*actual h,l*/, Pair(70f, 50f) /*forecast h,l*/),
            Triple(today.minusDays(3), Pair(79f, 59f), Pair(80f, 60f)),
            Triple(today.minusDays(4), Pair(85f, 67f), Pair(85f, 65f)),
        )
        for ((target, actual, forecast) in pairs) {
            insertExtreme(target, actual.first, actual.second)
            insertForecast(target = target, forecastMade = target.minusDays(1), high = forecast.first, low = forecast.second)
        }

        val calc = DesktopAccuracyCalculator(dao)
        val breakdown = calc.getDailyAccuracyBreakdown("NWS", lat, lon, days = 30)
        assertEquals(3, breakdown.size)

        val stats = calc.calculateAccuracy("NWS", lat, lon, days = 30)!!
        assertEquals(3, stats.totalForecasts)
        assertEquals(1.333, stats.avgHighError, 0.01)
        assertEquals(1.333, stats.avgLowError, 0.01)
        assertEquals(0.667, stats.highBias, 0.01)
        assertEquals(0.667, stats.lowBias, 0.01)
        assertEquals(3, stats.maxError)
        assertEquals(100.0, stats.percentWithin3Degrees, 0.01)
        assertEquals(4.833, stats.accuracyScore, 0.01)
    }

    @Test
    fun `accuracy ignores days missing a matching 1-day-ahead forecast`() {
        val target = LocalDate.now().minusDays(2)
        insertExtreme(target, 75f, 55f)
        insertForecast(target = target, forecastMade = target, high = 70f, low = 50f)

        val calc = DesktopAccuracyCalculator(dao)
        assertEquals(0, calc.getDailyAccuracyBreakdown("NWS", lat, lon).size)
        assertNull(calc.calculateAccuracy("NWS", lat, lon))
    }

    @Test
    fun `getObservationsInRange filters by window and location`() {
        val now = System.currentTimeMillis()
        dao.upsertObservations(listOf(
            obsAt(now - 2 * MS_IN_A_DAY, 60f),
            obsAt(now - 10 * MS_IN_A_DAY, 55f), // outside window
        ))
        val inRange = dao.getObservationsInRange(now - 3 * MS_IN_A_DAY, now + MS_IN_A_DAY, lat, lon)
        assertEquals(1, inRange.size)
        assertEquals(60f, inRange[0].temperature)
    }

    // --- helpers ---------------------------------------------------------------

    private var stationSeq = 0
    private fun obsAt(timestamp: Long, temp: Float, condition: String = "Fair", precip: Float? = null) =
        DesktopObservationEntity(
            stationId = "KTST",
            stationName = "Test Station",
            timestamp = timestamp + (stationSeq++),
            temperature = temp,
            condition = condition,
            locationLat = lat,
            locationLon = lon,
            api = "NWS",
            precipAmountMm = precip,
        )

    private fun insertExtreme(target: LocalDate, high: Float, low: Float) {
        dao.upsertDailyHistory(listOf(
            DailyHistory(
                date = target.toEpochDay() * MS_IN_A_DAY,
                source = "NWS",
                locationLat = lat,
                locationLon = lon,
                highTemp = high,
                lowTemp = low,
                condition = "Sunny",
                updatedAt = System.currentTimeMillis(),
            )
        ))
    }

    private fun insertForecast(target: LocalDate, forecastMade: LocalDate, high: Float, low: Float) {
        db.getConnection().use { conn ->
            val sql = """
                INSERT OR REPLACE INTO forecasts
                (targetDate, forecastDate, locationLat, locationLon, locationName, highTemp, lowTemp, condition,
                 nativeDailyIconToken, isClimateNormal, source, precipProbability, daytimePrecipProbability,
                 nighttimePrecipProbability, periodStartTime, periodEndTime, precipAmountMm, batchFetchedAt, fetchedAt)
                VALUES (?, ?, ?, ?, '', ?, ?, 'Sunny', NULL, 0, 'NWS', NULL, NULL, NULL, NULL, NULL, NULL, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                val now = System.currentTimeMillis()
                stmt.setLong(1, target.toEpochDay() * MS_IN_A_DAY)
                stmt.setLong(2, forecastMade.toEpochDay() * MS_IN_A_DAY)
                stmt.setDouble(3, lat)
                stmt.setDouble(4, lon)
                stmt.setFloat(5, high)
                stmt.setFloat(6, low)
                stmt.setLong(7, now)
                stmt.setLong(8, now)
                stmt.executeUpdate()
            }
        }
    }
}
