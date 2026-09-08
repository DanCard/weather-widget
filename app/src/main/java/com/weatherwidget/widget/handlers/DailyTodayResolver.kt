package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.shared.util.DailySnapshotSelector
import com.weatherwidget.util.DailyActualsEstimator
import com.weatherwidget.util.DailyForecastIconResolver
import com.weatherwidget.widget.DailyActualMap
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal object DailyTodayResolver {
    private const val TAG = "DailyTodayResolver"

    data class TodayValues(
        val finalHigh: Float?,
        val finalLow: Float?,
        val fHigh: Float?,
        val fLow: Float?,
        val bottomStackLow: Float?,
        val snapshotHigh: Float?,
        val snapshotLow: Float?,
        val snapshotIconRes: Int?,
        val trueActualHigh: Float?,
        val todayHasActualLow: Boolean,
        val isTodayForecastFallback: Boolean,
    )

    /**
     * Today's freshest batch is often high-only (the NWS evening drop: once the daytime period has
     * passed, the grid returns a low-less period). [incomplete] is that row; this finds the most
     * recent COMPLETE row to stand in for it.
     */
    fun completeSameSiteReplacement(
        incomplete: ForecastEntity,
        snapshots: List<ForecastEntity>,
    ): ForecastEntity? =
        snapshots.filter {
            it.source == incomplete.source &&
                it.highTemp != null &&
                it.lowTemp != null &&
                LocationMatch.sameSite(
                    incomplete.locationLat,
                    incomplete.locationLon,
                    it.locationLat,
                    it.locationLon,
                )
        }.maxByOrNull { it.fetchedAt }

    fun resolveTodayValues(
        date: LocalDate,
        today: LocalDate,
        now: LocalDateTime,
        displaySource: WeatherSource,
        weather: ForecastEntity?,
        dailyActuals: DailyActualMap,
        actual: DailyHistory?,
        forecasts: List<ForecastEntity>,
        hourlyForecasts: List<HourlyForecastEntity>,
        currentTemp: Float?,
    ): TodayValues {
        val snapshotCandidates = forecasts
            .filter { it.source == displaySource.id }
            .filter { it.highTemp != null && it.lowTemp != null }
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val snapshot = DailySnapshotSelector.selectPriorDaySnapshot(
            snapshotCandidates, nowMillis, { it.fetchedAt },
        )

        val snapshotIconRes = snapshot?.let { w ->
            DailyForecastIconResolver.resolveIcon(
                weather = w,
                targetDate = date,
                now = now,
                latitude = w.locationLat,
                longitude = w.locationLon,
                dayPrecipProbability = w.daytimePrecipProbability ?: w.precipProbability,
                nightPrecipProbability = w.nighttimePrecipProbability,
            )
        }

        val tripleValues = DailyActualsEstimator.calculateTodayTripleLineValues(
            hourlyForecasts, today, now, displaySource, weather, dailyActuals,
            currentTemp = currentTemp,
            snapshotHigh = snapshot?.highTemp,
            snapshotLow = snapshot?.lowTemp,
            snapshotIconRes = snapshotIconRes,
        )

        val finalHigh = tripleValues.solidLineHigh ?: tripleValues.dashedLineHigh
        val finalLow = tripleValues.solidLineLow ?: tripleValues.dashedLineLow
        val fHigh = tripleValues.dashedLineHigh
        val fLow = tripleValues.dashedLineLow
        val bottomStackLow = DailyDayValueResolver.effectiveLowForLabel(
            isToday = true,
            solidLow = tripleValues.solidLineLow,
            forecastLow = tripleValues.dashedLineLow,
            nowHour = now.hour,
            actualLow = actual?.computedLowTemp,
        )
        val snapshotHigh = tripleValues.snapshotHigh
        val snapshotLow = tripleValues.snapshotLow
        val trueActualHigh = tripleValues.ghostLineHigh
        val todayHasActualLow = tripleValues.hasActualLow
        val isTodayForecastFallback =
            tripleValues.solidLineHigh == null &&
                !tripleValues.hasActualLow &&
                (finalHigh != null || finalLow != null)

        return TodayValues(
            finalHigh = finalHigh,
            finalLow = finalLow,
            fHigh = fHigh,
            fLow = fLow,
            bottomStackLow = bottomStackLow,
            snapshotHigh = snapshotHigh,
            snapshotLow = snapshotLow,
            snapshotIconRes = tripleValues.snapshotIconRes,
            trueActualHigh = trueActualHigh,
            todayHasActualLow = todayHasActualLow,
            isTodayForecastFallback = isTodayForecastFallback,
        )
    }
}
