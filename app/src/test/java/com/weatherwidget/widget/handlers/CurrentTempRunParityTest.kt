package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.widget.CurrentTempRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Android's two layers — one observation per paint ([CurrentTempResolver.observeFromRows]), then each
 * view's header ([CurrentTempResolutionHelper.header]) — give the number desktop's single
 * [CurrentTempRun.run] gives, on the same rows (plans/261024-shared-current-temp-run.md, phase 2).
 */
@Category(ShortDuration::class)
class CurrentTempRunParityTest {
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 24, 14, 20)
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val source = WeatherSource.NWS

    @Before
    fun resetCache() {
        ActualsAggregator.blendCache.clear()
    }

    /** Two stations every 20 min over the last 36 h, a degree apart, on a daily swing. */
    private val observations: List<ObservationEntity> =
        (0..(36 * 3)).flatMap { step ->
            val ts = nowMs - step * 20L * 60_000
            val swing = 8f * kotlin.math.sin(step / 36.0 * Math.PI).toFloat()
            listOf(
                TestData.observation(stationId = "KAAA", timestamp = ts, temperature = 60f + swing, distanceKm = 3f, fetchedAt = ts),
                TestData.observation(stationId = "KBBB", timestamp = ts, temperature = 61f + swing, distanceKm = 9f, fetchedAt = ts),
            )
        }

    /** A forecast that rises then steepens across now. */
    private val hourly: List<HourlyForecastEntity> =
        (-40..12).map { offset ->
            val t = now.withMinute(0).plusHours(offset.toLong())
            val temp = if (offset < 0) 62f + offset * 0.2f else 62f + offset * 1.5f
            TestData.hourly(dateTime = t.toString(), source = source.id, temperature = temp, fetchedAt = nowMs - 3_600_000)
        }

    @Test
    fun `Android observe then header equals desktop run`() {
        val observed = CurrentTempResolver.observeFromRows(
            observations = observations,
            hourlyForecasts = hourly,
            displaySource = source,
            lat = TestData.LAT,
            lon = TestData.LON,
            now = now,
        )
        assertNotNull(observed)
        // Each view hands the header its now-centred list (WidgetRenderer's nowCenteredHourlyForecasts).
        val header = CurrentTempResolutionHelper.header(
            now = now,
            displaySource = source,
            hourlyForecasts = GraphDataLoader.nowCentred(hourly, now),
            lastObservedTemp = observed!!.temperature,
            observedAt = observed.observedAt,
        )

        val desktop = CurrentTempRun.run(
            hourly = hourly.map { it.toHourlyForecast() },
            observations = observations.map { it.toReading() },
            displaySource = source,
            latitude = TestData.LAT,
            longitude = TestData.LON,
            nowMs = nowMs,
            personalStationWeight = 1.0,
            zoneId = zone,
        )

        assertNotNull(desktop.displayTemp)
        assertNotNull(desktop.deltaFromYesterday)
        assertEquals(desktop.observedTemp, observed.temperature)
        assertEquals(desktop.observedAt, observed.observedAt)
        assertEquals(desktop.deltaFromYesterday, observed.deltaFromYesterday)
        assertEquals(desktop.appliedDelta, header.appliedDelta)
        assertEquals(desktop.displayTemp, header.displayTemp)
    }
}
