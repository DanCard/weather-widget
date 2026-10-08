package com.weatherwidget.data.remote

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

    /** HTTP 429: the provider refused for quota or rate. Counted apart from other errors. */
    fun isQuotaRefusal(status: Int): Boolean = status == 429

    fun isError(status: Int): Boolean = status >= 400
}
