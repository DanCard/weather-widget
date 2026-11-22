package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.util.BugReportLogs
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.sql.DriverManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

/**
 * [DesktopBugReport.writeZip] against a real desktop SQLite database: the same zip layout and log
 * rule as Android (2 h of everything + 72 h of key events), and a database copy that opens.
 */
@Category(MediumDuration::class)
class DesktopBugReportTest {
    private val config = DesktopConfig(lat = 33.62, lon = -111.95, label = "Scottsdale", viewMode = ViewMode.DAILY)

    @Test
    fun zipHoldsSummaryLogsByRuleAndDatabaseCopy() {
        val dir = Files.createTempDirectory("bugreport-test")
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(dir.resolve("db/weather.db")).apply { initialize() })
        val now = ZonedDateTime.of(2026, 11, 21, 21, 10, 0, 0, ZoneId.of("America/Phoenix"))
        val nowMs = now.toInstant().toEpochMilli()
        val hour = 3_600_000L
        dao.log("SYNC_CANCELLED", "too old", "INFO", nowMs - 80 * hour)
        dao.log("SNAPSHOT_SKIP", "old routine", "DEBUG", nowMs - 30 * hour)
        dao.log("FETCH_METEO_FAIL", "old warn", "WARN", nowMs - 30 * hour)
        dao.log("CONFIG", "old config", "INFO", nowMs - 5 * hour)
        dao.log("SNAPSHOT_SKIP", "recent routine", "DEBUG", nowMs - hour)
        dao.log("CLICK_DAILY", "date=2026-11-21 targetView=CLOUD_COVER", "DEBUG", nowMs - 60_000)

        val zip = DesktopBugReport.writeZip(config, dao, dir.toFile(), now, captureWindows = false)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = z.readBytes()
            }
        }
        println("DesktopBugReportTest zip=${zip.name} entries=${entries.mapValues { it.value.size }}")

        assertEquals("weather-widget-report-20261121-211000.zip", zip.name)
        assertEquals(listOf("summary.txt", "logs.tsv", "weather_database.db"), entries.keys.toList())

        val summary = entries.getValue("summary.txt").decodeToString()
        assertTrue("summary names the zip to attach", summary.contains(zip.absolutePath))
        assertTrue("summary lists recent taps", summary.contains("CLICK_DAILY date=2026-11-21 targetView=CLOUD_COVER"))

        val tsv = entries.getValue("logs.tsv").decodeToString().trimEnd('\n').lines()
        println("DesktopBugReportTest logs.tsv:\n${tsv.joinToString("\n")}")
        assertEquals(BugReportLogs.TSV_HEADER, tsv.first())
        val messages = tsv.drop(1).map { it.split('\t')[5] }
        assertTrue(messages.containsAll(listOf("old warn", "old config", "recent routine")))
        assertTrue("old routine and >72 h rows dropped", messages.none { it == "old routine" || it == "too old" })

        val copy = dir.resolve("check.db").toFile().apply { writeBytes(entries.getValue("weather_database.db")) }
        DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}").use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM app_logs WHERE message = 'old config'").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
        assertTrue("temp db copy removed", dir.toFile().listFiles()!!.none { it.name.endsWith(".db.tmp") })
    }
}
