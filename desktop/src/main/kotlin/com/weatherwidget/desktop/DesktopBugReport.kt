package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.BugReportLogs
import com.weatherwidget.shared.util.BugReportZip
import com.weatherwidget.shared.util.Log
import java.awt.Desktop
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.imageio.ImageIO

private const val TAG = "DesktopBugReport"

/**
 * Desktop "Submit Bug Report": the same zip as Android (`summary.txt`, `logs.tsv`, window images,
 * database copy) written to Downloads, then the mail draft and the folder are opened. `mailto:`
 * can't attach files, so the draft's first lines name the zip to attach.
 * See `plans/261121-bug-report-attachments.md`.
 */
object DesktopBugReport {
    fun downloadsDir(): File =
        File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

    /** Writes the zip into [dir] and returns it. Window images are skipped when headless. */
    fun writeZip(
        config: DesktopConfig,
        dao: DesktopWeatherDao,
        dir: File,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
        captureWindows: Boolean = !GraphicsEnvironment.isHeadless(),
    ): File {
        val zip = File(dir, BugReportZip.fileName(now))
        val nowMs = now.toInstant().toEpochMilli()
        val rows = dao.getBugReportLogs(
            recentSince = nowMs - BugReportLogs.RECENT_WINDOW_MS,
            keySince = nowMs - BugReportLogs.KEY_EVENT_WINDOW_MS,
            keyLevels = BugReportLogs.KEY_EVENT_LEVELS,
            keyTags = BugReportLogs.KEY_EVENT_TAGS,
        ).map { BugReportLogs.Row(it.timestamp, it.level, it.tag, it.message) }
        val logs = BugReportLogs.select(rows, nowMs)
        val taps = BugReportLogs.tapLines(logs, now.zone)
        val summary = bugReportBody(config, zip) + "\n--- RECENT TAPS ---\n" +
            (if (taps.isEmpty()) "none in the last 2 h\n" else taps.joinToString("\n", postfix = "\n"))

        val dbCopy = File(dir, ".${zip.nameWithoutExtension}.db.tmp")
        val dbOk = try {
            dao.copyDatabaseTo(dbCopy)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not copy database for bug report", e)
            false
        }
        val images = if (captureWindows) windowImages() else emptyList()
        val entries = buildList {
            add(BugReportZip.Entry.Bytes(BugReportZip.SUMMARY, summary.toByteArray()))
            add(BugReportZip.Entry.Bytes(BugReportZip.LOGS, BugReportLogs.tsv(logs, now.zone).toByteArray()))
            images.forEachIndexed { i, png -> add(BugReportZip.Entry.Bytes("window-${i + 1}.png", png)) }
            if (dbOk) add(BugReportZip.Entry.FromFile(BugReportZip.DATABASE, dbCopy))
        }
        try {
            zip.outputStream().use { BugReportZip.write(it, entries) }
        } finally {
            dbCopy.delete()
        }
        dao.log(
            BugReportLogs.BUILT_TAG,
            BugReportLogs.builtLine(zip.length(), logs, images.size, dbOk, entries.map { it.name }) + " path=${zip.path}",
            "INFO",
        )
        return zip
    }

    /** Builds the zip off the UI thread, then opens the mail draft and the Downloads folder. */
    fun buildAndOpenDraft(config: DesktopConfig, dao: DesktopWeatherDao) {
        Thread {
            val zip = try {
                writeZip(config, dao, downloadsDir())
            } catch (e: Exception) {
                Log.e(TAG, "Could not build bug report zip", e)
                null
            }
            openInBrowser(buildBugReportMailto(config, zip))
            zip?.parentFile?.let(::openFolder)
        }.apply { isDaemon = true; name = "bug-report" }.start()
    }

    /** Each showing app window, captured from the screen (what the user sees, including scaling). */
    private fun windowImages(): List<ByteArray> = try {
        val robot = Robot()
        Frame.getFrames().filter { it.isShowing && it.width > 0 && it.height > 0 }.map { frame ->
            val shot = robot.createScreenCapture(Rectangle(frame.locationOnScreen, frame.size))
            ByteArrayOutputStream().use { out ->
                ImageIO.write(shot, "png", out)
                out.toByteArray()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not capture app windows", e)
        emptyList()
    }

    private fun openFolder(dir: File) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir)
            } else {
                ProcessBuilder("xdg-open", dir.absolutePath).start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${dir.path}", e)
        }
    }
}
