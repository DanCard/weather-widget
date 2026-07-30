package com.weatherwidget.shared.graph

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Platform-free x-axis and endpoint-date calculations shared by hourly graph renderers. */
object HourlyTimelineGeometry {
    data class DayLabelEndpoints(
        val today: LocalDate,
        val leftDate: LocalDate,
        val rightDate: LocalDate,
        val leftText: String,
        val rightText: String,
    )

    fun <T> computeXForTime(
        targetTime: LocalDateTime,
        items: List<T>,
        points: List<Pair<Float, Float>>,
        hourWidth: Float,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        val pairedCount = minOf(items.size, points.size)
        if (pairedCount == 0) return null

        val firstTime = dateTimeOf(items.first())
        val lastTime = dateTimeOf(items[pairedCount - 1])
        val firstX = points.first().first
        val lastX = points[pairedCount - 1].first

        if (targetTime.isBefore(firstTime)) {
            val hoursBefore = Duration.between(targetTime, firstTime).toMinutes() / 60f
            return firstX - hoursBefore * hourWidth
        }
        if (targetTime.isAfter(lastTime)) {
            val hoursAfter = Duration.between(lastTime, targetTime).toMinutes() / 60f
            return lastX + hoursAfter * hourWidth
        }

        val index = (0 until pairedCount).indexOfLast { !dateTimeOf(items[it]).isAfter(targetTime) }
        if (index < 0) return null
        val minutesOffset = Duration.between(dateTimeOf(items[index]), targetTime).toMinutes()
        return points[index].first + (minutesOffset / 60f) * hourWidth
    }

    fun <T> computeNowX(
        items: List<T>,
        points: List<Pair<Float, Float>>,
        currentTime: LocalDateTime,
        hourWidth: Float,
        isCurrentHour: (T) -> Boolean,
        dateTimeOf: (T) -> LocalDateTime,
    ): Float? {
        val currentHourIndex = items.indexOfFirst(isCurrentHour)
        if (currentHourIndex !in points.indices) return null
        val minutesOffset =
            Duration.between(dateTimeOf(items[currentHourIndex]), currentTime).toMinutes()
        return points[currentHourIndex].first + (minutesOffset / 60f) * hourWidth
    }

    fun dayLabelEndpoints(
        firstDateTime: LocalDateTime,
        lastDateTime: LocalDateTime,
        currentTime: LocalDateTime,
        locale: Locale = Locale.getDefault(),
    ): DayLabelEndpoints = DayLabelEndpoints(
        today = currentTime.toLocalDate(),
        leftDate = firstDateTime.toLocalDate(),
        rightDate = lastDateTime.toLocalDate(),
        leftText = firstDateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
        rightText = lastDateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
    )
}
