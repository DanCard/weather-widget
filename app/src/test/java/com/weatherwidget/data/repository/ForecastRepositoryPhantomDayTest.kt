package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class ForecastRepositoryPhantomDayTest {

    private val mapper = NwsForecastMapper(mockk<NwsApi>(), mockk<AppLogDao>(relaxed = true))

    @Test
    fun `keeps last future date with null high and valid low`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
        assertEquals(65f, map["2026-04-04"]!!.second)
    }

    @Test
    fun `keeps future date with both high and low`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (72f to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
        assertEquals(72f, map["2026-04-04"]!!.first)
    }

    @Test
    fun `keeps today with null high`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-03-28" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
    }

    @Test
    fun `keeps past date with null high`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-03-27" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
    }

    @Test
    fun `removes earlier phantom day but keeps final low only future day`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (null to 65f),
            "2026-04-05" to (null to 62f),
            "2026-03-30" to (75f to 55f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(2, map.size)
        assertTrue(map.containsKey("2026-04-05"))
        assertTrue(map.containsKey("2026-03-30"))
        assertTrue(!map.containsKey("2026-04-04"))
    }

    @Test
    fun `no-op on empty map`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `applyForecastPeriods maps terminal night period to friday low only`() {
        val acc = NwsForecastMapper.NwsDayAccumulator()

        mapper.applyForecastPeriods(
            forecastPeriods = listOf(
                NwsApi.ForecastPeriod(
                    name = "Thursday",
                    startTime = "2026-04-23T06:00:00-07:00",
                    endTime = "2026-04-23T18:00:00-07:00",
                    temperature = 70,
                    temperatureUnit = "F",
                    shortForecast = "Sunny",
                    isDaytime = true,
                ),
                NwsApi.ForecastPeriod(
                    name = "Thursday Night",
                    startTime = "2026-04-23T18:00:00-07:00",
                    endTime = "2026-04-24T06:00:00-07:00",
                    temperature = 48,
                    temperatureUnit = "F",
                    shortForecast = "Mostly Clear",
                    isDaytime = false,
                ),
            ),
            todayDateString = "2026-04-17",
            acc = acc,
        )

        assertEquals(70f, acc.temperatureMap["2026-04-23"]?.first)
        assertEquals(48f, acc.temperatureMap["2026-04-24"]?.second)
        assertEquals(null, acc.temperatureMap["2026-04-24"]?.first)
    }
}
