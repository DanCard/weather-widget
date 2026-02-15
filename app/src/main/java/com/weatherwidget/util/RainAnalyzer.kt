package com.weatherwidget.util

import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Analyzes hourly forecast data to detect rain windows and timing.
 */
object RainAnalyzer {

    private const val RAIN_PROBABILITY_THRESHOLD = 40

    /**
     * Represents a continuous period of rain.
     */
    data class RainWindow(
        val startHour: LocalDateTime,
        val endHour: LocalDateTime,
        val maxProbability: Int,
    )

    /**
     * Result of analyzing a day's rain forecast.
     */
    data class RainForecast(
        val hasRain: Boolean,
        val windows: List<RainWindow>,
        val summary: String?, // "2pm", "2pm–5pm", "10am, 6pm"
    )

    /**
     * Analyzes hourly forecasts for a specific date to determine rain timing.
     * Only returns future rain (filters out rain that has already passed).
     *
     * @param hourlyForecasts List of hourly forecasts
     * @param date The date to analyze
     * @param source Optional source filter (if null, uses any available source)
     * @param now Current time - used to filter out past rain (defaults to now)
     * @return RainForecast with timing information, or null if all rain is in the past
     */
    fun analyzeDay(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): RainForecast {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        Log.d("RainAnalyzer", "Analyzing $dateStr, source=$source, total forecasts=${hourlyForecasts.size}")

        // Filter to the target date and optionally by source
        val dayForecasts = hourlyForecasts.filter { forecast ->
            forecast.dateTime.startsWith(dateStr) &&
                (source == null || forecast.source == source)
        }.sortedBy { it.dateTime }

        Log.d("RainAnalyzer", "Found ${dayForecasts.size} forecasts for $dateStr (source=$source)")

        if (dayForecasts.isEmpty()) {
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        // Find hours with rain
        val rainHours = dayForecasts.filter { isRainHour(it) }
        // Verified by RainAnalyzerIntegrationTest — do not change log tag or "rain hours" text
        Log.d("RainAnalyzer", "Found ${rainHours.size} rain hours for $dateStr")

        if (rainHours.isEmpty()) {
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        // Filter out past rain - only keep future rain hours
        val futureRainHours = rainHours.filter { parseHour(it.dateTime).isAfter(now) }
        Log.d("RainAnalyzer", "Future rain hours: ${futureRainHours.size}")

        if (futureRainHours.isEmpty()) {
            // All rain is in the past
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        // Group continuous rain periods into windows
        val windows = buildRainWindows(futureRainHours)

        val summary = generateSummary(windows)

        return RainForecast(hasRain = true, windows = windows, summary = summary)
    }

    /**
     * Quick check if rain is expected on a given date (future rain only).
     */
    fun hasRain(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        return analyzeDay(hourlyForecasts, date, source, now).hasRain
    }

    /**
     * Gets a short summary string for rain timing on a specific date (future rain only).
     * Returns null if no rain expected or all rain is in the past.
     */
    fun getRainSummary(
        hourlyForecasts: List<HourlyForecastEntity>,
        date: LocalDate,
        source: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ): String? {
        return analyzeDay(hourlyForecasts, date, source, now).summary
    }

    private fun isRainHour(forecast: HourlyForecastEntity): Boolean {
        // When probability data is available, use it exclusively.
        // NWS condition text like "Slight Chance Rain Showers" contains rain keywords
        // even at 18% probability — using the text as an override would be wrong.
        if (forecast.precipProbability != null) {
            return forecast.precipProbability >= RAIN_PROBABILITY_THRESHOLD
        }

        // Fall back to condition text only when probability is not reported
        val condition = forecast.condition?.lowercase() ?: return false
        return condition.contains("rain") ||
            condition.contains("drizzle") ||
            condition.contains("shower") ||
            condition.contains("thunder") ||
            condition.contains("storm")
    }

    private fun buildRainWindows(rainHours: List<HourlyForecastEntity>): List<RainWindow> {
        if (rainHours.isEmpty()) return emptyList()

        val windows = mutableListOf<RainWindow>()
        var currentWindowStart = parseHour(rainHours.first().dateTime)
        var currentWindowEnd = currentWindowStart
        var maxProbInWindow = rainHours.first().precipProbability ?: 0

        for (i in 1 until rainHours.size) {
            val hour = parseHour(rainHours[i].dateTime)
            val prevHour = parseHour(rainHours[i - 1].dateTime)
            val prob = rainHours[i].precipProbability ?: 0

            // Check if this hour is continuous with the previous (within 1-2 hours)
            val hoursGap = java.time.Duration.between(prevHour, hour).toHours()

            if (hoursGap <= 2) {
                // Continue current window
                currentWindowEnd = hour
                maxProbInWindow = maxOf(maxProbInWindow, prob)
            } else {
                // Gap detected, save current window and start new one
                windows.add(
                    RainWindow(
                        startHour = currentWindowStart,
                        endHour = currentWindowEnd,
                        maxProbability = maxProbInWindow,
                    ),
                )
                currentWindowStart = hour
                currentWindowEnd = hour
                maxProbInWindow = prob
            }
        }

        // Don't forget the last window
        windows.add(
            RainWindow(
                startHour = currentWindowStart,
                endHour = currentWindowEnd,
                maxProbability = maxProbInWindow,
            ),
        )

        return windows
    }

    private fun generateSummary(windows: List<RainWindow>): String {
        if (windows.isEmpty()) return ""

        return if (windows.size == 1) {
            formatTimeWindow(windows.first().startHour, windows.first().endHour)
        } else {
            // Multiple windows: show start times separated by comma
            windows.joinToString(", ") { formatHour(it.startHour) }
        }
    }

    private fun formatTimeWindow(start: LocalDateTime, end: LocalDateTime): String {
        return if (start == end) {
            formatHour(start)
        } else {
            "${formatHour(start)}–${formatHour(end)}"
        }
    }

    private fun formatHour(dateTime: LocalDateTime): String {
        val hour = dateTime.hour
        return when {
            hour == 0 -> "12am"
            hour < 12 -> "${hour}am"
            hour == 12 -> "12pm"
            else -> "${hour - 12}pm"
        }
    }

    private fun parseHour(dateTimeStr: String): LocalDateTime {
        return try {
            LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            // Fallback for format "2024-01-15T14:00"
            LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        }
    }
}
