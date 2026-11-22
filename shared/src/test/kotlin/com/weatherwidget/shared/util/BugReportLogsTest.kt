package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

@Category(ShortDuration::class)
class BugReportLogsTest {
    private val zone = ZoneId.of("America/Phoenix")
    private val now = ZonedDateTime.of(2026, 11, 21, 21, 10, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 60 * 60 * 1000L

    private fun row(hoursAgo: Double, tag: String, level: String = "DEBUG", msg: String = "m") =
        BugReportLogs.Row((now - hoursAgo * hour).toLong(), level, tag, msg)

    @Test
    fun recentWindowKeepsEverythingAndOlderKeepsOnlyKeyEvents() {
        val rows = listOf(
            row(0.1, "SNAPSHOT_SKIP"),
            row(1.9, "WIDGET_PAINT"),
            row(2.1, "SNAPSHOT_SKIP"),
            row(5.0, "SYNC_CANCELLED", "INFO"),
            row(10.0, "FETCH_METEO_FAIL", "WARN"),
            row(30.0, HeaderFit.LOG_TAG, "INFO"),
            row(71.0, "CONFIG"),
            row(73.0, "SYNC_CANCELLED", "INFO"),
            row(80.0, "ANYTHING", "ERROR"),
        )
        val selected = BugReportLogs.select(rows, now)
        println("BugReportLogsTest selected: ${selected.map { "${it.part.label}:${it.row.tag}" }}")

        assertEquals(
            listOf("CONFIG", HeaderFit.LOG_TAG, "FETCH_METEO_FAIL", "SYNC_CANCELLED", "WIDGET_PAINT", "SNAPSHOT_SKIP"),
            selected.map { it.row.tag },
        )
        assertEquals("oldest first", selected.map { it.row.epochMs }.sorted(), selected.map { it.row.epochMs })
        assertTrue(selected.takeLast(2).all { it.part == BugReportLogs.Part.RECENT })
        assertTrue(selected.dropLast(2).all { it.part == BugReportLogs.Part.KEY })
    }

    @Test
    fun keyEventInRecentWindowIsLabelledRecent() {
        val selected = BugReportLogs.select(listOf(row(0.5, "SYNC_CANCELLED", "WARN")), now)
        assertEquals(BugReportLogs.Part.RECENT, selected.single().part)
    }

    @Test
    fun tsvEscapesSeparatorsSoEveryRowIsOneLine() {
        val tricky = row(0.0, "T", msg = "a\tb\nc\r\\d ünï")
        val line = BugReportLogs.tsvLine(BugReportLogs.Selected(tricky, BugReportLogs.Part.RECENT), zone)
        println("BugReportLogsTest tsv line: $line")
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertEquals(6, line.split('\t').size)
        assertEquals("a\\tb\\nc\\r\\\\d ünï", line.split('\t')[5])
        assertEquals("2026-11-21 21:10:00.000", line.split('\t')[1])
        assertEquals("recent", line.split('\t')[2])
    }

    @Test
    fun tsvStartsWithHeader() {
        val tsv = BugReportLogs.tsv(emptyList(), zone)
        assertEquals(BugReportLogs.TSV_HEADER + "\n", tsv)
    }

    @Test
    fun tapLinesAreNewestLastAndFitMailWidth() {
        val rows = (1..15).map { row(it / 100.0, "INTERACTION_E2E", msg = "action=ACTION_SHOW_TOAST widget=12 " + "x".repeat(it * 8)) } +
            row(0.0, "SNAPSHOT_SKIP")
        val lines = BugReportLogs.tapLines(BugReportLogs.select(rows, now), zone)
        println("BugReportLogsTest tap lines:\n${lines.joinToString("\n")}")
        assertEquals(10, lines.size)
        assertTrue(lines.all { it.length <= 76 })
        assertTrue(lines.none { it.contains("SNAPSHOT_SKIP") })
        assertTrue("newest last", lines.last().startsWith("21:09:"))
    }

    @Test
    fun subjectCarriesShortDescriptionAndVersion() {
        assertEquals(
            "Weather widget bug report: back button doesn't work (26111801)",
            BugReportFormat.subject("back button doesn't work\nmore detail", "26111801"),
        )
        val long = BugReportFormat.subject("header buttons are missing in hourly graph after resizing", "1")
        assertTrue(long.startsWith("Weather widget bug report: header buttons are missing in hour"))
        assertTrue(long.endsWith("… (1)"))
        assertEquals("Weather widget bug report (1)", BugReportFormat.subject("  \n ", "1"))
    }

    @Test
    fun zipHoldsEntriesInOrder() {
        val out = ByteArrayOutputStream()
        val names = BugReportZip.write(
            out,
            listOf(
                BugReportZip.Entry.Bytes(BugReportZip.SUMMARY, "s".toByteArray()),
                BugReportZip.Entry.Bytes(BugReportZip.LOGS, "l".toByteArray()),
            ),
        )
        val read = mutableListOf<Pair<String, String>>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                read += e.name to zip.readBytes().decodeToString()
            }
        }
        assertEquals(listOf("summary.txt", "logs.tsv"), names)
        assertEquals(listOf("summary.txt" to "s", "logs.tsv" to "l"), read)
        assertEquals(
            "weather-widget-report-20261121-211000.zip",
            BugReportZip.fileName(ZonedDateTime.of(2026, 11, 21, 21, 10, 0, 0, zone)),
        )
    }

    @Test
    fun tapLinesSkipKeyEventTapsOlderThanTheRecentWindow() {
        val rows = listOf(
            row(30.0, "SETTINGS_REFRESH", "INFO", "partition fetch=NWS"),
            row(0.5, "CLICK_DAILY", msg = "date=2026-11-21"),
        )
        val selected = BugReportLogs.select(rows, now)
        assertEquals("the old refresh is still attached as a key event", 2, selected.size)
        val lines = BugReportLogs.tapLines(selected, zone)
        println("BugReportLogsTest recent-only taps: $lines")
        assertEquals(listOf("20:40:00 CLICK_DAILY date=2026-11-21"), lines)
    }
}
