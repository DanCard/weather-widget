package com.weatherwidget.desktop

import com.weatherwidget.data.remote.QuotaNotice
import com.weatherwidget.data.remote.QuotaNoticeText

/** What the shared failure pill ([com.weatherwidget.shared.graph.FailureBannerLayout]) is worded from. */
internal data class DesktopFailurePill(
    val sourceLabel: String,
    val errorCode: String?,
    val failureTimeMs: Long,
)

internal data class DesktopFetchErrorPresentation(
    val title: String,
    val bodyLines: List<String>,
    val retryLine: String,
    /** A daily-quota refusal ([desktopQuotaPresentation]): the daily view shows these, and only these. */
    val quota: Boolean = false,
)

/** Converts persisted fetch-failure details into honest, user-facing desktop banner copy. */
internal fun desktopFetchErrorPresentation(
    sourceDisplayName: String,
    className: String,
    detail: String,
    /** When the failure was recorded: a quota resets at the midnight Pacific after it, not after now. */
    failureMs: Long,
): DesktopFetchErrorPresentation {
    val statusCode = if (className == "ApiAccessException" || className == "GoogleDailyQuotaException") {
        Regex("""\bstatus\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(detail)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    } else {
        null
    }
    val titleName = sourceDisplayName.uppercase()

    QuotaNotice.forSourceFailure(statusCode, failureMs, detail)?.let {
        return desktopQuotaPresentation(sourceDisplayName, it)
    }

    return when (statusCode) {
        429 -> DesktopFetchErrorPresentation(
            title = "$titleName REQUEST LIMIT REACHED",
            bodyLines = listOf(
                "$sourceDisplayName rejected this request because its API rate limit was reached.",
                "HTTP 429 — Too Many Requests",
                "Cached $sourceDisplayName weather is still being displayed.",
                "No other weather provider was substituted.",
            ),
            retryLine = "The next scheduled refresh will try again.",
        )

        401 -> DesktopFetchErrorPresentation(
            title = "$titleName AUTHORIZATION FAILED",
            bodyLines = listOf(
                "$sourceDisplayName rejected the configured API key.",
                "HTTP 401 — Unauthorized",
                "Cached $sourceDisplayName weather is still being displayed.",
                "No other weather provider was substituted.",
            ),
            retryLine = "Check the configured API key before retrying.",
        )

        403 -> DesktopFetchErrorPresentation(
            title = "$titleName ACCESS DENIED",
            bodyLines = listOf(
                "$sourceDisplayName denied access to this API resource.",
                "HTTP 403 — Forbidden",
                "Cached $sourceDisplayName weather is still being displayed.",
                "No other weather provider was substituted.",
            ),
            retryLine = "Check the API key and plan permissions before retrying.",
        )

        else -> {
            val host = when {
                detail.contains("open-meteo.com") -> "api.open-meteo.com"
                detail.contains("weather.gov") -> "api.weather.gov"
                detail.contains("tomorrow.io") -> "api.tomorrow.io"
                detail.contains("weatherapi.com") -> "api.weatherapi.com"
                detail.contains("visualcrossing.com") -> "weather.visualcrossing.com"
                detail.contains("openweathermap.org") -> "api.openweathermap.org"
                detail.contains("silurian", ignoreCase = true) -> "silurian API"
                else -> ""
            }
            val friendlyError = when (className) {
                "ConnectTimeoutException" -> "Connection timed out after 10 seconds."
                "SocketTimeoutException" -> "The weather service stopped responding."
                "UnknownHostException" -> "The weather service could not be found (DNS lookup failed)."
                else -> detail.substringBefore(" [").ifBlank { "The weather request failed." }
            }
            DesktopFetchErrorPresentation(
                title = "$titleName WEATHER UPDATE FAILED",
                bodyLines = listOfNotNull(
                    friendlyError,
                    host.takeIf { it.isNotEmpty() }?.let { "Service: $it" },
                    "Cached $sourceDisplayName weather is still being displayed.",
                    "No other weather provider was substituted.",
                ),
                retryLine = "The next scheduled refresh will try again.",
            )
        }
    }
}

/**
 * A daily-quota refusal, worded like the Android widget pill and error page (shared [QuotaNoticeText]):
 * "<SOURCE> UPDATES PAUSED", "<which> quota used · resets <time>", the quota's name and limit, and the
 * explanation. Covers the whole source and a single forecast product alike.
 */
internal fun desktopQuotaPresentation(
    sourceDisplayName: String,
    notice: QuotaNotice,
): DesktopFetchErrorPresentation {
    val resetTime = QuotaNoticeText.formatResetTime(notice.resetAtMs)
    return DesktopFetchErrorPresentation(
        title = QuotaNoticeText.headline(sourceDisplayName),
        bodyLines = listOf(QuotaNoticeText.summary(notice.scope, resetTime)) +
            QuotaNoticeText.detailLines(notice.provider),
        retryLine = QuotaNoticeText.explanation(resetTime),
        quota = true,
    )
}
