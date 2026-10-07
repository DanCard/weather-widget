package com.weatherwidget.desktop

import com.weatherwidget.data.remote.GoogleQuota
import com.weatherwidget.data.remote.ProviderErrorDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class DesktopFetchErrorPresentation(
    val title: String,
    val bodyLines: List<String>,
    val retryLine: String,
)

/** Converts persisted fetch-failure details into honest, user-facing desktop banner copy. */
internal fun desktopFetchErrorPresentation(
    sourceDisplayName: String,
    className: String,
    detail: String,
    nowMs: Long = System.currentTimeMillis(),
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

    if (statusCode == 429 && GoogleQuota.isDailyQuotaExhausted(statusCode, detail)) {
        val resetAt = DateTimeFormatter.ofPattern("h a").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(GoogleQuota.nextResetMs(nowMs)))
        val provider = ProviderErrorDetails.parse(detail)
        val quotaLine = provider?.quotaName?.let { name ->
            "Quota: $name" + (provider.quotaLimit?.let { " — $it per day, shared by every device using this key" } ?: "")
        }
        return DesktopFetchErrorPresentation(
            title = "$titleName DAILY QUOTA USED",
            bodyLines = listOfNotNull(
                "$sourceDisplayName's daily request quota for this API key is used up.",
                quotaLine,
                provider?.request?.let { "Request: $it" },
                "HTTP 429 — resets at $resetAt",
                "Cached $sourceDisplayName weather is still being displayed.",
                "No other weather provider was substituted.",
            ),
            retryLine = "Updates resume automatically after the reset.",
        )
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
 * The hourly view's banner when only the hourly forecast product is refused (Google's separate
 * `forecast/hours` daily quota) while the daily forecast keeps updating (user, 2026-10-07).
 */
internal fun desktopHourlyQuotaPresentation(
    sourceDisplayName: String,
    untilMs: Long,
    detail: String,
): DesktopFetchErrorPresentation {
    val resetAt = DateTimeFormatter.ofPattern("h a").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(untilMs))
    val provider = ProviderErrorDetails.parse(detail)
    return DesktopFetchErrorPresentation(
        title = "${sourceDisplayName.uppercase()} HOURLY FORECAST QUOTA USED",
        bodyLines = listOfNotNull(
            "$sourceDisplayName's hourly-forecast quota for this API key is used up.",
            provider?.quotaName?.let { name ->
                "Quota: $name" + (provider.quotaLimit?.let { " — $it per day, shared by every device using this key" } ?: "")
            },
            "HTTP 429 — resets at $resetAt",
            "The daily forecast is still updating; cached hourly data is shown.",
        ),
        retryLine = "Hourly updates resume automatically after the reset.",
    )
}

