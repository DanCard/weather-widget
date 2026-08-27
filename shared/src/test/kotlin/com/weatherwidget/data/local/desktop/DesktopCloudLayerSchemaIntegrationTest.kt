package com.weatherwidget.data.local.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DesktopCloudLayerSchemaIntegrationTest {
    private lateinit var path: Path

    @Before
    fun setUp() {
        path = Files.createTempFile("desktop-cloud-layers", ".db")
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(path)
    }

    @Test
    fun `version 21 migrates and all four cloud fields round trip through live and history`() {
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE hourly_forecasts (
                        dateTime INTEGER NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL,
                        temperature REAL NOT NULL, condition TEXT NOT NULL, source TEXT NOT NULL,
                        precipProbability INTEGER, cloudCover INTEGER, cloudCoverLow INTEGER,
                        precipAmountMm REAL, fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (dateTime, source, locationLat, locationLon)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE hourly_forecast_history (
                        dateTime INTEGER NOT NULL, locationLat REAL NOT NULL, locationLon REAL NOT NULL,
                        temperature REAL NOT NULL, condition TEXT NOT NULL, source TEXT NOT NULL,
                        timestampToGroupPredictions INTEGER NOT NULL, precipProbability INTEGER,
                        cloudCover INTEGER, cloudCoverLow INTEGER, precipAmountMm REAL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY (dateTime, source, locationLat, locationLon, timestampToGroupPredictions)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "INSERT INTO hourly_forecasts VALUES " +
                        "(1000, 37.417, -122.089, 70.0, 'Cloudy', 'OPEN_METEO', 0, 100, 4, 0.0, 2000)",
                )
                stmt.execute("PRAGMA user_version = 21")
            }
        }

        val db = DesktopWeatherDatabase(path).apply { initialize() }
        db.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT cloudCover, cloudCoverLow, cloudCoverMid, cloudCoverHigh FROM hourly_forecasts WHERE dateTime=1000",
                ).use { rs ->
                    rs.next()
                    assertEquals(100, rs.getInt("cloudCover"))
                    assertEquals(4, rs.getInt("cloudCoverLow"))
                    assertNull(rs.getObject("cloudCoverMid"))
                    assertNull(rs.getObject("cloudCoverHigh"))
                }
            }
        }

        val dao = DesktopWeatherDao(db)
        val hour = System.currentTimeMillis() + 3_600_000L
        val row = HourlyForecast(
            dateTime = hour,
            temperature = 72f,
            condition = "Cloudy",
            cloudCover = 100,
            cloudCoverLow = 3,
            cloudCoverMid = 64,
            cloudCoverHigh = 98,
        )
        dao.upsertHourlyForecasts(37.417, -122.089, "OPEN_METEO", listOf(row))
        dao.upsertHourlyForecastHistory(37.417, -122.089, "OPEN_METEO", hour - 7_200_000L, listOf(row))

        val live = dao.getLatestHourly(37.417, -122.089, "OPEN_METEO", 10_000).single { it.dateTime == hour }
        val history = dao.getHourlyHistory(37.417, -122.089, "OPEN_METEO", hour, hour).single()
        assertEquals(listOf(100, 3, 64, 98), listOf(live.cloudCover, live.cloudCoverLow, live.cloudCoverMid, live.cloudCoverHigh))
        assertEquals(
            listOf(100, 3, 64, 98),
            listOf(history.cloudCover, history.cloudCoverLow, history.cloudCoverMid, history.cloudCoverHigh),
        )
    }
}
