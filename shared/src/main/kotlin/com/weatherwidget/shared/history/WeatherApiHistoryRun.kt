package com.weatherwidget.shared.history

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * WeatherAPI's missing prior-day provider history, repaired the same way on both platforms: the
 * coverage check, the [ProviderHistoryPolicy] decision, the fetch, the partial-day retry and the
 * `WAPI_HISTORY_*` logs. Was Android's `WeatherApiHistoryBackfiller` and desktop's
 * `backfillWeatherApiHistoryIfNeeded` (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 * Failures are optional: a valid current forecast stays successful.
 */
object WeatherApiHistoryRun {
    const val CHECK_TAG = "WAPI_HISTORY_CHECK"
    const val RESULT_TAG = "WAPI_HISTORY_RESULT"

    enum class Status { FETCHED, ALREADY_COVERED, COOLDOWN, FAILED }

    data class Result(val status: Status, val storedHours: Int = 0, val retryAtMs: Long? = null)

    interface Host {
        val lat: Double
        val lon: Double

        /** WeatherAPI observations stored at or near the site in [startMs, endMs). */
        suspend fun storedObservations(startMs: Long, endMs: Long): List<ObservationReading>

        /** The newest [RESULT_TAG] log message starting with [prefix] (its retry time), or null. */
        suspend fun latestResult(prefix: String): String?

        /** WeatherAPI `history.json` for [date]: its hourly list. Throws on failure. */
        suspend fun fetchHistory(date: LocalDate): History

        /**
         * Stores [targetHours] as the day's provider history (observation rows, via the platform's
         * historical-actuals path) and refreshes that day's derived extremes.
         */
        suspend fun store(targetHours: List<HourlyForecast>, date: LocalDate)

        suspend fun log(tag: String, message: String, level: String)
    }

    data class History(val hourly: List<HourlyForecast>, val dailyCount: Int)

    /** The day the run repairs and its stored-observation window [startMs, endMs). */
    data class Window(val date: LocalDate, val startMs: Long, val endMs: Long, val logPrefix: String)

    fun window(lat: Double, lon: Double, nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Window {
        val date = ProviderHistoryPolicy.targetDate(nowMs, zoneId)
        return Window(
            date = date,
            startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            logPrefix = "site=${ProviderHistoryPolicy.siteKey(lat, lon)} date=$date",
        )
    }

    /**
     * Whether the run would fetch: [stored] are the site's observations in [window], [latestResult]
     * the newest [RESULT_TAG] message for its prefix. Also desktop's "show the fetching notice?" check.
     */
    fun decide(
        lat: Double,
        lon: Double,
        stored: List<ObservationReading>,
        latestResult: String?,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ProviderHistoryDecision {
        val storedTimestamps = stored
            .filter { it.api == WeatherSource.WEATHER_API.id && LocationMatch.sameSite(it.locationLat, it.locationLon, lat, lon) }
            .map { it.timestamp }
        val retryAtMs = latestResult?.let(ProviderHistoryPolicy::retryAtFromMessage)
        return ProviderHistoryPolicy.decide(WeatherSource.WEATHER_API, nowMs, zoneId, storedTimestamps, retryAtMs)
    }

    suspend fun run(host: Host, nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Result {
        val w = window(host.lat, host.lon, nowMs, zoneId)
        val targetDate = w.date
        val prefix = w.logPrefix
        val stored = host.storedObservations(w.startMs, w.endMs)

        when (val decision = decide(host.lat, host.lon, stored, host.latestResult(prefix), nowMs, zoneId)) {
            is ProviderHistoryDecision.AlreadyCovered -> {
                host.log(CHECK_TAG, "$prefix coverage=${decision.distinctHours} decision=covered", "VERBOSE")
                return Result(Status.ALREADY_COVERED, storedHours = decision.distinctHours)
            }
            is ProviderHistoryDecision.Cooldown -> {
                host.log(CHECK_TAG, "$prefix coverage=${decision.distinctHours} decision=cooldown retryAtMs=${decision.retryAtMs}", "VERBOSE")
                return Result(Status.COOLDOWN, storedHours = decision.distinctHours, retryAtMs = decision.retryAtMs)
            }
            is ProviderHistoryDecision.NotApplicable -> return Result(Status.ALREADY_COVERED)
            is ProviderHistoryDecision.Fetch ->
                host.log(CHECK_TAG, "$prefix coverage=${decision.distinctHours} decision=fetch", "INFO")
        }

        return try {
            val history = host.fetchHistory(targetDate)
            val targetHours = history.hourly.filter {
                Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDate() == targetDate
            }
            if (targetHours.isEmpty()) {
                return recordFailure(host, prefix, nowMs, ProviderHistoryFailureClass.MALFORMED_RESPONSE, "empty_target_day")
            }
            host.store(targetHours, targetDate)
            val distinctHours = targetHours.map { it.dateTime / ProviderHistoryPolicy.HOUR_MS }.distinct().size
            // A partial day is stored and retried later, as a malformed reply would be.
            val partialRetryAt = if (distinctHours < ProviderHistoryPolicy.COMPLETE_DAY_MIN_DISTINCT_HOURS) {
                nowMs + ProviderHistoryPolicy.retryDelayMs(ProviderHistoryFailureClass.MALFORMED_RESPONSE)
            } else {
                null
            }
            val retry = partialRetryAt?.let { " retryAtMs=$it" }.orEmpty()
            host.log(RESULT_TAG, "$prefix result=stored hours=$distinctHours daily=${history.dailyCount}$retry", "INFO")
            Result(Status.FETCHED, storedHours = distinctHours, retryAtMs = partialRetryAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val statusCode = (e as? ApiAccessException)?.statusCode
            recordFailure(
                host, prefix, nowMs,
                ProviderHistoryPolicy.failureClassForStatus(statusCode),
                statusCode?.let { "http_$it" } ?: e.javaClass.simpleName.ifBlank { "error" },
            )
        }
    }

    private suspend fun recordFailure(
        host: Host,
        prefix: String,
        nowMs: Long,
        failureClass: ProviderHistoryFailureClass,
        detail: String,
    ): Result {
        val retryAtMs = nowMs + ProviderHistoryPolicy.retryDelayMs(failureClass)
        host.log(
            RESULT_TAG,
            "$prefix result=cooldown failure=${failureClass.name.lowercase(Locale.US)} detail=$detail retryAtMs=$retryAtMs",
            "WARN",
        )
        return Result(Status.FAILED, retryAtMs = retryAtMs)
    }
}
