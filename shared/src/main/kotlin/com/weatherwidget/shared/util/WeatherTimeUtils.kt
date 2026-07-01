package com.weatherwidget.shared.util

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object WeatherTimeUtils {
    fun alignToNearestHourHalfUp(dateTime: LocalDateTime): LocalDateTime {
        val truncatedHour = dateTime.truncatedTo(ChronoUnit.HOURS)
        return if (dateTime.minute >= 30) truncatedHour.plusHours(1) else truncatedHour
    }
}