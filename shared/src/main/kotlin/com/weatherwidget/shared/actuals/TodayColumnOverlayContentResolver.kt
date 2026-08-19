package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.shared.graph.ForecastDeltaLabel
import com.weatherwidget.shared.util.Log
import java.time.ZoneId

private const val TAG = "TodayColOverlayResolver"

data class TodayColumnOverlayContent(
    val deltaValueText: String?,
    val deltaCaptionText: String?,
    val dominantTempText: String?,
    val dominantAgeText: String?,
    val observedAt: Long,
    val dominantContribution: DominantBlend?,
    /**
     * Why the station rows are absent, or null when a station was named. The rows can vanish for
     * three structurally different reasons and the rendered overlay looks identical in all three
     * (delta row alone), so the distinction only exists in this field — see [TodayColumnOverlayContentResolver].
     */
    val dominantNullReason: String? = null,
)

/** Resolves the exact compact Today annotation shared by Android and desktop. */
object TodayColumnOverlayContentResolver {
    /**
     * Lookahead for the blend window that resolves "the current observation".
     *
     * [resolveLatest] derives `observedAt` from its own blend, so its window is self-consistent
     * whatever this is. [resolveAt] is different: it is handed an `observedAt` derived elsewhere and
     * keeps the dominant station only when its own blend agrees exactly. A caller using that overload
     * MUST pass the lookahead the producer used — otherwise the two blends can select different
     * latest readings and the station rows disappear with the delta row still showing. Android's
     * producer is `CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS`.
     */
    const val DEFAULT_LOOKAHEAD_HOURS = 2L

    fun resolveLatest(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long,
        personalStationWeight: Double,
        useCelsius: Boolean,
        forecastDelta: Float? = null,
        showForecastDelta: Boolean = true,
        showDominantStationTemp: Boolean = true,
        showDominantReadingAge: Boolean = true,
        lookaheadHours: Long = DEFAULT_LOOKAHEAD_HOURS,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TodayColumnOverlayContent? {
        val details =
            ActualsAggregator.resolveCurrentObservationDetails(
                observations = observations,
                hourlyForecasts = hourlyForecasts,
                displaySourceId = displaySourceId,
                userLat = userLat,
                userLon = userLon,
                nowMs = nowMs,
                lookaheadHours = lookaheadHours,
                zoneId = zoneId,
                personalStationWeight = personalStationWeight,
            ) ?: return null
        return resolveAt(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            nowMs = nowMs,
            observedAt = details.observedAt,
            currentObservedTemp = details.temperature,
            personalStationWeight = personalStationWeight,
            useCelsius = useCelsius,
            forecastDelta = forecastDelta,
            showForecastDelta = showForecastDelta,
            showDominantStationTemp = showDominantStationTemp,
            showDominantReadingAge = showDominantReadingAge,
            lookaheadHours = lookaheadHours,
            zoneId = zoneId,
            resolvedDetails = details,
        )
    }

    fun resolveAt(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long,
        observedAt: Long,
        currentObservedTemp: Float?,
        personalStationWeight: Double,
        useCelsius: Boolean,
        forecastDelta: Float? = null,
        showForecastDelta: Boolean = true,
        showDominantStationTemp: Boolean = true,
        showDominantReadingAge: Boolean = true,
        lookaheadHours: Long = DEFAULT_LOOKAHEAD_HOURS,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TodayColumnOverlayContent? =
        resolveAt(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            nowMs = nowMs,
            observedAt = observedAt,
            currentObservedTemp = currentObservedTemp,
            personalStationWeight = personalStationWeight,
            useCelsius = useCelsius,
            forecastDelta = forecastDelta,
            showForecastDelta = showForecastDelta,
            showDominantStationTemp = showDominantStationTemp,
            showDominantReadingAge = showDominantReadingAge,
            lookaheadHours = lookaheadHours,
            zoneId = zoneId,
            resolvedDetails = null,
        )

    private fun resolveAt(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        nowMs: Long,
        observedAt: Long,
        currentObservedTemp: Float?,
        personalStationWeight: Double,
        useCelsius: Boolean,
        forecastDelta: Float?,
        showForecastDelta: Boolean,
        showDominantStationTemp: Boolean,
        showDominantReadingAge: Boolean,
        lookaheadHours: Long,
        zoneId: ZoneId,
        resolvedDetails: ActualsAggregator.CurrentObservationResolution?,
    ): TodayColumnOverlayContent? {
        val details =
            resolvedDetails ?: ActualsAggregator.resolveCurrentObservationDetails(
                observations = observations,
                hourlyForecasts = hourlyForecasts,
                displaySourceId = displaySourceId,
                userLat = userLat,
                userLon = userLon,
                nowMs = nowMs,
                lookaheadHours = lookaheadHours,
                zoneId = zoneId,
                personalStationWeight = personalStationWeight,
            )
        val dominant = details?.takeIf { it.observedAt == observedAt }?.dominantContribution
        // Three distinct ways to lose the station rows, indistinguishable on screen:
        //  - no_details      : the blend emitted nothing at all for this window.
        //  - observed_at_skew: this resolve's blend landed on a different latest observation than the
        //                      caller's `observedAt`, which was derived by a DIFFERENT path over a
        //                      DIFFERENT window (WidgetRenderer's current-temp resolution window).
        //  - no_contribution : the blend agreed on the timestamp but captured no dominant station.
        val dominantNullReason =
            when {
                dominant != null -> null
                details == null -> "no_details"
                details.observedAt != observedAt -> "observed_at_skew(derived=${details.observedAt})"
                else -> "no_contribution"
            }
        // The overlay delta row is the FORECAST delta (observed minus forecast at the current
        // hour), supplied by the caller — the yesterday delta moved to the widget header.
        val deltaText =
            forecastDelta
                ?.let { ForecastDeltaLabel.formatValue(it, useCelsius) }
                ?.takeIf { showForecastDelta }
        val dominantRows =
            dominant?.let { BlendTableFormatter.formatDominantTempAgeRows(it.contribution, useCelsius) }
        val dominantTempText = dominantRows?.temperature?.takeIf { showDominantStationTemp }
        val dominantAgeText = dominantRows?.age?.takeIf { showDominantReadingAge }
        if (deltaText == null) {
            Log.d(
                TAG,
                "delta null: observedAtMs=$observedAt currentObsTemp=$currentObservedTemp " +
                    "forecastDelta=$forecastDelta showDelta=$showForecastDelta " +
                    "obsCount=${observations.size} displaySource=$displaySourceId",
            )
        }
        if (deltaText == null && dominantTempText == null && dominantAgeText == null) return null
        return TodayColumnOverlayContent(
            deltaValueText = deltaText,
            deltaCaptionText = deltaText?.let { ForecastDeltaLabel.COMPACT_CAPTION },
            dominantTempText = dominantTempText,
            dominantAgeText = dominantAgeText,
            observedAt = observedAt,
            dominantContribution = dominant,
            dominantNullReason = dominantNullReason,
        )
    }
}
