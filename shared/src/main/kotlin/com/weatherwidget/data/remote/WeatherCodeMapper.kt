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
}
