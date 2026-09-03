package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Analyzes hourly forecast data to detect rain windows and timing.
 * Pure-Kotlin, parameterized on shared [HourlyForecast] model.
 */
object RainAnalyzer {

    private const val RAIN_PROBABILITY_THRESHOLD = 50
    private const val IMMINENT_RAIN_HOURS = 2L
    private const val DRY_GAP_HOURS = 12L

    /** Represents a continuous period of rain. */
    data class RainWindow(
        val startHour: LocalDateTime,
        val endHour: LocalDateTime,
        val maxProbability: Int,
    )

    /** Result of analyzing a day's rain forecast. */
    data class RainForecast(
        val hasRain: Boolean,
        val windows: List<RainWindow>,
        val summary: String?,
    )

    /**
     * Analyzes hourly forecasts for a specific date to determine rain timing.
     * Only returns future rain (filters out rain that has already passed).
     */
    fun analyzeDay(
        hourlyForecasts: List<HourlyForecast>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): RainForecast {
        Log.d("RainAnalyzer", "Analyzing $date, source=$source, total forecasts=${hourlyForecasts.size}")
        val zoneId = ZoneId.systemDefault()
        val dayForecasts = hourlyForecasts.filter { forecast ->
            val dt = Instant.ofEpochMilli(forecast.dateTime).atZone(zoneId).toLocalDateTime()
            val forecastDate = dt.toLocalDate()
            val isTargetDate = forecastDate == date
            val isNextDayMidnight = forecastDate == date.plusDays(1) && dt.hour == 0 && dt.minute == 0
            (isTargetDate || isNextDayMidnight) && (source == null || forecast.source == source)
        }.sortedBy { it.dateTime }

        if (dayForecasts.isEmpty()) {
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        val rainHours = dayForecasts.filter { isRainHour(it) }
        if (rainHours.isEmpty()) {
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        val futureRainHours = rainHours.filter {
            val hour = Instant.ofEpochMilli(it.dateTime).atZone(zoneId).toLocalDateTime()
            hour.isAfter(now) &&
                java.time.Duration.between(now, hour).toHours() >= IMMINENT_RAIN_HOURS
        }

        if (futureRainHours.isEmpty()) {
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        val windows = buildRainWindows(futureRainHours)
        val firstWindowStart = windows.first().startHour

        val isToday = date == now.toLocalDate()
        val isLateNight = firstWindowStart.hour >= 23 || firstWindowStart.hour < 5
        if (isToday && isLateNight) {
            return RainForecast(hasRain = true, windows = windows, summary = null)
        }

        val summary = if (hasDryGapBefore(hourlyForecasts, source, firstWindowStart)) {
            generateSummary(windows)
        } else {
            null
        }

        return RainForecast(hasRain = true, windows = windows, summary = summary)
    }

    /** Quick check if rain is expected on a given date (future rain only). */
    fun hasRain(
        hourlyForecasts: List<HourlyForecast>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        return analyzeDay(hourlyForecasts, date, source, now).hasRain
    }

    /** Gets a short summary string for rain timing on a specific date. */
    fun getRainSummary(
        hourlyForecasts: List<HourlyForecast>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): String? {
        val summary = analyzeDay(hourlyForecasts, date, source, now).summary
        Log.d("RainAnalyzer", "rain hours for $date: summary=$summary")
        return summary
    }

    private fun isRainHour(forecast: HourlyForecast): Boolean {
        if (forecast.precipProbability != null) {
            return forecast.precipProbability >= RAIN_PROBABILITY_THRESHOLD
        }
        val condition = forecast.condition.lowercase()
        return condition.contains("rain") ||
            condition.contains("drizzle") ||
            condition.contains("shower") ||
            condition.contains("thunder") ||
            condition.contains("storm")
    }

    private fun isAnyRainHour(forecast: HourlyForecast): Boolean {
        if (forecast.precipProbability != null) {
            return forecast.precipProbability > 0
        }
        val condition = forecast.condition.lowercase()
        return condition.contains("rain") ||
            condition.contains("drizzle") ||
            condition.contains("shower") ||
            condition.contains("thunder") ||
            condition.contains("storm")
    }

    private fun buildRainWindows(rainHours: List<HourlyForecast>): List<RainWindow> {
        if (rainHours.isEmpty()) return emptyList()

        val windows = mutableListOf<RainWindow>()
        val zoneId = ZoneId.systemDefault()
        var currentWindowStart = parseHour(rainHours.first().dateTime, zoneId)
        var currentWindowEnd = currentWindowStart
        var maxProbInWindow = rainHours.first().precipProbability ?: 0

        for (i in 1 until rainHours.size) {
            val hour = parseHour(rainHours[i].dateTime, zoneId)
            val prevHour = parseHour(rainHours[i - 1].dateTime, zoneId)
            val prob = rainHours[i].precipProbability ?: 0
            val hoursGap = java.time.Duration.between(prevHour, hour).toHours()

            if (hoursGap <= 2) {
                currentWindowEnd = hour
                maxProbInWindow = maxOf(maxProbInWindow, prob)
            } else {
                windows.add(RainWindow(startHour = currentWindowStart, endHour = currentWindowEnd, maxProbability = maxProbInWindow))
                currentWindowStart = hour
                currentWindowEnd = hour
                maxProbInWindow = prob
            }
        }

        windows.add(RainWindow(startHour = currentWindowStart, endHour = currentWindowEnd, maxProbability = maxProbInWindow))
        return windows
    }

    private fun generateSummary(windows: List<RainWindow>): String {
        if (windows.isEmpty()) return ""
        return formatHour(windows.first().startHour)
    }

    private fun hasDryGapBefore(
        allForecasts: List<HourlyForecast>,
        source: String?,
        windowStart: LocalDateTime,
    ): Boolean {
        val zoneId = ZoneId.systemDefault()
        val cutoff = windowStart.minusHours(DRY_GAP_HOURS)
        val recentRainBeforeWindow = allForecasts
            .filter { forecast ->
                (source == null || forecast.source == source) && isAnyRainHour(forecast)
            }
            .mapNotNull { forecast ->
                val hour = parseHour(forecast.dateTime, zoneId)
                if (hour.isBefore(windowStart)) hour else null
            }
            .maxOrNull()

        if (recentRainBeforeWindow == null) return true
        return recentRainBeforeWindow.isBefore(cutoff)
    }

    fun formatHour(dateTime: LocalDateTime): String {
        val hour = dateTime.hour
        return when {
            hour == 0 -> "12am"
            hour < 12 -> "${hour}am"
            hour == 12 -> "12pm"
            else -> "${hour - 12}pm"
        }
    }

    private fun parseHour(timestamp: Long, zoneId: ZoneId): LocalDateTime {
        return Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDateTime()
    }
}
