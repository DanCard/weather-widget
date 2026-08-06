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
)

/** Resolves the exact compact Today annotation shared by Android and desktop. */
object TodayColumnOverlayContentResolver {
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
                lookaheadHours = 2L,
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
                lookaheadHours = 2L,
                zoneId = zoneId,
                personalStationWeight = personalStationWeight,
            )
        val dominant = details?.takeIf { it.observedAt == observedAt }?.dominantContribution
        // The overlay delta row is the FORECAST delta (observed minus forecast at the current
        // hour), supplied by the caller — the yesterday delta moved to the widget header.
        val deltaText = forecastDelta?.let { ForecastDeltaLabel.formatValue(it, useCelsius) }
        val dominantRows = dominant?.let { BlendTableFormatter.formatDominantTempAgeRows(it.contribution, useCelsius) }
        if (deltaText == null) {
            Log.d(
                TAG,
                "delta null: observedAtMs=$observedAt currentObsTemp=$currentObservedTemp " +
                    "forecastDelta=$forecastDelta obsCount=${observations.size} displaySource=$displaySourceId",
            )
        }
        if (deltaText == null && dominantRows == null) return null
        return TodayColumnOverlayContent(
            deltaValueText = deltaText,
            deltaCaptionText = deltaText?.let { ForecastDeltaLabel.COMPACT_CAPTION },
            dominantTempText = dominantRows?.temperature,
            dominantAgeText = dominantRows?.age,
            observedAt = observedAt,
            dominantContribution = dominant,
        )
    }
}
