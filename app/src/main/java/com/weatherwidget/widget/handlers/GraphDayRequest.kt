package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.DailyActualMap
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay

/**
 * Request parameter object encapsulating all inputs needed to compute daily graph days
 * across past, today, and future horizons.
 */
data class GraphDayRequest(
    val now: LocalDateTime,
    val centerDate: LocalDate,
    val today: LocalDate,
    val weatherByDate: Map<LocalDate, ForecastEntity>,
    val forecastSnapshots: Map<LocalDate, List<ForecastEntity>>,
    val numColumns: Int,
    val displaySource: WeatherSource,
    val skipYesterday: Boolean,
    val skipHistory: Boolean,
    val hourlyForecasts: List<HourlyForecastEntity>,
    val stateManager: WidgetStateManager? = null,
    val appWidgetId: Int = 0,
    val todayPrecipProbability: Int? = null,
    val dailyActuals: DailyActualMap = emptyMap(),
    val climateNormals: Map<MonthDay, Pair<Float, Float>> = emptyMap(),
    val currentTemps: List<ObservationEntity> = emptyList(),
    val currentTemp: Float? = null,
    val observedAt: Long? = null,
    val allowTodayRainChanceLabel: Boolean = false,
    val rainSummaryProvider: (List<HourlyForecastEntity>, LocalDate, String?, LocalDateTime) -> String? = DailyViewLogic::entityRainSummary,
    val todayLabel: String,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
)
