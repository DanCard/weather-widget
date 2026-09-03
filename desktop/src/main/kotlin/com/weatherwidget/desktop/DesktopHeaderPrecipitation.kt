package com.weatherwidget.desktop

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyRainLabels
import com.weatherwidget.shared.util.PrecipProbabilityCalculator
import com.weatherwidget.util.SunPositionUtils
import java.time.LocalDateTime

internal data class DesktopHeaderPrecipitation(
    val probability: Int?,
    val fontScale: Float?,
)

internal object DesktopHeaderPrecipitationResolver {
    const val HEADER_TEMP_BASE_SP = 15f

    fun resolve(
        hourlyForecasts: List<HourlyForecast>,
        displaySource: WeatherSource,
        fallbackDailyProbability: Int?,
        referenceTime: LocalDateTime,
        latitude: Double,
        longitude: Double,
        isDailyView: Boolean,
    ): DesktopHeaderPrecipitation {
        val sunTimes = SunPositionUtils.getSunTimes(referenceTime, latitude, longitude)
        val shared = PrecipProbabilityCalculator.resolveHeaderPrecipitation(
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySource.id,
            fallbackSourceId = WeatherSource.GENERIC_GAP.id,
            fallbackDailyProbability = fallbackDailyProbability,
            referenceTime = referenceTime,
            sunriseHour = sunTimes.sunriseHour,
            sunsetHour = sunTimes.sunsetHour,
        )
        val visibleProbability = shared.probability?.takeIf { it > 0 }
        val fontScale = visibleProbability?.let {
            DailyRainLabels.headerPrecipFontScale(
                precipProbability = it,
                isDailyView = isDailyView,
                isNightPrecip = shared.isPredominantlyNight,
            )
        }
        return DesktopHeaderPrecipitation(visibleProbability, fontScale)
    }
}
