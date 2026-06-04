package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopWeatherDaoTest {

    private lateinit var tempDbPath: Path
    private lateinit var database: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-test-dao", ".db")
        database = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(database)
    }

    @After
    fun teardown() {
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `getLastSuccessfulFetch returns null when no logs exist`() {
        assertNull(dao.getLastSuccessfulFetch())
        assertNull(dao.getLastSuccessfulFetch("NWS"))
    }

    @Test
    fun `getLastSuccessfulFetch retrieves latest refresh matching specific source`() {
        // Write a warning log (should be ignored)
        dao.log(tag = "REFRESH_FAIL", message = "source=NWS offline", level = "WARN")
        
        // Write a success NWS log
        val nwsTime = 1000L
        dao.log(tag = "REFRESH", message = "source=NWS hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        // We override timestamp for test correctness via SQL since DAO log uses System.currentTimeMillis()
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $nwsTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // Write a success Open-Meteo log later
        val omTime = 2000L
        dao.log(tag = "REFRESH", message = "source=OPEN_METEO hourly=156 daily=7 obs=1726 extremes=9", level = "INFO")
        database.getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE app_logs SET timestamp = $omTime WHERE id = (SELECT max(id) FROM app_logs)")
            }
        }

        // 1. Generic check should return the latest overall (2000L)
        assertEquals(2000L, dao.getLastSuccessfulFetch())

        // 2. Specific source checks
        assertEquals(1000L, dao.getLastSuccessfulFetch("NWS"))
        assertEquals(2000L, dao.getLastSuccessfulFetch("OPEN_METEO"))
        assertNull(dao.getLastSuccessfulFetch("SILURIAN"))
    }
}
