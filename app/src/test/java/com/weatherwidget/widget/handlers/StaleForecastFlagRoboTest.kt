package com.weatherwidget.widget.handlers

import android.app.Application
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ZoomStage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * All three Android hour builders flag a started hour whose forecast was fetched over 3 h before it,
 * so the renderers dim it. Desktop asleep 19:33-03:10 on 2026-11-04 drew NWS's 18:45 forecast for 3am.
 * See plans/261104-dim-stale-pre-hour-forecasts.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@Category(LongDuration::class)
class StaleForecastFlagRoboTest {
    private val zone = ZoneId.systemDefault()
    private val nowHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    /** One NWS row per hour, -8 h..+6 h, all from a single fetch [fetchedAt]. */
    private fun rows(fetchedAt: LocalDateTime) = (-8L..6L).map { offset ->
        HourlyForecastEntity(
            dateTime = ms(nowHour.plusHours(offset)),
            locationLat = 37.424,
            locationLon = -122.088,
            temperature = 55f,
            condition = "Cloudy",
            source = WeatherSource.NWS.id,
            precipProbability = 25,
            cloudCover = 50,
            cloudCoverLow = 50,
            fetchedAt = ms(fetchedAt),
        )
    }

    /** Stale = started and fetched > 3 h before the hour: with an 8 h-old fetch, nowHour-4..nowHour. */
    private fun expected(times: List<LocalDateTime>, fetchedAt: LocalDateTime) = times.map { t ->
        !t.isAfter(nowHour) && java.time.Duration.between(fetchedAt, t).toMinutes() > 180
    }

    private val zoom = ZoomStage.WIDE.window()

    @Test
    fun `asleep device - started hours from an 8 h old fetch are flagged on all three graphs`() {
        val fetchedAt = nowHour.minusHours(8)
        val data = rows(fetchedAt)

        val precip = PrecipViewHandler.buildPrecipHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom)
        val cloud = CloudCoverViewHandler.buildCloudHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom)
        val temp = buildHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom).filter { it.dateTime.minute == 0 }

        println("precip stale=${precip.filter { it.isStaleForecast }.map { it.dateTime.hour }}")
        println("cloud stale=${cloud.filter { it.isStaleForecast }.map { it.dateTime.hour }}")
        println("temp stale=${temp.filter { it.isStaleForecast }.map { it.dateTime.hour }}")

        assertEquals(expected(precip.map { it.dateTime }, fetchedAt), precip.map { it.isStaleForecast })
        assertEquals(expected(cloud.map { it.dateTime }, fetchedAt), cloud.map { it.isStaleForecast })
        assertEquals(expected(temp.map { it.dateTime }, fetchedAt), temp.map { it.isStaleForecast })
        assertEquals("the current hour is stale", true, precip.first { it.dateTime == nowHour }.isStaleForecast)
        assertEquals("the next hour is not", false, precip.first { it.dateTime == nowHour.plusHours(1) }.isStaleForecast)
    }

    @Test
    fun `awake device - a fetch 1 h before the hours flags nothing`() {
        val data = rows(nowHour.minusHours(9)).map { it.copy(fetchedAt = it.dateTime - 3_600_000L) }

        val precip = PrecipViewHandler.buildPrecipHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom)
        val cloud = CloudCoverViewHandler.buildCloudHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom)
        val temp = buildHourDataList(data, nowHour, 5, WeatherSource.NWS, zoom)

        assertEquals(emptyList<Int>(), precip.filter { it.isStaleForecast }.map { it.dateTime.hour })
        assertEquals(emptyList<Int>(), cloud.filter { it.isStaleForecast }.map { it.dateTime.hour })
        assertEquals(emptyList<Int>(), temp.filter { it.isStaleForecast }.map { it.dateTime.hour })
    }
}
