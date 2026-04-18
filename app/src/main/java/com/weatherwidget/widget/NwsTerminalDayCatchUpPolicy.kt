package com.weatherwidget.widget

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

object NwsTerminalDayCatchUpPolicy {
    val WINDOW_START = LocalTime.of(18, 15)
    val WINDOW_END = LocalTime.of(19, 30)
    const val BASE_INTERVAL_MINUTES = 15L
    const val JITTER_MINUTES = 3L

    fun isInCatchUpWindow(now: LocalTime = LocalTime.now()): Boolean {
        return !now.isBefore(WINDOW_START) && now.isBefore(WINDOW_END)
    }

    fun shouldScheduleCatchUp(
        isCharging: Boolean,
        isScreenInteractive: Boolean,
        isInWindow: Boolean,
    ): Boolean = isCharging && isScreenInteractive && isInWindow

    fun detectTerminalDayMissingHigh(
        forecasts: List<ForecastEntity>,
        today: LocalDate = LocalDate.now(),
    ): TerminalDayInfo? {
        val nwsFutureForecasts = forecasts
            .filter { it.source == WeatherSource.NWS.id }
            .filter { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY).isAfter(today) }

        if (nwsFutureForecasts.isEmpty()) return null

        val furthestFutureDate = nwsFutureForecasts.maxOf {
            LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY)
        }

        val terminalDayForecast = nwsFutureForecasts.first {
            LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) == furthestFutureDate
        }

        return if (terminalDayForecast.highTemp == null && terminalDayForecast.lowTemp != null) {
            TerminalDayInfo(
                date = furthestFutureDate,
                lowTemp = terminalDayForecast.lowTemp,
            )
        } else {
            null
        }
    }

    fun computeJitteredDelay(
        baseMinutes: Long = BASE_INTERVAL_MINUTES,
        random: Random = Random.Default,
    ): Long {
        val jitter = random.nextLong(-JITTER_MINUTES, JITTER_MINUTES + 1)
        val totalMinutes = (baseMinutes + jitter).coerceAtLeast(1)
        return totalMinutes * 60_000L
    }

    fun computeInitialDelay(
        now: LocalTime = LocalTime.now(),
        random: Random = Random.Default,
    ): Long {
        if (!now.isBefore(WINDOW_START)) return 0L
        val minutesUntilWindow = java.time.Duration.between(now, WINDOW_START).toMinutes()
        val jitterMinutes = random.nextLong(0, 6)
        return (minutesUntilWindow + jitterMinutes) * 60_000L
    }
}

data class TerminalDayInfo(
    val date: LocalDate,
    val lowTemp: Float,
)

data class CatchUpDecision(
    val isNeeded: Boolean,
    val terminalDayInfo: TerminalDayInfo?,
    val reason: String,
)
