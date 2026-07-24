package com.weatherwidget.widget

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Category(ShortDuration::class)
class LocationHandoffPolicyTest {

    private val nowMs = Instant.parse("2026-07-24T19:00:00Z").toEpochMilli()
    private val source = WeatherSource.NWS.id

    @Test
    fun `complete visible hourly coverage promotes a candidate immediately`() {
        val result = evaluateCandidateUsability(
            forecasts = dailyForecasts(),
            hourlyForecasts = hourlyForecasts(-12..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = nowMs,
        )

        assertTrue(result.useful)
        assertEquals("complete_visible_coverage", result.reason)
    }

    @Test
    fun `future-only candidate keeps the prior location during movement grace`() {
        val result = evaluateCandidateUsability(
            forecasts = dailyForecasts(),
            hourlyForecasts = hourlyForecasts(0..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = nowMs - LocationHandoffPolicy.MOVING_GRACE_MS + 1,
        )

        assertFalse(result.useful)
        assertEquals("waiting_for_history_or_stability", result.reason)
    }

    @Test
    fun `future-only candidate becomes useful after movement grace`() {
        val result = evaluateCandidateUsability(
            forecasts = dailyForecasts(),
            hourlyForecasts = hourlyForecasts(0..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = nowMs - LocationHandoffPolicy.MOVING_GRACE_MS,
        )

        assertTrue(result.useful)
        assertEquals("forward_coverage_after_grace", result.reason)
    }

    @Test
    fun `daily-only candidate does not wait for hourly history`() {
        val result = evaluateCandidateUsability(
            forecasts = dailyForecasts(),
            hourlyForecasts = emptyList(),
            requiredSourceIds = setOf(source),
            requiresHourlyData = false,
            nowMs = nowMs,
            candidateFirstSeenMs = nowMs,
        )

        assertTrue(result.useful)
        assertEquals("daily_coverage", result.reason)
    }

    @Test
    fun `candidate without a meaningful daily range retains the prior location`() {
        val result = evaluateCandidateUsability(
            forecasts = dailyForecasts(dayCount = 1),
            hourlyForecasts = hourlyForecasts(-12..12),
            requiredSourceIds = setOf(source),
            requiresHourlyData = true,
            nowMs = nowMs,
            candidateFirstSeenMs = nowMs - LocationHandoffPolicy.MOVING_GRACE_MS,
        )

        assertFalse(result.useful)
        assertEquals("insufficient_daily_coverage", result.reason)
    }

    private fun dailyForecasts(dayCount: Int = 3): List<ForecastEntity> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
        return (0 until dayCount).map { offset ->
            ForecastEntity(
                targetDate = today.plusDays(offset.toLong()).toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                dateOfPrediction = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                locationLat = 37.4168,
                locationLon = -122.0890,
                highTemp = 75f,
                lowTemp = 55f,
                condition = "Clear",
                source = source,
            )
        }
    }

    private fun hourlyForecasts(offsets: IntRange): List<HourlyForecastEntity> =
        offsets.map { offset ->
            HourlyForecastEntity(
                dateTime = nowMs + offset * 60 * 60 * 1000L,
                locationLat = 37.4168,
                locationLon = -122.0890,
                temperature = 65f,
                condition = "Clear",
                source = source,
                fetchedAt = nowMs,
            )
        }
}
