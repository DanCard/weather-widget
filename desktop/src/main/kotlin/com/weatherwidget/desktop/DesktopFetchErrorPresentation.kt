package com.weatherwidget.desktop

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
): DesktopFetchErrorPresentation {
    val statusCode = if (className == "ApiAccessException") {
        Regex("""\bstatus\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(detail)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    } else {
        null
    }
    val titleName = sourceDisplayName.uppercase()

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
