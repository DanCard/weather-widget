package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
import com.weatherwidget.widget.handlers.HeaderConstants
import java.time.LocalDateTime

object HeaderPrecipCalculator {

    fun getNext8HourPrecipProbability(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
    ): Int? {
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        return PrecipProbabilityCalculator.getNext8HourPrecipProbability(
            sharedForecasts, displaySource.id, WeatherSource.GENERIC_GAP.id, fallbackDailyProbability, referenceTime,
        )
    }

    /** Delegates to the shared constant so the header and the daily rain labels never drift. */
    val NIGHT_SCALE = DailyRainLabels.NIGHT_SCALE

    /** Delegates to the shared step table so the header and the daily rain labels never drift. */
    fun getPrecipScaleFactor(precipProb: Int): Float =
        DailyRainLabels.precipProbabilityScaleFactor(precipProb)

    fun getPrecipTextSize(precipProb: Int): Float {
        return HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP * getPrecipScaleFactor(precipProb)
    }

    /**
     * Returns true if more than half of the probability-weighted minutes in the next 8-hour window
     * fall after sunset / before sunrise, i.e. the rain is predominantly nighttime. Delegates to
     * the shared implementation so desktop sizes the header rain chance identically.
     *
     * @param sunriseHour Sunrise in fractional 24h (from SunPositionUtils.SunTimes)
     * @param sunsetHour  Sunset  in fractional 24h (from SunPositionUtils.SunTimes)
     */
    fun isNext8HourPrecipPredominantlyNight(
        hourlyForecasts: List<HourlyForecastEntity>,
        displaySource: WeatherSource,
        referenceTime: LocalDateTime,
        sunriseHour: Double,
        sunsetHour: Double,
    ): Boolean {
        val sharedForecasts = hourlyForecasts.map { it.toHourlyForecast() }
        return PrecipProbabilityCalculator.isNext8HourPrecipPredominantlyNight(
            sharedForecasts, displaySource.id, WeatherSource.GENERIC_GAP.id, referenceTime, sunriseHour, sunsetHour,
        )
    }
}
