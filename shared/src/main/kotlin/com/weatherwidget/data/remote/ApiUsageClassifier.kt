package com.weatherwidget.data.remote

import java.time.Instant
import java.time.ZoneId

/**
 * Which `api_usage_stats` row an HTTP request counts against: the source by host, the endpoint by
 * path. One rule for Android's `HttpSend` interceptor and desktop's `ResponseObserver`.
 *
 * Providers bill per endpoint (Google's quotas are `ForecastHoursQueriesPerDay`,
 * `ForecastDaysQueriesPerDay`, …), so a per-source total cannot be checked against a console.
 * The endpoint is the path with ids taken out, so rows stay few: a version segment (`v1`, `2.5`) is
 * dropped, a `:method` suffix is cut (`forecast/hours:lookup` → `forecast/hours`), and any segment
 * holding a digit — coordinates, grid points, station ids — becomes `{id}`. Coordinates must never
 * reach the table.
 *
 * The row's day is the provider's quota day ([usageDayMs]), so a day's count lines up with the
 * provider's console wherever the device is.
 */
object ApiUsageClassifier {
    data class Key(val source: String, val endpoint: String)

    private val VERSION_SEGMENT = Regex("""v?\d+(\.\d+)?""")
    private const val MAX_SEGMENTS = 4

    fun sourceForHost(host: String): String? = when {
        host.contains("silurian.ai") -> "SILURIAN"
        host.contains("tomorrow.io") -> "TOMORROW_IO"
        host.contains("weather.gov") -> "NWS"
        host.contains("open-meteo.com") -> "OPEN_METEO"
        host.contains("openweathermap.org") -> "OPEN_WEATHER_MAP"
        host.contains("weatherapi.com") -> "WEATHER_API"
        host == "weather.googleapis.com" -> "GOOGLE_WEATHER"
        host.contains("synopticdata.com") -> "SYNOPTIC"
        else -> null
    }

    fun endpointForPath(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() }
            .filterNot { VERSION_SEGMENT.matches(it) }
            .map { it.substringBefore(':') }
            .map { segment -> if (segment.any(Char::isDigit)) "{id}" else segment }
            .take(MAX_SEGMENTS)
            .joinToString("/")

    fun classify(host: String, path: String): Key? =
        sourceForHost(host)?.let { Key(it, endpointForPath(path)) }

    /** The zone whose midnight starts [source]'s quota day; null when the provider publishes none. */
    fun quotaZone(source: String): ZoneId? = if (source == "GOOGLE_WEATHER") GoogleQuota.ZONE else null

    /**
     * The `date` key for a request to [source] at [now]: that day's UTC-midnight epoch ms, the day
     * taken in the provider's quota zone (Pacific for Google), else in [localZone].
     */
    fun usageDayMs(source: String, now: Instant, localZone: ZoneId): Long =
        now.atZone(quotaZone(source) ?: localZone).toLocalDate().toEpochDay() * 86_400_000L

    /** HTTP 429: the provider refused for quota or rate. Counted apart from other errors. */
    fun isQuotaRefusal(status: Int): Boolean = status == 429

    fun isError(status: Int): Boolean = status >= 400
}
