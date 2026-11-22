package com.weatherwidget.shared.util

import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The bug-report attachment on both platforms: `weather-widget-report-<stamp>.zip` holding
 * `summary.txt`, `logs.tsv`, widget images and an optional database copy.
 */
object BugReportZip {
    const val SUMMARY = "summary.txt"
    const val LOGS = "logs.tsv"
    const val DATABASE = "weather_database.db"

    fun fileName(now: ZonedDateTime): String =
        "weather-widget-report-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now)}.zip"

    fun widgetImageName(widgetId: Int): String = "widget-$widgetId.png"

    /** One zip entry: in-memory bytes or a file streamed from disk (the database copy). */
    sealed class Entry(val name: String) {
        class Bytes(name: String, val bytes: ByteArray) : Entry(name)
        class FromFile(name: String, val file: File) : Entry(name)
    }

    /** Writes [entries] in order; returns the entry names written. */
    fun write(out: OutputStream, entries: List<Entry>): List<String> {
        ZipOutputStream(out).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                when (entry) {
                    is Entry.Bytes -> zip.write(entry.bytes)
                    is Entry.FromFile -> entry.file.inputStream().use { it.copyTo(zip) }
                }
                zip.closeEntry()
            }
        }
        return entries.map { it.name }
    }
}
