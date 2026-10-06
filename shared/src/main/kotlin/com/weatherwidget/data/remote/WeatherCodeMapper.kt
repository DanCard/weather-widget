package com.weatherwidget.data.remote

/**
 * Single source of truth for mapping provider-native weather codes to the app's condition
 * vocabulary. Both the API parsers ([OpenMeteoApi], [TomorrowIoApi]) and the Android daily-icon
 * resolver read from here, so a code can never resolve to different condition text on two paths.
 */
object WeatherCodeMapper {
    /** Open-Meteo WMO `weather_code` → condition text. */
    fun openMeteoCodeToCondition(code: Int?): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45 -> "Light Fog"
        48 -> "Dense Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow Grains"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    /** Tomorrow.io `weatherCode` → condition text. */
    fun tomorrowIoCodeToCondition(code: Int): String = when (code) {
        1000 -> "Clear"
        1100 -> "Mostly Clear"
        1101 -> "Partly Cloudy"
        1102 -> "Mostly Cloudy"
        1001 -> "Cloudy"
        2000, 2100 -> "Fog"
        4000 -> "Drizzle"
        4001, 4200 -> "Rain"
        4201 -> "Heavy Rain"
        5000, 5001, 5100, 5101 -> "Snow"
        6000, 6001, 6200, 6201 -> "Freezing Rain"
        7000, 7101, 7102 -> "Ice Pellets"
        8000 -> "Thunderstorm"
        else -> "Unknown"
    }

    /**
     * Google Weather `weatherCondition.type` enum → condition text. Covers every value in the
     * published `WeatherCondition.Type` list; matched on the enum name so a new rain/snow variant
     * still lands in the right family rather than "Unknown".
     */
    fun googleConditionToCondition(type: String?): String = when (type) {
        null, "", "TYPE_UNSPECIFIED" -> "Unknown"
        "CLEAR" -> "Clear"
        "MOSTLY_CLEAR" -> "Mostly Clear"
        "PARTLY_CLOUDY" -> "Partly Cloudy"
        "MOSTLY_CLOUDY" -> "Mostly Cloudy"
        "CLOUDY" -> "Cloudy"
        "WINDY" -> "Windy"
        "WIND_AND_RAIN" -> "Rain"
        "RAIN_AND_SNOW" -> "Freezing Rain"
        "HAIL", "HAIL_SHOWERS" -> "Ice Pellets"
        "LIGHT_RAIN_SHOWERS", "CHANCE_OF_SHOWERS", "SCATTERED_SHOWERS", "RAIN_SHOWERS" -> "Rain Showers"
        "HEAVY_RAIN_SHOWERS", "MODERATE_TO_HEAVY_RAIN", "HEAVY_RAIN", "RAIN_PERIODICALLY_HEAVY" -> "Heavy Rain"
        "LIGHT_RAIN", "LIGHT_TO_MODERATE_RAIN" -> "Light Rain"
        "RAIN" -> "Rain"
        "LIGHT_SNOW_SHOWERS", "CHANCE_OF_SNOW_SHOWERS", "SCATTERED_SNOW_SHOWERS", "SNOW_SHOWERS",
        "HEAVY_SNOW_SHOWERS" -> "Snow Showers"
        "BLOWING_SNOW", "SNOWSTORM", "HEAVY_SNOW_STORM", "HEAVY_SNOW", "MODERATE_TO_HEAVY_SNOW",
        "SNOW_PERIODICALLY_HEAVY", "LIGHT_SNOW", "LIGHT_TO_MODERATE_SNOW", "SNOW" -> "Snow"
        else -> when {
            "THUNDER" in type -> "Thunderstorm"
            "SNOW" in type -> "Snow"
            "RAIN" in type || "SHOWER" in type -> "Rain"
            "FOG" in type || "HAZE" in type -> "Fog"
            else -> "Unknown"
        }
    }
}
