package com.weatherwidget.widget.handlers

import android.util.Log
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyDayValueResolver
import com.weatherwidget.shared.util.PartialForecastDays
import com.weatherwidget.shared.util.PriorDayForecast
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
        /** A side was last confirmed more than [PriorDayForecast.STALE_SLACK_HOURS] before its anchor — draw it dashed. */
        val snapshotIsStale: Boolean = false,
    )

    /**
     * Today's daily row, by the rule desktop shares ([PartialForecastDays.todayRow]). [batchRow]
     * is today's entry in `weatherByDate` (newest fetch, display source), or null when that
     * fetch has none (Silurian's evening batches start at tomorrow). [snapshots] are every stored
     * row for today. Only the display source's rows compete, at [batchRow]'s site when there is
     * one; GENERIC_GAP filler never stands in for today.
     */
    fun resolveTodayRow(
        batchRow: ForecastEntity?,
        snapshots: List<ForecastEntity>,
        displaySourceId: String,
    ): ForecastEntity? =
        PartialForecastDays.todayRow(
            batchRow,
            snapshots.filter {
                it.source == displaySourceId &&
                    (batchRow == null || LocationMatch.sameSite(batchRow.locationLat, batchRow.locationLon, it.locationLat, it.locationLon))
            },
            { it.highTemp },
            { it.lowTemp },
            { it.fetchedAt },
        )

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
        // "Yesterday's forecast" (left bar): low from the newest fetch before 06:00 yesterday, high
        // from the newest before 16:00 yesterday — shared with desktop and the history freeze.
        val zone = ZoneId.systemDefault()
        val snapshotCandidates = forecasts.filter {
            it.source == displaySource.id && !it.isClimateNormal && it.source != WeatherSource.GENERIC_GAP.id
        }
        val pick = PriorDayForecast.select(
            snapshotCandidates, date, zone,
            fetchedAt = { it.fetchedAt }, high = { it.highTemp }, low = { it.lowTemp },
            fallbackToEarliest = true,
        )
        // The bar's condition colour and icon describe the daytime, so they follow the high's row.
        val snapshot = pick.highRow ?: pick.lowRow

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
            snapshotHigh = pick.highRow?.highTemp,
            snapshotLow = pick.lowRow?.lowTemp,
            snapshotIconRes = snapshotIconRes,
        )

        // Bar top (Android DayData.solidLineHigh): mercury ?: dashed forecast high — shared
        // TodayLineValues.barTopHigh so desktop draws the same thermostat top.
        val finalHigh = tripleValues.barTopHigh
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
            snapshotIsStale = isSnapshotStale(pick, date, zone),
        )
    }

    /**
     * Judged from [ForecastEntity.batchFetchedAt], the last fetch that returned these values:
     * `ForecastSnapshotStore` skips writing an unchanged re-fetch and only re-stamps the existing
     * row's batch, so [ForecastEntity.fetchedAt] is the first sighting.
     */
    /**
     * Dashed when either side's row was last confirmed more than a day before its anchor. Judged
     * from `batchFetchedAt`, which the dedup skip path re-stamps — `fetchedAt` is when a value was
     * first seen and would age an unchanging forecast into "stale" while it is being re-confirmed.
     */
    fun isSnapshotStale(pick: PriorDayForecast.Pick<ForecastEntity>, date: LocalDate, zone: ZoneId): Boolean =
        pick.highRow?.let { PriorDayForecast.isStale(it.batchFetchedAt, PriorDayForecast.highCutoffMs(date, zone)) } == true ||
            pick.lowRow?.let { PriorDayForecast.isStale(it.batchFetchedAt, PriorDayForecast.lowCutoffMs(date, zone)) } == true
}
