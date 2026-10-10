package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.sourceview.SourceViewKind
import com.weatherwidget.shared.sourceview.SourceViewSwitch
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop half of "not viewed in 8 days → not fetched in the background": the daemon's other-source
 * loops (3c forecasts, 3d observations) take [DesktopSourceFetchGate.otherSources].
 * performance/261010-fetch-only-sources-likely-to-be-viewed.md
 */
@Category(ShortDuration::class)
class DesktopSourceFetchGateTest {
    private lateinit var tempDir: Path
    private lateinit var dbPath: Path
    private lateinit var dao: DesktopWeatherDao

    private val config = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = "NWS"),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-fetch-gate")
        dbPath = tempDir.resolve("weather.db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
        DesktopSourceFetchGate.resetLogMemoForTesting()
    }

    @After
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun today() = LocalDate.now(ZoneId.systemDefault())

    private fun track(daysAgo: Long) =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use {
                it.execute("UPDATE source_view_tracking SET startedDate = ${SourceViewTally.dayMs(today().minusDays(daysAgo))}")
            }
        }

    private fun switchTo(source: String, daysAgo: Long) = dao.recordSourceSwitch(
        SourceViewSwitch(SourceViewTally.dayMs(today().minusDays(daysAgo)), source, SourceViewKind.DAILY, SourceViewTrigger.TOGGLE, false),
    )

    @Test
    fun `fresh database is in grace - every other source fetched`() {
        assertEquals(listOf("OPEN_METEO", "SILURIAN"), DesktopSourceFetchGate.otherSources(dao, config))
    }

    @Test
    fun `7 days on, 8 days off - parity with Android's fixture`() {
        track(30)
        switchTo("OPEN_METEO", 7)
        switchTo("SILURIAN", 8)
        assertEquals(listOf("OPEN_METEO"), DesktopSourceFetchGate.otherSources(dao, config))
    }

    @Test
    fun `never viewed - no other source in the background, displayed is never in the list`() {
        track(30)
        assertEquals(emptyList<String>(), DesktopSourceFetchGate.otherSources(dao, config))
        assertEquals(
            listOf("NWS"),
            DesktopSourceFetchGate.otherSources(dao, config.copy(settings = config.settings.copy(weatherSource = "SILURIAN"))),
        )
    }

    @Test
    fun `a switch today brings a source back`() {
        track(30)
        switchTo("SILURIAN", 0)
        assertEquals(listOf("SILURIAN"), DesktopSourceFetchGate.otherSources(dao, config))
    }

    @Test
    fun `decision logged once while unchanged`() {
        track(30)
        repeat(3) { DesktopSourceFetchGate.otherSources(dao, config) }
        val line = dao.getLatestLogByTagAndMessagePrefix(DesktopSourceFetchGate.LOG_TAG, "")!!
        assertEquals("on=NWS off=OPEN_METEO,SILURIAN feeds=NWS", line.message)
        val count = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM app_logs WHERE tag = '${DesktopSourceFetchGate.LOG_TAG}'").use { it.next(); it.getInt(1) }
            }
        }
        assertEquals(1, count)
        // A change is logged again.
        switchTo("SILURIAN", 0)
        DesktopSourceFetchGate.otherSources(dao, config)
        assertEquals(
            "on=NWS,SILURIAN off=OPEN_METEO feeds=NWS",
            dao.getLatestLogByTagAndMessagePrefix(DesktopSourceFetchGate.LOG_TAG, "")!!.message,
        )
    }
}
