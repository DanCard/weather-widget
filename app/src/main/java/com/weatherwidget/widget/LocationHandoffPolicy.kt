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
 * Decides whether candidate-location data is useful enough to replace what the widget shows.
 *
 * There are two operations here and they want opposite biases:
 *
 *  - **Following** ([isAcquisition] = false): site A → site B, with a perfectly good body for A on
 *    screen. Be conservative. Distinguish "complete cached 24h window" from "successful
 *    current/future response at a genuinely new site"; the latter becomes usable only after a
 *    movement grace, so driving past three forecast sites doesn't repaint and refetch at each one.
 *  - **Acquisition** ([isAcquisition] = true): there is no active location, so what's on screen is
 *    "No location — tap to set". There is no body to protect and nothing to flap between. Any
 *    drawable forecast beats an error message, immediately.
 *
 * Sharing one answer meant acquisition inherited the driving case's caution: a fresh install could
 * fetch its weather successfully and keep showing the error for the length of the grace — and, since
 * promotion is only retried on a full sync, in practice until the next one (60 min plugged, up to
 * 480 min on low battery). Hence a branch rather than a smaller [LocationHandoffPolicy.MOVING_GRACE_MS]:
 * a compromise constant would have to be wrong for one of the two cases, and tuning it later for the
 * driving case would silently re-strand first-time users.
 *
 * @param isAcquisition true when the app has no active location at all.
 */
@VisibleForTesting
internal fun evaluateCandidateUsability(
    forecasts: List<ForecastEntity>,
    hourlyForecasts: List<HourlyForecastEntity>,
    requiredSourceIds: Set<String>,
    requiresHourlyData: Boolean,
    nowMs: Long,
    candidateFirstSeenMs: Long,
    isAcquisition: Boolean,
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

    // Still gated on dailyReady above: promoting with nothing to draw would swap one blank state for
    // another. Past that, the error message is the thing being replaced, so nothing is worth waiting for.
    if (isAcquisition) {
        return CandidateUsability(useful = true, reason = "acquisition_daily_coverage")
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
        // current(), not resolve(): the question is whether a location is *established*, and resolve()
        // would answer yes off this very candidate's freshly-written forecast rows.
        isAcquisition = ActiveLocationResolver.current(context) == null,
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
