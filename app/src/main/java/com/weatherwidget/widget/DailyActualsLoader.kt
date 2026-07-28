package com.weatherwidget.widget

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.util.WeatherTimeUtils
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Loads cached past daily actuals and computes today's actuals from live observation inputs.
 *
 * Split out of [WidgetIntentRouter] (2026-07-28, third-pass review N7) so DB orchestration for
 * [ObservationResolver] and [ActualsAggregator] is owned by an actuals-focused component rather
 * than the intent dispatcher. The interaction cache still controls when this loader runs.
 *
 * Today's result intentionally comes only from the time-aligned live blender. A persisted
 * `daily_history` row for today can use a different aggregation method and must not override it.
 */
object DailyActualsLoader {
    private const val HISTORY_LOOKBACK_DAYS = 30L

    suspend fun load(
        database: WeatherDatabase,
        lat: Double,
        lon: Double,
        personalStationWeight: Double = 1.0,
        now: LocalDateTime = LocalDateTime.now(),
    ): DailyActualsBySource {
        val today = now.toLocalDate()
        val zone = ZoneId.systemDefault()

        val startDate =
            today.minusDays(HISTORY_LOOKBACK_DAYS).toEpochDay() *
                WeatherTimeUtils.MILLIS_PER_DAY
        val endDate =
            today.minusDays(1).toEpochDay() *
                WeatherTimeUtils.MILLIS_PER_DAY
        val pastExtremes =
            database.dailyHistoryDao().getExtremesInRange(startDate, endDate, lat, lon)
        val pastActuals =
            ObservationResolver.extremesToDailyActualsBySource(pastExtremes, lat, lon)

        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Reach back across midnight so stations whose feed lapsed before midnight still bracket
        // today's early-morning candidates. The aggregator indexes today's result below.
        val contextObservations =
            database.observationDao()
                .getObservationsInRange(
                    todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                    tomorrowMs,
                    lat,
                    lon,
                )
                .filter { it.stationId != "NWS_BLEND" }

        val hourlyLookbackStart =
            now.minusHours(WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val hourlyLookaheadEnd =
            now.plusHours(WeatherWidgetProvider.HOURLY_GRAPH_LOOKAHEAD_HOURS)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val hourlyForecasts =
            GraphDataLoader.unifyToNearestSite(
                database.hourlyForecastDao()
                    .getHourlyForecasts(hourlyLookbackStart, hourlyLookaheadEnd, lat, lon),
                lat,
                lon,
            )
        val todayActuals =
            ObservationResolver.aggregateObservationsToDailyBySource(
                contextObservations,
                hourlyForecasts,
                lat,
                lon,
                personalStationWeight,
            )

        return ObservationResolver.mergeDailyActualsBySource(
            primary = pastActuals,
            secondary = todayActuals,
        )
    }
}
