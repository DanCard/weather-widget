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

    private const val RAIN_PROBABILITY_THRESHOLD = 50
    private const val IMMINENT_RAIN_HOURS = 2L
    private const val DRY_GAP_HOURS = 12L  // Rain must have stopped for 12+ hours to count as a new "start"

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
        val summary: String?, // e.g. "2pm" — only shown when rain is genuinely starting (dry gap before)
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

        // Filter to the target date and optionally by source.
        // Also include midnight (00:00) of the next day so rain windows that
        // span midnight aren't truncated at 11pm.
        val nextDateStr = date.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val midnightPrefix = "${nextDateStr}T00:00"
        val dayForecasts = hourlyForecasts.filter { forecast ->
            (forecast.dateTime.startsWith(dateStr) || forecast.dateTime.startsWith(midnightPrefix)) &&
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

        // Filter out past rain and imminent rain (within 2 hours) to reduce noise
        val futureRainHours = rainHours.filter {
            val hour = parseHour(it.dateTime)
            hour.isAfter(now) &&
                java.time.Duration.between(now, hour).toHours() >= IMMINENT_RAIN_HOURS
        }
        Log.d("RainAnalyzer", "Future rain hours (excluding imminent): ${futureRainHours.size}")

        if (futureRainHours.isEmpty()) {
            // All rain is in the past
            return RainForecast(hasRain = false, windows = emptyList(), summary = null)
        }

        // Group continuous rain periods into windows
        val windows = buildRainWindows(futureRainHours)

        // Only show summary if rain is genuinely "starting" — i.e., there's been
        // a dry gap of at least DRY_GAP_HOURS before the first window.
        // Look backward in the full forecast data (across all dates) for recent rain.
        val firstWindowStart = windows.first().startHour
        val summary = if (hasDryGapBefore(hourlyForecasts, source, firstWindowStart)) {
            generateSummary(windows)
        } else {
            Log.d("RainAnalyzer", "Suppressing summary: rain continuation (no dry gap before ${formatHour(firstWindowStart)})")
            null
        }

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
        // Only show the start hour of the first window — answer "when does rain start?"
        return formatHour(windows.first().startHour)
    }

    /**
     * Checks whether there's been a dry gap of at least [DRY_GAP_HOURS] before [windowStart].
     * Looks backward across all dates in the forecast data to detect continuations.
     */
    private fun hasDryGapBefore(
        allForecasts: List<HourlyForecastEntity>,
        source: String?,
        windowStart: LocalDateTime,
    ): Boolean {
        // Find the most recent rain hour before windowStart from all available data
        val cutoff = windowStart.minusHours(DRY_GAP_HOURS)
        val recentRainBeforeWindow = allForecasts
            .filter { forecast ->
                (source == null || forecast.source == source) && isRainHour(forecast)
            }
            .mapNotNull { forecast ->
                val hour = parseHour(forecast.dateTime)
                if (hour.isBefore(windowStart)) hour else null
            }
            .maxOrNull()  // Most recent rain hour before the window

        if (recentRainBeforeWindow == null) {
            // No prior rain at all — this is a genuine new start
            return true
        }

        // If the most recent prior rain is before the cutoff, there's been a dry gap
        return recentRainBeforeWindow.isBefore(cutoff)
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
