package com.weatherwidget.desktop

import com.weatherwidget.shared.util.BugReportFormat
import com.weatherwidget.shared.util.Log
import java.awt.GraphicsEnvironment
import java.net.URLEncoder
import java.util.Locale

/** Same inbox as Android's `BugReportActivity`, so reports from both platforms land together. */
internal const val BUG_REPORT_RECIPIENT = "daniecarde55@gmail.com"
private const val TAG = "BugReportMailto"

/**
 * Phase 4 item 5: builds a `mailto:` URI pre-populated with desktop-app diagnostic info, mirroring
 * (at a much smaller scope) what Android's `BugReportActivity` collects. The user's mail client
 * opens with subject/body pre-filled; they can edit and send, or copy the body into another
 * channel. Nothing is sent automatically: the user still reviews and sends from their mail client.
 *
 * Deliberately excludes API keys from the diagnostic dump -- they're sensitive even in a bug
 * report, and the daemon already redacts them in app logs (see DesktopWeatherDao log writers).
 */
fun buildBugReportMailto(config: DesktopConfig, attachment: java.io.File? = null): String {
    val subject = BugReportFormat.subject("", "desktop")
    val body = bugReportBody(config, attachment)
    val encodedSubject = URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
    val encodedBody = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
    return "mailto:$BUG_REPORT_RECIPIENT?subject=$encodedSubject&body=$encodedBody"
}

/**
 * The mail body and `summary.txt`. With [attachment], the first lines ask the user to attach the
 * zip: `mailto:` can't carry files, so the zip is written to Downloads ([DesktopBugReport]).
 */
internal fun bugReportBody(config: DesktopConfig, attachment: java.io.File? = null): String =
    buildString {
        appendLine("Weather Widget desktop bug report")
        appendLine()
        if (attachment != null) {
            appendLine("Please attach this file before sending:")
            appendLine(attachment.absolutePath)
            appendLine()
        }
        appendLine("Please describe what happened:")
        appendLine("…")
        appendLine()
        appendLine("---- diagnostic info ----")
        appendLine("App version: desktop (jpackage 1.0.0)")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        appendLine("Locale: ${Locale.getDefault()}")
        appendLine(BugReportFormat.timezoneLine())
        appendLine(displayLine())
        appendLine()
        appendLine("Config:")
        appendLine("  location: ${config.label} (${formatCoord(config.lat)}, ${formatCoord(config.lon)})")
        appendLine("  weatherSource: ${config.settings.weatherSource}")
        appendLine("  visibleSources: ${config.settings.visibleSources}")
        appendLine("  viewMode: ${config.viewMode}")
        appendLine("  useCelsius: ${config.settings.useCelsius}")
        appendLine("  personalStationDiscount: ${config.settings.personalStationDiscount}")
        appendLine("  headerRainLookaheadHours: ${config.settings.headerRainLookaheadHours}")
        appendLine("  hindcastMaxPriorAgeHours: ${config.settings.hindcastMaxPriorAgeHours}")
        appendLine("  dailyExtraHistory: ${config.dailyExtraHistory}")
        appendLine("  monthsView: ${config.monthsView} span=${config.monthsSpan} end=${config.monthsEnd}")
        appendLine("  zoomFactor: ${config.zoomFactor}")
        appendLine("  apiKeys: ${if (config.settings.apiKeys.isEmpty()) "(none)" else config.settings.apiKeys.keys.joinToString(", ") { "$it=(redacted)" }}")
    }

/**
 * Each screen's size and HiDPI scale, e.g. `Display: 2560x1440 @1.25x`. Rendering is sized in
 * scaled pixels, so the scale is what a reproduction must match. Headless hosts report `headless`.
 */
internal fun displayLine(): String =
    try {
        if (GraphicsEnvironment.isHeadless()) {
            "Display: headless"
        } else {
            val screens = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.joinToString(", ") { device ->
                val config = device.defaultConfiguration
                val mode = device.displayMode
                val scale = config.defaultTransform.scaleX
                "${mode.width}x${mode.height} @${String.format(Locale.US, "%.2f", scale)}x"
            }
            "Display: $screens"
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read display info", e)
        "Display: unavailable (${e.javaClass.simpleName})"
    }

private fun formatCoord(value: Double): String = com.weatherwidget.shared.util.formatCoord(value)
