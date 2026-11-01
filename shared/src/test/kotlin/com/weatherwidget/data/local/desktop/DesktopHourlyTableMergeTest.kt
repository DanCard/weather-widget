package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.graph.PriorDayCloudForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager

/** Schema v36, Room MIGRATION_83_84: plans/261101-one-hourly-forecast-table.md. */
@Category(ShortDuration::class)
class DesktopHourlyTableMergeTest {
    private val path = Files.createTempFile("weather_hourly_merge", ".db")
    private val hour = 1_792_900_800_000L

    @After
    fun delete() {
        Files.deleteIfExists(path)
    }

    private fun seedVersion35() = DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
        conn.createStatement().use { stmt ->
            val columns = "dateTime INTEGER NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL, temperature REAL NOT NULL, " +
                "condition TEXT NOT NULL, source TEXT NOT NULL, precipProbability INTEGER, cloudCover INTEGER, cloudCoverLow INTEGER, " +
                "cloudCoverMid INTEGER, cloudCoverHigh INTEGER, precipAmountMm REAL, fetchedAt INTEGER NOT NULL"
            stmt.execute("CREATE TABLE hourly_forecasts ($columns, PRIMARY KEY (dateTime, source, locationLat, locationLon))")
            stmt.execute(
                "CREATE TABLE hourly_forecast_history ($columns, timestampToGroupPredictions INTEGER NOT NULL, " +
                    "PRIMARY KEY (dateTime, source, locationLat, locationLon, timestampToGroupPredictions))",
            )
            val live = "INSERT INTO hourly_forecasts (dateTime, locationLat, locationLon, temperature, condition, source, fetchedAt) VALUES "
            val history = "INSERT INTO hourly_forecast_history (dateTime, locationLat, locationLon, temperature, condition, source, " +
                "timestampToGroupPredictions, fetchedAt) VALUES "
            stmt.execute("$live ($hour, 37.417, -122.089, 60.0, 'Cloudy', 'NWS', 200)")
            stmt.execute("$history ($hour, 37.417, -122.089, 60.0, 'Cloudy', 'NWS', 200, 200)")
            stmt.execute("$history ($hour, 37.417, -122.089, 55.0, 'Clear', 'NWS', 100, 100)")
            stmt.execute("$live (${hour + 3_600_000L}, 37.417, -122.089, 61.0, 'Rain', 'NWS', 300)")
            stmt.execute("PRAGMA user_version = 35")
        }
    }

    @Test
    fun `live rows join the snapshots, none lost, and a restart keeps one table`() {
        seedVersion35()
        val db = DesktopWeatherDatabase(path).apply { initialize() }
        DesktopWeatherDatabase(path).initialize() // the DDL runs on every launch

        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rows = mutableListOf<Pair<Long, Float>>()
                stmt.executeQuery("SELECT timestampToGroupPredictions, temperature FROM hourly_forecasts ORDER BY dateTime, fetchedAt").use {
                    while (it.next()) rows += it.getLong(1) to it.getFloat(2)
                }
                assertEquals(listOf(100L to 55f, 200L to 60f, 300L to 61f), rows)
                stmt.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'hourly_forecast_history'").use {
                    it.next()
                    assertEquals(0, it.getInt(1))
                }
                stmt.executeQuery("PRAGMA user_version").use {
                    it.next()
                    assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `an all-sources read leaves out the synthetic prior-day source`() {
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        val row = HourlyForecast(hour, 60f, "Clear", fetchedAt = hour - 1)
        dao.upsertHourlyForecasts(37.417, -122.089, "NWS", listOf(row))
        dao.upsertHourlyForecasts(37.417, -122.089, PriorDayCloudForecast.SOURCE_ID, listOf(row))

        assertEquals(listOf("NWS"), dao.getHourlyForecastsAllSources(37.417, -122.089, hour, hour).map { it.source })
    }

    @Test
    fun `the newest row per hour reads back and the older fetch stays stored`() {
        seedVersion35()
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })

        assertEquals(listOf(60f, 61f), dao.getHourlyForecasts(37.417, -122.089, "NWS", hour, hour + 3_600_000L).map { it.temperature })
        assertEquals(setOf(55f, 60f), dao.getHourlyHistorySnapshots(37.417, -122.089, "NWS", hour, hour + 1).map { it.temperature }.toSet())
    }
}
