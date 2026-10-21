package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.WeatherCodeMapper
import com.weatherwidget.shared.util.WeatherConditionResolver as R
import com.weatherwidget.util.SunPositionUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * A daily forecast row's icon name: the provider's native token when it has one, else its condition
 * text; a rain icon the day and night chances both fall below their thresholds becomes a cloud-cover
 * icon; worded "partly cloudy" needs the shared noon cloud floor. Moved from Android's
 * `DailyForecastIconResolver.resolveIcon` (which now maps this name to a drawable) so the desktop
 * header's no-hourly fallback draws the same icon (`plans/261021-header-shared-content-audit.md` A2).
 */
object DailyIconName {

    @Suppress("LongParameterList")
    fun resolve(
        sourceId: String,
        nativeToken: String?,
        condition: String?,
        precipProbability: Int?,
        targetDate: LocalDate,
        now: LocalDateTime,
        latitude: Double,
        longitude: Double,
        dayPrecipProbability: Int? = null,
        nightPrecipProbability: Int? = null,
        cloudCover: Int? = null,
    ): String {
        val isNight = targetDate == now.toLocalDate() && SunPositionUtils.getSunInfoOrUnknown(now, latitude, longitude).isNight
        val daysFromToday = ChronoUnit.DAYS.between(now.toLocalDate(), targetDate).toInt()

        val token = nativeToken?.trim().orEmpty()
        val icon = token.takeIf { it.isNotEmpty() }
            ?.let { nativeTokenIcon(WeatherSource.fromId(sourceId), it, isNight, precipProbability, cloudCover) }
            ?: R.resolveIconName(
                condition = condition,
                isNight = isNight,
                cloudCover = cloudCover,
                precipProbability = precipProbability,
            )
        if (shouldSuppressRainIcon(icon, daysFromToday, dayPrecipProbability, nightPrecipProbability)) {
            return R.getCloudCoverIcon(isNight, cloudCover)
        }
        return R.applyDailyPartlyCloudyFloor(icon, cloudCover, isNight)
    }

    /** A rain icon whose day AND night chances are both known and below the day's thresholds. */
    fun shouldSuppressRainIcon(
        iconName: String,
        daysFromToday: Int,
        dayPrecipProbability: Int?,
        nightPrecipProbability: Int?,
    ): Boolean {
        if (!R.isRainIndicator(iconName)) return false
        val daySuppresses = dayPrecipProbability != null &&
            dayPrecipProbability < DailyRainLabels.getMinimumPrecipProbabilityDay(daysFromToday)
        val nightSuppresses = nightPrecipProbability != null &&
            nightPrecipProbability < DailyRainLabels.getMinimumPrecipProbabilityNight(daysFromToday)
        return daySuppresses && nightSuppresses
    }

    private fun nativeTokenIcon(
        source: WeatherSource,
        token: String,
        isNight: Boolean,
        precipProbability: Int?,
        cloudCover: Int?,
    ): String? {
        fun fromCondition(condition: String?) = condition?.let {
            R.resolveIconName(condition = it, isNight = isNight, cloudCover = cloudCover, precipProbability = precipProbability)
        }
        return when (source) {
            WeatherSource.OPEN_METEO -> token.toIntOrNull()?.let { fromCondition(WeatherCodeMapper.openMeteoCodeToCondition(it)) }
            WeatherSource.OPEN_WEATHER_MAP -> openWeatherMapIcon(token)
            WeatherSource.WEATHER_API -> weatherApiIcon(token)
            // Silurian labels near-dry days "Rain" (e.g. 5 %); its own chance turns that into cloud.
            WeatherSource.SILURIAN, WeatherSource.NWS -> fromCondition(token)
            WeatherSource.GOOGLE_WEATHER -> fromCondition(WeatherCodeMapper.googleConditionToCondition(token))
            WeatherSource.TOMORROW_IO -> token.toIntOrNull()?.let { fromCondition(WeatherCodeMapper.tomorrowIoCodeToCondition(it)) }
            // No daily forecast rows: GENERIC_GAP has no token, METAR/Synoptic are observation feeds.
            WeatherSource.GENERIC_GAP, WeatherSource.METAR, WeatherSource.SYNOPTIC, WeatherSource.VISUAL_CROSSING -> null
        }
    }

    private fun openWeatherMapIcon(token: String): String? = when (token.lowercase()) {
        "01d" -> R.IC_CLEAR
        "01n" -> R.IC_NIGHT
        "02d" -> R.IC_MOSTLY_CLEAR
        "02n" -> R.IC_PARTLY_CLOUDY_NIGHT
        "03d", "03n" -> R.IC_MOSTLY_CLOUDY
        "04d", "04n" -> R.IC_CLOUDY
        "09d", "09n", "10d", "10n" -> R.IC_RAIN
        "11d", "11n" -> R.IC_STORM
        "13d", "13n" -> R.IC_SNOW
        "50d", "50n" -> R.IC_FOG
        else -> null
    }

    private fun weatherApiIcon(token: String): String? {
        val code = token.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: return null
        return when (code) {
            1000 -> R.IC_CLEAR
            1003 -> R.IC_PARTLY_CLOUDY
            1006 -> R.IC_MOSTLY_CLOUDY
            1009 -> R.IC_CLOUDY
            1030 -> R.IC_FOG_LIGHT
            1135 -> R.IC_FOG
            1147 -> R.IC_FOG_DENSE
            1063, 1150, 1153, 1180, 1183, 1186, 1189, 1192, 1195, 1240, 1243, 1246 -> R.IC_RAIN
            1066, 1114, 1117, 1210, 1213, 1216, 1219, 1222, 1225, 1237, 1255, 1258, 1261, 1264 -> R.IC_SNOW
            1069, 1072, 1168, 1171, 1198, 1201, 1204, 1207, 1249, 1252 -> R.IC_RAIN
            1087, 1273, 1276, 1279, 1282 -> R.IC_STORM
            else -> null
        }
    }
}
