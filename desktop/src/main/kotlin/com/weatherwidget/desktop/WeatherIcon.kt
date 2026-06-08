package com.weatherwidget.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.weatherwidget.shared.util.WeatherConditionResolver
import com.weatherwidget.shared.util.WeatherConditionResolver.ConditionFlags

/**
 * Maps weather conditions to desktop resources.
 * Delegates pure logic to shared [WeatherConditionResolver]; maps icon names to Compose resource paths.
 */
object WeatherIcon {
    fun getIconResource(condition: String?): String {
        val iconName = WeatherConditionResolver.resolveIconName(condition)
        return "drawable/${iconName}.xml"
    }

    @Composable
    fun painter(condition: String?): Painter {
        return painterResource(getIconResource(condition))
    }

    fun getConditionFlags(condition: String?, isNight: Boolean = false): ConditionFlags {
        val iconName = WeatherConditionResolver.resolveIconName(condition, isNight = isNight)
        return WeatherConditionResolver.getConditionFlags(iconName, isNight)
    }

    fun getCloudRatio(condition: String?): Float? {
        return WeatherConditionResolver.cloudRatioFromCondition(condition)
    }

    fun isRainIndicator(iconRes: String): Boolean {
        val iconName = iconRes.removePrefix("drawable/").removeSuffix(".xml")
        return WeatherConditionResolver.isRainIndicator(iconName)
    }

    fun isCloudForecastEligible(iconRes: String): Boolean {
        val iconName = iconRes.removePrefix("drawable/").removeSuffix(".xml")
        return WeatherConditionResolver.isCloudForecastEligible(iconName)
    }

    fun resolveIconHome(iconRes: String): String {
        val iconName = iconRes.removePrefix("drawable/").removeSuffix(".xml")
        return when (WeatherConditionResolver.resolveIconHome(iconName)) {
            WeatherConditionResolver.IconHome.PRECIPITATION -> "PRECIPITATION"
            WeatherConditionResolver.IconHome.CLOUD_COVER -> "CLOUD_COVER"
            WeatherConditionResolver.IconHome.HOURLY -> "HOURLY"
        }
    }
}
