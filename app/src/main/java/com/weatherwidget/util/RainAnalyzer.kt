package com.weatherwidget.util

import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.shared.util.RainAnalyzer as SharedRainAnalyzer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Analyzes hourly forecast data to detect rain windows and timing.
 *
 * Delegates to shared [SharedRainAnalyzer] after converting Room entities to shared models.
 */
object RainAnalyzer {

    data class RainWindow(
        val startHour: LocalDateTime,
        val endHour: LocalDateTime,
        val maxProbability: Int,
    )

    data class RainForecast(
        val hasRain: Boolean,
        val windows: List<RainWindow>,
        val summary: String?,
    )

    fun analyzeDay(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): RainForecast {
        Log.d("RainAnalyzer", "Analyzing $date, source=$source, total forecasts=${hourlyForecasts.size}")
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        val result = SharedRainAnalyzer.analyzeDay(sharedForecasts, date, source, now)
        Log.d("RainAnalyzer", "Found ${result.windows.size} rain windows for $date")
        return RainForecast(
            hasRain = result.hasRain,
            windows = result.windows.map { RainWindow(it.startHour, it.endHour, it.maxProbability) },
            summary = result.summary,
        )
    }

    fun hasRain(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        return SharedRainAnalyzer.hasRain(sharedForecasts, date, source, now)
    }

    fun getRainSummary(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): String? {
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        val summary = SharedRainAnalyzer.getRainSummary(sharedForecasts, date, source, now)
        Log.d("RainAnalyzer", "rain hours for $date: summary=$summary")
        return summary
    }
}
