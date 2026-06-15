package com.weatherwidget.data.local.desktop

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopClimateNormalsDaoTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao

    @Before
    fun setUp() {
        tempDbPath = Files.createTempFile("weather_climate_test", ".db")
        db = DesktopWeatherDatabase(tempDbPath)
        db.initialize()
        dao = DesktopWeatherDao(db)
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(tempDbPath)
    }

    private val key = "37.4_-122.1"
    private val monthlyHigh = (1..12).associateWith { (it * 5 + 40).toFloat() }
    private val monthlyLow = (1..12).associateWith { (it * 5 + 20).toFloat() }

    @Test
    fun `upsert then get round-trips the 12 monthly normals`() {
        dao.upsertClimateNormals(key, monthlyHigh, monthlyLow)
        val (high, low) = dao.getClimateNormals(key)
        assertEquals(12, high.size)
        for (month in 1..12) {
            assertEquals(monthlyHigh[month]!!, high[month]!!, 0.001f)
            assertEquals(monthlyLow[month]!!, low[month]!!, 0.001f)
        }
    }

    @Test
    fun `get returns empty for an uncached location`() {
        val (high, low) = dao.getClimateNormals("0.0_0.0")
        assertTrue(high.isEmpty())
        assertTrue(low.isEmpty())
    }

    @Test
    fun `upsert preserves fractional tenths`() {
        dao.upsertClimateNormals(key, mapOf(6 to 76.5f), mapOf(6 to 54.1f))
        val (high, low) = dao.getClimateNormals(key)
        assertEquals(76.5f, high[6]!!, 0.001f)
        assertEquals(54.1f, low[6]!!, 0.001f)
    }

    @Test
    fun `upsert for a new location evicts the previous one`() {
        dao.upsertClimateNormals(key, monthlyHigh, monthlyLow)
        dao.upsertClimateNormals("0.0_0.0", mapOf(1 to 30f), mapOf(1 to 20f))
        assertTrue("old location should be evicted", dao.getClimateNormals(key).first.isEmpty())
        assertEquals(1, dao.getClimateNormals("0.0_0.0").first.size)
    }
}
