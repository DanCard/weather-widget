package com.weatherwidget.widget

import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.log
import java.time.Instant
import java.time.ZoneId

internal data class CandidateUsability(
    val useful: Boolean,
    val reason: String,
)

internal object LocationHandoffPolicy {
    /**
     * A short stability window keeps a drive through several forecast sites from repainting and
     * refetching each intermediate site. Complete cached coverage (for example, returning home)
     * can still promote immediately.
     */
    const val MOVING_GRACE_MS = 30 * 60 * 1000L

    const val REQUIRED_DAILY_DAYS = 3
    const val VISIBLE_BACK_HOURS = 12
    const val VISIBLE_FORWARD_HOURS = 12
    const val MIN_COMPLETE_VISIBLE_HOURS = 22
    const val MIN_FORWARD_HOURS = 10
}

/**
 * Decides whether candidate-location data is useful enough to replace the last complete body.
 *
 * This intentionally distinguishes "complete cached 24h window" from "successful current/future
 * response at a genuinely new site." The latter becomes usable after a movement grace instead of
 * requiring historical forecasts that the app could not have collected before arriving.
 */
@VisibleForTesting
internal fun evaluateCandidateUsability(
    forecasts: List<ForecastEntity>,
    hourlyForecasts: List<HourlyForecastEntity>,
    requiredSourceIds: Set<String>,
    requiresHourlyData: Boolean,
    nowMs: Long,
    candidateFirstSeenMs: Long,
): CandidateUsability {
    if (requiredSourceIds.isEmpty()) {
        return CandidateUsability(useful = false, reason = "no_display_sources")
    }

    val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val todayMs = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY
    val dailyEndMs = today.plusDays(LocationHandoffPolicy.REQUIRED_DAILY_DAYS.toLong()).toEpochDay() *
        WidgetConstants.MS_IN_A_DAY
    val dailyReady = requiredSourceIds.all { source ->
        forecasts.asSequence()
            .filter { it.source == source }
            .filter { it.targetDate in todayMs until dailyEndMs }
            .filter { it.highTemp != null && it.lowTemp != null }
            .map { it.targetDate }
            .distinct()
            .count() >= LocationHandoffPolicy.REQUIRED_DAILY_DAYS
    }
    if (!dailyReady) {
        return CandidateUsability(useful = false, reason = "insufficient_daily_coverage")
    }

    if (!requiresHourlyData) {
        return CandidateUsability(useful = true, reason = "daily_coverage")
    }

    val alignedNowMs = (nowMs / MILLIS_PER_HOUR) * MILLIS_PER_HOUR
    val visibleStart = alignedNowMs - LocationHandoffPolicy.VISIBLE_BACK_HOURS * MILLIS_PER_HOUR
    val visibleEnd = alignedNowMs + LocationHandoffPolicy.VISIBLE_FORWARD_HOURS * MILLIS_PER_HOUR
    val forwardEnd = alignedNowMs + LocationHandoffPolicy.VISIBLE_FORWARD_HOURS * MILLIS_PER_HOUR

    val completeVisible = requiredSourceIds.all { source ->
        hourlyForecasts.asSequence()
            .filter { it.source == source && it.dateTime in visibleStart until visibleEnd }
            .map { (it.dateTime / MILLIS_PER_HOUR) * MILLIS_PER_HOUR }
            .distinct()
            .count() >= LocationHandoffPolicy.MIN_COMPLETE_VISIBLE_HOURS
    }
    if (completeVisible) {
        return CandidateUsability(useful = true, reason = "complete_visible_coverage")
    }

    val forwardReady = requiredSourceIds.all { source ->
        hourlyForecasts.asSequence()
            .filter { it.source == source && it.dateTime in alignedNowMs until forwardEnd }
            .map { (it.dateTime / MILLIS_PER_HOUR) * MILLIS_PER_HOUR }
            .distinct()
            .count() >= LocationHandoffPolicy.MIN_FORWARD_HOURS
    }
    val graceElapsed = nowMs - candidateFirstSeenMs >= LocationHandoffPolicy.MOVING_GRACE_MS
    return if (forwardReady && graceElapsed) {
        CandidateUsability(useful = true, reason = "forward_coverage_after_grace")
    } else {
        CandidateUsability(useful = false, reason = "waiting_for_history_or_stability")
    }
}

internal sealed class LocationCandidateOutcome {
    data class Superseded(val message: String) : LocationCandidateOutcome()
    data class WaitingForData(val reason: String) : LocationCandidateOutcome()
    data class PromotionFailed(val reason: String) : LocationCandidateOutcome()
    data class Promoted(val reason: String) : LocationCandidateOutcome()
}

internal suspend fun tryPromoteLocationCandidate(
    context: android.content.Context,
    appLogDao: com.weatherwidget.data.local.AppLogDao,
    widgetStateManager: WidgetStateManager,
    candidateAtLoad: CandidateLocation,
    weatherList: List<ForecastEntity>,
    hourlyForecasts: List<HourlyForecastEntity>,
    activeSourceIds: Collection<String>,
    appWidgetIds: IntArray,
): LocationCandidateOutcome {
    val currentCandidate = LocationHandoffStore.getCandidate(context)
    if (currentCandidate == null || !LocationHandoffStore.matches(candidateAtLoad, currentCandidate)) {
        val message = "state=candidate_superseded loaded=${candidateAtLoad.location.lat},${candidateAtLoad.location.lon}"
        appLogDao.log("LOCATION_HANDOFF", message, "INFO")
        return LocationCandidateOutcome.Superseded(message)
    }

    val requiresHourlyData = appWidgetIds.any { id ->
        widgetStateManager.getViewMode(id) != ViewMode.DAILY
    }
    val usability = evaluateCandidateUsability(
        forecasts = weatherList,
        hourlyForecasts = hourlyForecasts,
        requiredSourceIds = activeSourceIds.toSet(),
        requiresHourlyData = requiresHourlyData,
        nowMs = System.currentTimeMillis(),
        candidateFirstSeenMs = candidateAtLoad.firstSeenMs,
    )
    if (!usability.useful) {
        val message = "state=candidate_waiting_data reason=${usability.reason} " +
            "candidate=${candidateAtLoad.location.lat},${candidateAtLoad.location.lon} " +
            "dailyRows=${weatherList.size} hourlyRows=${hourlyForecasts.size}"
        appLogDao.log("LOCATION_HANDOFF", message, "INFO")
        return LocationCandidateOutcome.WaitingForData(usability.reason)
    }

    if (!com.weatherwidget.ui.LocationUpdater.promoteCandidateIfMatches(
            context,
            candidateAtLoad,
            appWidgetIds,
        )
    ) {
        appLogDao.log(
            "LOCATION_HANDOFF",
            "state=candidate_superseded phase=promotion",
            "INFO",
        )
        return LocationCandidateOutcome.PromotionFailed("promotion_rejected")
    }

    val message = "state=candidate_promoted reason=${usability.reason} " +
        "location=${candidateAtLoad.location.lat},${candidateAtLoad.location.lon}"
    appLogDao.log("LOCATION_HANDOFF", message, "INFO")
    return LocationCandidateOutcome.Promoted(usability.reason)
}

private const val MILLIS_PER_HOUR = 60 * 60 * 1000L
