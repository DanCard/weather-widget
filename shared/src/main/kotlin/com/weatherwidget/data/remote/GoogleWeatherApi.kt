package com.weatherwidget.data.remote

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.ForecastProduct
import com.weatherwidget.data.model.QuotaRefusal
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

private const val TAG = "GoogleWeatherApi"

/**
 * Google Maps Platform Weather API client (`weather.googleapis.com/v1`).
 *
 * Limits probed 2026-10-06: `forecast/days` serves at most 10 days, `forecast/hours` returns 24 hours
 * per page (follow `nextPageToken`), `history/hours` reaches back at most 24 h. Every request is
 * billed, so one full fetch is current + days + 1–3 hour pages ([GoogleHourPaging]) + history.
 *
 * The key goes in the `X-Goog-Api-Key` header, never the URL, so it cannot ride along in an
 * exception message into `app_logs` or a bug report.
 *
 * Forecast-only (see [WeatherSource.GOOGLE_WEATHER]): history rows fill the elapsed part of today's
 * hourly curve as forecast context and are never re-filed as observations.
 */
class GoogleWeatherApi(
    private val httpClient: HttpClient,
    private val json: Json,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Called once per billed request with a `GOOGLE_REQUEST` message, for the caller's persisted log
     * (this module has no DB). Every quota in Cloud Console counts requests per endpoint per project,
     * so these rows, summed across devices since midnight PT, are what the console should show.
     */
    private val onRequest: suspend (message: String) -> Unit = {},
    private val apiKeyProvider: () -> String?,
) {
    companion object {
        private const val BASE_URL = "https://weather.googleapis.com/v1"

        /**
         * Hourly horizon. Daily rows (10 days) carry their own day/night precip and condition, so
         * hours past 72 cost a request per 24 h without changing what is drawn.
         */
        const val FORECAST_HOURS = 72
        const val FORECAST_DAYS = 10
        const val HISTORY_HOURS = 24
        private const val PAGE_SIZE = 24

        /** Guards a misbehaving token chain; 72 h needs 3 pages. */
        private const val MAX_HOUR_PAGES = (FORECAST_HOURS + PAGE_SIZE - 1) / PAGE_SIZE

        private const val INCHES_TO_MM = 25.4f

        /** The elapsed part of the last 24 h — what one `history/hours` call can fill. */
        fun historyWindow(nowMs: Long): LongRange = (nowMs - HISTORY_HOURS * 3_600_000L) until (nowMs - 3_600_000L)

        /**
         * True when [coveredHours] (this source's `hourly_forecast_history` hours at the site) holds
         * none of [historyWindow]: a site fetched for the first time today. Every later fetch's
         * forecast hours become history as they elapse, so a site seen before never needs it.
         */
        fun needsHistory(coveredHours: Collection<Long>, nowMs: Long): Boolean {
            val window = historyWindow(nowMs)
            return coveredHours.none { it in window }
        }

        internal fun nextQuotaResetMs(nowMs: Long): Long = GoogleQuota.nextResetMs(nowMs)
    }

    /**
     * After a history 429, skip history until the quota resets (in this process). Google's quotas
     * reset at midnight Pacific ([GoogleQuota]). `history/hours` allows only a small
     * `HistoryHoursQueriesPerDay` per *project* — default 10, raised to 20 in Cloud Console on
     * 2026-10-06; every device, build and probe sharing the key draws on the same pool, and the value
     * is the project's setting, not ours, so nothing here depends on it. It is called only when it
     * adds something ([needsHistory]) and not again that day after a 429.
     */
    @Volatile
    private var historyBlockedUntilMs = 0L

    /**
     * Per-product daily-quota blocks: after a *daily*-quota 429 on `forecast/hours`
     * (`ForecastHoursQueriesPerDay`) or `forecast/days` (`ForecastDaysQueriesPerDay`), that product is
     * not requested again until the quota resets at midnight PT (in this process). Each has its own
     * quota, so the other keeps being fetched (user, 2026-10-07: the old single block stopped the
     * daily forecast and put "quota used" on the daily view when only the hourly quota was spent).
     * A per-minute 429 does not block. [getCurrent] has its own quota and is untouched.
     */
    private data class ProductBlock(val untilMs: Long, val detail: String)

    private val productBlocks = java.util.concurrent.ConcurrentHashMap<ForecastProduct, ProductBlock>()

    private fun activeBlock(product: ForecastProduct, nowMs: Long): ProductBlock? =
        productBlocks[product]?.takeIf { nowMs < it.untilMs }

    /**
     * Runs one product's request. A daily-quota 429 blocks that product until the reset and returns
     * null (the rest of the forecast proceeds); any other failure propagates as before.
     */
    private suspend fun <T> fetchProduct(product: ForecastProduct, request: suspend () -> T): T? =
        try {
            request()
        } catch (e: ApiAccessException) {
            if (!GoogleQuota.isDailyQuotaExhausted(e)) throw e
            val until = GoogleQuota.nextResetMs(nowMs())
            productBlocks[product] = ProductBlock(until, e.detail)
            SourceQuotaBlocks.block(WeatherSource.GOOGLE_WEATHER.id, product, until)
            Log.w(TAG, "$product daily quota exhausted; that product skipped until $until")
            null
        }

    /**
     * The last [getForecast]'s `forecast/hours` paging, e.g. `pages=1 reason=unchanged …`, for the
     * caller's persisted log (this module has no DB). Null before the first fetch.
     */
    @Volatile
    var lastHoursPaging: String? = null
        private set

    /**
     * [includeHistory]: the caller's [needsHistory] answer. History is best-effort either way — its
     * failure (a 429 above all) drops only the elapsed hours, never the forecast.
     *
     * [storedHours]: this source's stored hourly rows at the site. When given, page 1 is compared
     * with them and pages 2–3 are fetched only on change ([GoogleHourPaging]); null fetches all.
     *
     * [includeHours]: false skips `forecast/hours` and `history/hours` (an hourly-limited fetch,
     * [HourlyFetchGate]); daily and current conditions are fetched as usual, and the result has no
     * hourly rows, so the stored hours stand.
     */
    suspend fun getForecast(
        lat: Double,
        lon: Double,
        includeHistory: Boolean = true,
        storedHours: List<HourlyForecast>? = null,
        includeHours: Boolean = true,
    ): RawFetch = coroutineScope {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("GOOGLE_WEATHER_API_KEY is missing.")
        }
        val startMs = nowMs()
        val hoursBlock = activeBlock(ForecastProduct.HOURLY, startMs)
        val daysBlock = activeBlock(ForecastProduct.DAILY, startMs)
        if (hoursBlock != null && daysBlock != null) {
            Log.d(TAG, "forecast skipped: hourly and daily quotas exhausted")
            throw GoogleDailyQuotaException(minOf(hoursBlock.untilMs, daysBlock.untilMs), hoursBlock.detail)
        }

        // Current conditions ride along for the header; their own quota refusing must not cost the
        // forecast, so a daily-quota 429 there just leaves the current fields empty.
        val currentDeferred = async {
            try {
                fetchJson(apiKey, "/currentConditions:lookup", lat, lon)
            } catch (e: ApiAccessException) {
                if (!GoogleQuota.isDailyQuotaExhausted(e)) throw e
                null
            }
        }
        val daysDeferred = async {
            if (daysBlock != null) {
                null
            } else {
                fetchProduct(ForecastProduct.DAILY) {
                    fetchJson(apiKey, "/forecast/days:lookup", lat, lon) {
                        parameter("days", FORECAST_DAYS)
                        parameter("pageSize", FORECAST_DAYS)
                    }
                }
            }
        }
        val hoursDeferred = async {
            when {
                !includeHours -> null
                hoursBlock != null -> null
                else -> fetchProduct(ForecastProduct.HOURLY) { fetchForecastHours(apiKey, lat, lon, storedHours) }
            }
        }
        val requestHistory = includeHours && includeHistory && nowMs() >= historyBlockedUntilMs
        val historyDeferred = if (requestHistory) async { fetchHistoryOrNull(apiKey, lat, lon) } else null

        val current = currentDeferred.await()
        val days = daysDeferred.await()
        val forecastHours = hoursDeferred.await()
        val history = historyDeferred?.await()
        if (!requestHistory) {
            Log.d(TAG, "history skipped included=$includeHistory blockedUntil=$historyBlockedUntilMs")
        }

        val refused = ForecastProduct.entries
            .mapNotNull { product -> activeBlock(product, nowMs())?.let { product to it } }
            .toMap()
        if (!includeHours) {
            lastHoursPaging = "pages=0 reason=hourly_limited"
            if (days == null) {
                val block = refused[ForecastProduct.DAILY] ?: throw IllegalStateException("Google daily forecast missing")
                throw GoogleDailyQuotaException(block.untilMs, block.detail)
            }
        } else if (days == null && forecastHours == null) {
            // Both refused (one now, one earlier, or both now): nothing to return.
            val first = refused.values.minBy { it.untilMs }
            throw GoogleDailyQuotaException(first.untilMs, first.detail)
        }
        if (includeHours && forecastHours == null) lastHoursPaging = "pages=0 reason=quota_blocked"

        parse(
            current,
            days,
            forecastHours.orEmpty(),
            // Elapsed hours only make sense beside a forecast-hours payload.
            if (forecastHours == null) emptyList() else history?.get("historyHours")?.jsonArray.orEmpty(),
        ).copy(quotaRefused = refused.mapValues { QuotaRefusal(it.value.untilMs, it.value.detail) })
    }

    private suspend fun fetchHistoryOrNull(apiKey: String, lat: Double, lon: Double): JsonObject? =
        try {
            fetchJson(apiKey, "/history/hours:lookup", lat, lon) {
                parameter("hours", HISTORY_HOURS)
                parameter("pageSize", HISTORY_HOURS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiAccessException && e.statusCode == 429) {
                historyBlockedUntilMs = nextQuotaResetMs(nowMs())
            }
            Log.w(TAG, "history failed; forecast kept without elapsed hours: ${ApiKeyRedaction.redact(e.message.orEmpty())}")
            null
        }

    /**
     * Current conditions only — ONE billed request. The current-temperature refresh runs every
     * 10–20 minutes while charging, so it must never go through [getForecast] (6 requests), nor
     * the forecast-backed POI helper other sources use (5 points × a full fetch).
     */
    suspend fun getCurrent(lat: Double, lon: Double): RawFetch {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("GOOGLE_WEATHER_API_KEY is missing.")
        }
        return parseCurrent(fetchJson(apiKey, "/currentConditions:lookup", lat, lon))
    }

    private suspend fun fetchForecastHours(
        apiKey: String,
        lat: Double,
        lon: Double,
        storedHours: List<HourlyForecast>?,
    ): List<JsonObject> {
        val hours = mutableListOf<JsonObject>()
        val startMs = nowMs()
        var pagingReason = if (storedHours == null) "no_stored_hours_given" else "single_page"
        var pageToken: String? = null
        var pages = 0
        do {
            val page = fetchJson(apiKey, "/forecast/hours:lookup", lat, lon) {
                parameter("hours", FORECAST_HOURS)
                parameter("pageSize", PAGE_SIZE)
                pageToken?.let { parameter("pageToken", it) }
            }
            pages++
            page["forecastHours"]?.jsonArray?.mapTo(hours) { it.jsonObject }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (pages == 1 && pageToken != null && storedHours != null) {
                val decision = GoogleHourPaging.decide(
                    page1 = hours.mapNotNull(::parseHour),
                    stored = storedHours,
                    nowMs = startMs,
                    horizonEndMs = startMs + FORECAST_HOURS * 3_600_000L,
                )
                pagingReason = decision.reason
                if (!decision.fetchRest) break
            }
        } while (pageToken != null && pages < MAX_HOUR_PAGES)
        lastHoursPaging = "pages=$pages reason=$pagingReason"
        Log.i(TAG, "forecast/hours $lastHoursPaging")
        return hours
    }

    internal fun parse(
        current: JsonObject?,
        days: JsonObject?,
        forecastHours: List<JsonObject>,
        historyHours: List<kotlinx.serialization.json.JsonElement>,
    ): RawFetch {
        val forecast = forecastHours.mapNotNull(::parseHour)
        val firstForecastMs = forecast.minOfOrNull { it.dateTime } ?: Long.MAX_VALUE
        // History is newest-first and overlaps nothing in practice; keep only hours before the
        // forecast starts so a boundary hour is never stored twice.
        val elapsed = historyHours
            .mapNotNull { parseHour(it.jsonObject) }
            .filter { it.dateTime < firstForecastMs }
        val hourly = (elapsed + forecast).distinctBy { it.dateTime }.sortedBy { it.dateTime }

        val daily = days?.get("forecastDays")?.jsonArray.orEmpty().mapNotNull { parseDay(it.jsonObject) }

        Log.d(
            TAG,
            "parsed daily=${daily.size} hourly=${hourly.size} elapsed=${elapsed.size} " +
                "currentTemp=${current?.degrees("temperature")}",
        )
        return (current?.let(::parseCurrent) ?: RawFetch()).copy(daily = daily, hourly = hourly)
    }

    private fun parseCurrent(current: JsonObject): RawFetch =
        RawFetch(
            providerCurrentTemp = current.degrees("temperature"),
            providerCurrentCondition = current.condition()?.let(WeatherCodeMapper::googleConditionToCondition),
            providerCurrentObservedAt = current.string("currentTime")?.let(::parseInstantMs),
            providerCurrentCloudCover = current.int("cloudCover"),
        )

    private fun parseHour(obj: JsonObject): HourlyForecast? {
        val start = obj["interval"]?.jsonObject?.string("startTime") ?: return null
        val epochMs = parseInstantMs(start) ?: return null
        val temperature = obj.degrees("temperature") ?: return null
        return HourlyForecast(
            dateTime = epochMs,
            temperature = temperature,
            condition = WeatherCodeMapper.googleConditionToCondition(obj.condition()),
            precipProbability = obj.precipPercent(),
            precipAmountMm = obj.precipMm(),
            cloudCover = obj.int("cloudCover"),
            source = WeatherSource.GOOGLE_WEATHER.id,
        )
    }

    private fun parseDay(obj: JsonObject): DailyForecast? {
        val date = obj["displayDate"]?.jsonObject?.let { d ->
            val y = d.int("year") ?: return@let null
            val m = d.int("month") ?: return@let null
            val day = d.int("day") ?: return@let null
            LocalDate.of(y, m, day).toString()
        } ?: return null
        val daytime = obj["daytimeForecast"]?.jsonObject
        val nighttime = obj["nighttimeForecast"]?.jsonObject
        val dayPop = daytime?.precipPercent()
        val nightPop = nighttime?.precipPercent()
        val type = daytime?.condition() ?: nighttime?.condition()
        val dayMm = daytime?.precipMm()
        val nightMm = nighttime?.precipMm()
        return DailyForecast(
            date = date,
            highTemp = obj.degrees("maxTemperature"),
            lowTemp = obj.degrees("minTemperature"),
            condition = WeatherCodeMapper.googleConditionToCondition(type),
            iconToken = type,
            precipProbability = listOfNotNull(dayPop, nightPop).maxOrNull(),
            precipAmountMm = if (dayMm == null && nightMm == null) null else (dayMm ?: 0f) + (nightMm ?: 0f),
            source = WeatherSource.GOOGLE_WEATHER.id,
            daytimePrecipProbability = dayPop,
            nighttimePrecipProbability = nightPop,
        )
    }

    private suspend fun fetchJson(
        apiKey: String,
        path: String,
        lat: Double,
        lon: Double,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): JsonObject {
        val response = httpClient.get("$BASE_URL$path") {
            header("X-Goog-Api-Key", apiKey)
            parameter("location.latitude", lat)
            parameter("location.longitude", lon)
            parameter("unitsSystem", "IMPERIAL")
            extra()
        }
        val endpoint = path.removePrefix("/").substringBefore(':')
        onRequest("endpoint=$endpoint status=${response.status.value}")
        response.require2xx(WeatherSource.GOOGLE_WEATHER, "Google Weather fetch failed ($path)")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun parseInstantMs(ts: String): Long? =
        runCatching { Instant.parse(ts).toEpochMilli() }
            .onFailure { Log.w(TAG, "Unparseable Google Weather timestamp: $ts") }
            .getOrNull()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.floatOrNull?.let { Math.round(it) }
    private fun JsonObject.degrees(key: String): Float? = this[key]?.jsonObject?.get("degrees")?.jsonPrimitive?.floatOrNull
    private fun JsonObject.condition(): String? = this["weatherCondition"]?.jsonObject?.string("type")
    private fun JsonObject.precipPercent(): Int? =
        this["precipitation"]?.jsonObject?.get("probability")?.jsonObject?.int("percent")?.coerceIn(0, 100)

    /** `qpf` in the requested unit; IMPERIAL reports INCHES, the app stores mm. */
    private fun JsonObject.precipMm(): Float? {
        val qpf = this["precipitation"]?.jsonObject?.get("qpf")?.jsonObject ?: return null
        val quantity = qpf["quantity"]?.jsonPrimitive?.floatOrNull ?: return null
        return when (qpf.string("unit")) {
            "MILLIMETERS" -> quantity
            else -> quantity * INCHES_TO_MM
        }
    }
}
