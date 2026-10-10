package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.sourceview.SourceViewKind
import com.weatherwidget.shared.sourceview.SourceViewProbability
import com.weatherwidget.shared.sourceview.SourceViewSwitch
import com.weatherwidget.shared.sourceview.SourceViewTally
import com.weatherwidget.shared.sourceview.SourceViewTrigger
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.ViewMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desktop half of source_view_days (Room MIGRATION_76_77): schema v30 on fresh and upgraded
 * databases, the one API-button cycle that records, the home/observations triggers, retention, and
 * parity with the shared estimator. plans/261010-source-view-tracking-table.md
 */
@Category(ShortDuration::class)
class DesktopSourceViewsTest {
    private lateinit var tempDir: Path
    private lateinit var dbPath: Path
    private lateinit var dao: DesktopWeatherDao
    private val zone = ZoneId.systemDefault()

    // Mountain View: inside NWS coverage, so all three sources are usable.
    private val config = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Mountain View",
        settings = DesktopSettings(visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN"), weatherSource = "NWS"),
    )

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("desktop-source-views")
        dbPath = tempDir.resolve("weather.db")
        dao = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
        DesktopSourceViews.install(dao, executor = { it.run() })
    }

    @After
    fun teardown() {
        DesktopSourceViews.install(null)
        tempDir.toFile().deleteRecursively()
    }

    private fun today() = SourceViewTally.dayMs(System.currentTimeMillis(), zone)

    private fun rows() = dao.sourceViewDaysSince(0L).associateBy { Triple(it.sourceId, it.trigger, it.viewKind) }

    private fun userVersion(): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }

    @Test
    fun `fresh database is current with tracking started today`() {
        assertTrue(DesktopWeatherDatabase.SCHEMA_VERSION >= 30)
        assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, userVersion())
        assertEquals(today(), dao.sourceViewTrackingStartMs())
        assertTrue(dao.sourceViewDaysSince(0L).isEmpty())
    }

    @Test
    fun `schema 29 upgrade creates the tables and the tracking row`() {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE source_view_days")
                st.execute("DROP TABLE source_view_tracking")
                st.execute("PRAGMA user_version = 29")
            }
        }
        val upgraded = DesktopWeatherDao(DesktopWeatherDatabase(dbPath).apply { initialize() })
        assertEquals(DesktopWeatherDatabase.SCHEMA_VERSION, userVersion())
        assertEquals(today(), upgraded.sourceViewTrackingStartMs())
        // A second open keeps the first start date.
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { it.execute("UPDATE source_view_tracking SET startedDate = 1") }
        }
        DesktopWeatherDatabase(dbPath).initialize()
        assertEquals(1L, upgraded.sourceViewTrackingStartMs())
    }

    @Test
    fun `api button cycles the source and counts each switch`() {
        var current = config
        repeat(4) { cycleDisplaySource(current) { current = it } } // METEO, SILURIAN, NWS, METEO

        assertEquals("OPEN_METEO", current.settings.weatherSource)
        val r = rows()
        assertEquals(3, r.size)
        val meteo = r.getValue(Triple("OPEN_METEO", "TOGGLE", "DAILY"))
        assertEquals(2, meteo.switches)
        assertFalse(meteo.wasPrimary)
        assertTrue(r.getValue(Triple("NWS", "TOGGLE", "DAILY")).wasPrimary)
        assertTrue(r.values.all { it.dateMs == today() })
    }

    @Test
    fun `api button in an hourly view counts HOURLY`() {
        cycleDisplaySource(config.copy(viewMode = ViewMode.PRECIPITATION)) {}
        assertEquals(1, rows().getValue(Triple("OPEN_METEO", "TOGGLE", "HOURLY")).switches)
    }

    @Test
    fun `one usable source - no switch, no row`() {
        val single = config.copy(settings = config.settings.copy(visibleSources = listOf("NWS")))
        var updated = false
        cycleDisplaySource(single) { updated = true }
        assertFalse(updated)
        assertTrue(dao.sourceViewDaysSince(0L).isEmpty())
    }

    @Test
    fun `home and observations triggers`() {
        DesktopSourceViews.record("NWS", config.effectiveSources, ViewMode.DAILY, SourceViewTrigger.HOME)
        DesktopSourceViews.record("SILURIAN", config.effectiveSources, ViewMode.DAILY, SourceViewTrigger.OBSERVATIONS)
        val r = rows()
        assertTrue(r.getValue(Triple("NWS", "HOME", "DAILY")).wasPrimary)
        assertEquals(1, r.getValue(Triple("SILURIAN", "OBSERVATIONS", "HOURLY")).switches)
    }

    @Test
    fun `not installed - recording is a no-op`() {
        DesktopSourceViews.install(null)
        cycleDisplaySource(config) {}
        assertTrue(dao.sourceViewDaysSince(0L).isEmpty())
    }

    @Test
    fun `retention keeps exactly the 30 days the estimator reads`() {
        val now = System.currentTimeMillis()
        listOf(0L, 30L, 31L, 60L).forEach { age ->
            dao.recordSourceSwitch(
                SourceViewSwitch(today() - age * SourceViewTally.DAY_MS, "OPEN_METEO", SourceViewKind.DAILY, SourceViewTrigger.TOGGLE, false),
            )
        }
        dao.applyRetention(now)
        assertEquals(listOf(today() - 30 * SourceViewTally.DAY_MS, today()), dao.sourceViewDaysSince(0L).map { it.dateMs }.sorted())
    }

    @Test
    fun `desktop rows read back through the shared estimator - parity with Android`() {
        val day = LocalDate.of(2026, 10, 10)
        fun switch(daysAgo: Long) = SourceViewSwitch(
            SourceViewTally.dayMs(day.minusDays(daysAgo)), "OPEN_METEO", SourceViewKind.DAILY, SourceViewTrigger.TOGGLE, false,
        )
        dao.recordSourceSwitch(switch(3))
        dao.recordSourceSwitch(switch(3))
        dao.recordSourceSwitch(switch(45))
        val loaded = dao.sourceViewDaysSince(0L)
        assertEquals(2, loaded.size)
        assertEquals(2, loaded.single { it.date == day.minusDays(3) }.switches)
        val since = day.minusDays(60)
        // Same fixture and expectation as SourceViewRecordingRoboTest (Android).
        assertEquals(0.157, SourceViewProbability.toggleToday(loaded, since, day).probability, 0.001)
        assertEquals(0.157, SourceViewProbability.sourceViewedToday("OPEN_METEO", loaded, since, day, "NWS").probability, 0.001)
    }

    @Test
    fun `probability line is logged once a day`() {
        assertNull(dao.getLatestLogByTagAndMessagePrefix(DesktopSourceViews.PROBABILITY_TAG, ""))
        DesktopSourceViews.logDailyIfDue(config.effectiveSources)
        DesktopSourceViews.logDailyIfDue(config.effectiveSources)
        val logged = dao.getLatestLogByTagAndMessagePrefix(DesktopSourceViews.PROBABILITY_TAG, "")!!
        assertTrue(logged.message, logged.message.startsWith("toggle=0.50 "))
        assertTrue(logged.message, logged.message.endsWith("NWS=1.00 OPEN_METEO=0.50 SILURIAN=0.50"))
        val count = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM app_logs WHERE tag = '${DesktopSourceViews.PROBABILITY_TAG}'")
                    .use { it.next(); it.getInt(1) }
            }
        }
        assertEquals(1, count)
    }
}
