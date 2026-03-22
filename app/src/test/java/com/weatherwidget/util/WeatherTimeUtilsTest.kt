package com.weatherwidget.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class WeatherTimeUtilsTest {

    @Test
    fun `toHourlyForecastKey uses current hour before half past`() {
        val now = LocalDateTime.of(2026, 2, 20, 10, 29)

        val key = WeatherTimeUtils.toHourlyForecastKeyMs(now)

        assertEquals(com.weatherwidget.testutil.TestData.toEpoch("2026-02-20T10:00"), key)
    }

    @Test
    fun `toHourlyForecastKey uses next hour at half past`() {
        val now = LocalDateTime.of(2026, 2, 20, 10, 30)

        val key = WeatherTimeUtils.toHourlyForecastKeyMs(now)

        assertEquals(com.weatherwidget.testutil.TestData.toEpoch("2026-02-20T11:00"), key)
    }

    @Test
    fun `toHourlyForecastKey rolls day at late night half past`() {
        val now = LocalDateTime.of(2026, 2, 20, 23, 45)

        val key = WeatherTimeUtils.toHourlyForecastKeyMs(now)

        assertEquals(com.weatherwidget.testutil.TestData.toEpoch("2026-02-21T00:00"), key)
    }
}
