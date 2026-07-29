package com.weatherwidget.shared.history

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class ProviderHistoryPolicyTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDate.of(2026, 7, 28).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val yesterday = LocalDate.of(2026, 7, 27)

    @Test
    fun `missing WeatherAPI day requests yesterday`() {
        assertEquals(
            ProviderHistoryDecision.Fetch(yesterday, 0),
            ProviderHistoryPolicy.decide(
                WeatherSource.WEATHER_API,
                now,
                zone,
                emptyList(),
                retryAtMs = null,
            ),
        )
    }

    @Test
    fun `twenty distinct stored hours satisfy coverage`() {
        val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val timestamps = (0 until 20).map { start + it * ProviderHistoryPolicy.HOUR_MS }
        assertEquals(
            ProviderHistoryDecision.AlreadyCovered(yesterday, 20),
            ProviderHistoryPolicy.decide(
                WeatherSource.WEATHER_API,
                now,
                zone,
                timestamps + timestamps.take(3),
                retryAtMs = null,
            ),
        )
    }

    @Test
    fun `partial day respects persisted cooldown`() {
        val retryAt = now + 10_000L
        val decision = ProviderHistoryPolicy.decide(
            WeatherSource.WEATHER_API,
            now,
            zone,
            listOf(yesterday.atStartOfDay(zone).toInstant().toEpochMilli()),
            retryAtMs = retryAt,
        )
        assertEquals(ProviderHistoryDecision.Cooldown(yesterday, 1, retryAt), decision)
    }

    @Test
    fun `other sources are not routed to WeatherAPI history`() {
        val decision = ProviderHistoryPolicy.decide(
            WeatherSource.OPEN_METEO,
            now,
            zone,
            emptyList(),
            retryAtMs = null,
        )
        assertTrue(decision is ProviderHistoryDecision.NotApplicable)
    }

    @Test
    fun `failure classes have quota aware retry windows`() {
        assertEquals(
            ProviderHistoryFailureClass.AUTH_OR_PLAN,
            ProviderHistoryPolicy.failureClassForStatus(403),
        )
        assertEquals(
            ProviderHistoryFailureClass.QUOTA,
            ProviderHistoryPolicy.failureClassForStatus(429),
        )
        assertEquals(
            ProviderHistoryFailureClass.TRANSIENT,
            ProviderHistoryPolicy.failureClassForStatus(503),
        )
        assertTrue(
            ProviderHistoryPolicy.retryDelayMs(ProviderHistoryFailureClass.AUTH_OR_PLAN) >
                ProviderHistoryPolicy.retryDelayMs(ProviderHistoryFailureClass.TRANSIENT),
        )
    }
}
