package com.weatherwidget.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object WeatherTimeUtils {
    private val HOURLY_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime {
        val truncatedHour = dateTime.truncatedTo(ChronoUnit.HOURS)
        return if (dateTime.minute >= 30) truncatedHour.plusHours(1) else truncatedHour
    }

    fun toHourlyForecastKey(dateTime: LocalDateTime): String {
        return alignToNearestHourHalfUp(dateTime).format(HOURLY_KEY_FORMATTER)
    }
}
