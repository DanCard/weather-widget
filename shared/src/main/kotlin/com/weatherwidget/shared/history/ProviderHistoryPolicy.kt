package com.weatherwidget.shared.history

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.local.LocationMatch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

sealed interface ProviderHistoryDecision {
    data class NotApplicable(val reason: String) : ProviderHistoryDecision
    data class AlreadyCovered(val date: LocalDate, val distinctHours: Int) : ProviderHistoryDecision
    data class Fetch(val date: LocalDate, val distinctHours: Int) : ProviderHistoryDecision
    data class Cooldown(
        val date: LocalDate,
        val distinctHours: Int,
        val retryAtMs: Long,
    ) : ProviderHistoryDecision
}

enum class ProviderHistoryFailureClass {
    AUTH_OR_PLAN,
    QUOTA,
    MALFORMED_RESPONSE,
    TRANSIENT,
}

/**
 * Platform-neutral policy for WeatherAPI's one-day automatic provider-history repair.
 */
object ProviderHistoryPolicy {
    const val COMPLETE_DAY_MIN_DISTINCT_HOURS = 20
    const val HOUR_MS = 3_600_000L

    const val TRANSIENT_RETRY_MS = 60 * 60 * 1000L
    const val MALFORMED_RETRY_MS = 6 * 60 * 60 * 1000L
    const val QUOTA_RETRY_MS = 6 * 60 * 60 * 1000L
    const val AUTH_OR_PLAN_RETRY_MS = 24 * 60 * 60 * 1000L
    private val RETRY_AT_REGEX = Regex("""(?:^|\s)retryAtMs=(\d+)(?:\s|$)""")

    fun targetDate(
        nowMs: Long,
        zoneId: ZoneId,
    ): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().minusDays(1)

    fun decide(
        source: WeatherSource,
        nowMs: Long,
        zoneId: ZoneId,
        storedTimestamps: List<Long>,
        retryAtMs: Long?,
    ): ProviderHistoryDecision {
        if (source != WeatherSource.WEATHER_API) {
            return ProviderHistoryDecision.NotApplicable("source=${source.id}")
        }
        val date = targetDate(nowMs, zoneId)
        val distinctHours =
            storedTimestamps
                .asSequence()
                .filter {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() == date
                }
                .map { it / HOUR_MS }
                .distinct()
                .count()
        if (distinctHours >= COMPLETE_DAY_MIN_DISTINCT_HOURS) {
            return ProviderHistoryDecision.AlreadyCovered(date, distinctHours)
        }
        if (retryAtMs != null && retryAtMs > nowMs) {
            return ProviderHistoryDecision.Cooldown(date, distinctHours, retryAtMs)
        }
        return ProviderHistoryDecision.Fetch(date, distinctHours)
    }

    fun failureClassForStatus(statusCode: Int?): ProviderHistoryFailureClass =
        when (statusCode) {
            401, 403 -> ProviderHistoryFailureClass.AUTH_OR_PLAN
            429 -> ProviderHistoryFailureClass.QUOTA
            in 400..499 -> ProviderHistoryFailureClass.AUTH_OR_PLAN
            else -> ProviderHistoryFailureClass.TRANSIENT
        }

    fun retryDelayMs(failureClass: ProviderHistoryFailureClass): Long =
        when (failureClass) {
            ProviderHistoryFailureClass.AUTH_OR_PLAN -> AUTH_OR_PLAN_RETRY_MS
            ProviderHistoryFailureClass.QUOTA -> QUOTA_RETRY_MS
            ProviderHistoryFailureClass.MALFORMED_RESPONSE -> MALFORMED_RETRY_MS
            ProviderHistoryFailureClass.TRANSIENT -> TRANSIENT_RETRY_MS
        }

    fun retryAtFromMessage(message: String): Long? =
        RETRY_AT_REGEX.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()

    fun siteKey(
        latitude: Double,
        longitude: Double,
    ): String =
        String.format(
            Locale.US,
            "%.3f,%.3f",
            LocationMatch.quantize(latitude),
            LocationMatch.quantize(longitude),
        )
}
