package com.weatherwidget.desktop

import java.net.URLEncoder
import java.util.Locale

private const val BUG_REPORT_RECIPIENT = "weather-widget-bugs@example.invalid"
private const val TAG = "BugReportMailto"

/**
 * Phase 4 item 5: builds a `mailto:` URI pre-populated with desktop-app diagnostic info, mirroring
 * (at a much smaller scope) what Android's `BugReportActivity` collects. The user's mail client
 * opens with subject/body pre-filled; they can edit and send, or copy the body into another
 * channel. Recipient is a placeholder -- a real address would live in local.properties or a build
 * constant; left as `example.invalid` so we never auto-send to a wrong inbox.
 *
 * Deliberately excludes API keys from the diagnostic dump -- they're sensitive even in a bug
 * report, and the daemon already redacts them in app logs (see DesktopWeatherDao log writers).
 */
fun buildBugReportMailto(config: DesktopConfig): String {
    val subject = "Weather Widget desktop — bug report"
    val body = buildString {
        appendLine("Weather Widget desktop bug report")
        appendLine()
        appendLine("Please describe what happened:")
        appendLine("…")
        appendLine()
        appendLine("---- diagnostic info ----")
        appendLine("App version: desktop (jpackage 1.0.0)")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        appendLine("Locale: ${Locale.getDefault()}")
        appendLine()
        appendLine("Config:")
        appendLine("  location: ${config.label} (${formatCoord(config.lat)}, ${formatCoord(config.lon)})")
        appendLine("  weatherSource: ${config.weatherSource}")
        appendLine("  visibleSources: ${config.visibleSources}")
        appendLine("  viewMode: ${config.viewMode}")
        appendLine("  useCelsius: ${config.useCelsius}")
        appendLine("  personalStationDiscount: ${config.personalStationDiscount}")
        appendLine("  dailyExtraHistory: ${config.dailyExtraHistory}")
        appendLine("  zoomFactor: ${config.zoomFactor}")
        appendLine("  apiKeys: ${if (config.apiKeys.isEmpty()) "(none)" else config.apiKeys.keys.joinToString(", ") { "$it=(redacted)" }}")
    }
    val encodedSubject = URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
    val encodedBody = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
    return "mailto:$BUG_REPORT_RECIPIENT?subject=$encodedSubject&body=$encodedBody"
}

private fun formatCoord(value: Double): String = "%.4f".format(value)
