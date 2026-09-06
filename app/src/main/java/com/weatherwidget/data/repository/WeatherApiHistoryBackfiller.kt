package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.shared.history.ProviderHistoryDecision
import com.weatherwidget.shared.history.ProviderHistoryFailureClass
import com.weatherwidget.shared.history.ProviderHistoryPolicy
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal enum class WeatherApiHistoryBackfillStatus {
    FETCHED,
    ALREADY_COVERED,
    COOLDOWN,
    FAILED,
}

internal data class WeatherApiHistoryBackfillResult(
    val status: WeatherApiHistoryBackfillStatus,
    val storedHours: Int = 0,
    val retryAtMs: Long? = null,
)

/**
 * Repairs WeatherAPI's missing prior-day provider history without coupling it to the NWS station
 * observation worker. Failures are optional: a valid current forecast remains successful.
 */
internal class WeatherApiHistoryBackfiller(
    private val weatherApi: WeatherApi,
    private val observationDao: ObservationDao,
    private val hourlyStore: HourlyForecastStore,
    private val observationRepository: ObservationRepository,
    private val appLogDao: AppLogDao,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun backfillIfNeeded(
        latitude: Double,
        longitude: Double,
    ): WeatherApiHistoryBackfillResult {
        val nowMs = nowProvider()
        val zoneId = zoneIdProvider()
        val targetDate = ProviderHistoryPolicy.targetDate(nowMs, zoneId)
        val dayStartMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEndMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val siteKey = ProviderHistoryPolicy.siteKey(latitude, longitude)
        val logPrefix = "site=$siteKey date=$targetDate"
        val storedTimestamps =
            observationDao.getObservationsInRange(
                dayStartMs,
                dayEndMs,
                latitude,
                longitude,
            ).asSequence()
                .filter {
                    it.api == WeatherSource.WEATHER_API.id &&
                        LocationMatch.sameSite(
                            it.locationLat,
                            it.locationLon,
                            latitude,
                            longitude,
                        )
                }
                .map { it.timestamp }
                .toList()
        val retryAtMs =
            appLogDao.getLatestLogByTagAndMessagePrefix(
                HISTORY_RESULT_TAG,
                logPrefix,
            )?.message
                ?.let(ProviderHistoryPolicy::retryAtFromMessage)
        val decision =
            ProviderHistoryPolicy.decide(
                source = WeatherSource.WEATHER_API,
                nowMs = nowMs,
                zoneId = zoneId,
                storedTimestamps = storedTimestamps,
                retryAtMs = retryAtMs,
            )

        when (decision) {
            is ProviderHistoryDecision.AlreadyCovered -> {
                appLogDao.log(
                    HISTORY_CHECK_TAG,
                    "$logPrefix coverage=${decision.distinctHours} decision=covered",
                    "VERBOSE",
                )
                return WeatherApiHistoryBackfillResult(
                    WeatherApiHistoryBackfillStatus.ALREADY_COVERED,
                    storedHours = decision.distinctHours,
                )
            }
            is ProviderHistoryDecision.Cooldown -> {
                appLogDao.log(
                    HISTORY_CHECK_TAG,
                    "$logPrefix coverage=${decision.distinctHours} " +
                        "decision=cooldown retryAtMs=${decision.retryAtMs}",
                    "VERBOSE",
                )
                return WeatherApiHistoryBackfillResult(
                    WeatherApiHistoryBackfillStatus.COOLDOWN,
                    storedHours = decision.distinctHours,
                    retryAtMs = decision.retryAtMs,
                )
            }
            is ProviderHistoryDecision.Fetch -> {
                appLogDao.log(
                    HISTORY_CHECK_TAG,
                    "$logPrefix coverage=${decision.distinctHours} decision=fetch",
                )
            }
            is ProviderHistoryDecision.NotApplicable -> {
                return WeatherApiHistoryBackfillResult(
                    WeatherApiHistoryBackfillStatus.ALREADY_COVERED,
                )
            }
        }

        return try {
            val history = weatherApi.getHistory(latitude, longitude, targetDate)
            val targetHours =
                history.hourly.filter {
                    Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDate() == targetDate
                }
            if (targetHours.isEmpty()) {
                return recordFailure(
                    logPrefix = logPrefix,
                    nowMs = nowMs,
                    failureClass = ProviderHistoryFailureClass.MALFORMED_RESPONSE,
                    detail = "empty_target_day",
                )
            }

            hourlyStore.saveHourlyEntitiesFromShared(
                hourlyData = targetHours,
                latitude = latitude,
                longitude = longitude,
                sourceId = WeatherSource.WEATHER_API.id,
            )
            observationRepository.recomputeDailyExtremesFromStoredObservations(
                latitude = latitude,
                longitude = longitude,
                startDate = targetDate,
                endDateInclusive = targetDate,
                hourlyForecasts = emptyList(),
                // Repair path: it distrusts what is stored, which is the whole reason it ran.
                force = true,
            )

            val distinctHours =
                targetHours.asSequence()
                    .map { it.dateTime / ProviderHistoryPolicy.HOUR_MS }
                    .distinct()
                    .count()
            val partialRetryAt =
                if (distinctHours < ProviderHistoryPolicy.COMPLETE_DAY_MIN_DISTINCT_HOURS) {
                    nowMs + ProviderHistoryPolicy.retryDelayMs(
                        ProviderHistoryFailureClass.MALFORMED_RESPONSE,
                    )
                } else {
                    null
                }
            appLogDao.log(
                HISTORY_RESULT_TAG,
                buildString {
                    append("$logPrefix result=stored hours=$distinctHours daily=${history.daily.size}")
                    if (partialRetryAt != null) append(" retryAtMs=$partialRetryAt")
                },
                "INFO",
            )
            WeatherApiHistoryBackfillResult(
                status = WeatherApiHistoryBackfillStatus.FETCHED,
                storedHours = distinctHours,
                retryAtMs = partialRetryAt,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            val statusCode = (exception as? ApiAccessException)?.statusCode
            recordFailure(
                logPrefix = logPrefix,
                nowMs = nowMs,
                failureClass = ProviderHistoryPolicy.failureClassForStatus(statusCode),
                detail = statusCode?.let { "http_$it" }
                    ?: exception.javaClass.simpleName.ifBlank { "error" },
            )
        }
    }

    private suspend fun recordFailure(
        logPrefix: String,
        nowMs: Long,
        failureClass: ProviderHistoryFailureClass,
        detail: String,
    ): WeatherApiHistoryBackfillResult {
        val retryAtMs = nowMs + ProviderHistoryPolicy.retryDelayMs(failureClass)
        appLogDao.log(
            HISTORY_RESULT_TAG,
            "$logPrefix result=cooldown failure=${failureClass.name.lowercase(Locale.US)} " +
                "detail=$detail retryAtMs=$retryAtMs",
            "WARN",
        )
        return WeatherApiHistoryBackfillResult(
            status = WeatherApiHistoryBackfillStatus.FAILED,
            retryAtMs = retryAtMs,
        )
    }

    companion object {
        private const val HISTORY_CHECK_TAG = "WAPI_HISTORY_CHECK"
        private const val HISTORY_RESULT_TAG = "WAPI_HISTORY_RESULT"
    }
}
