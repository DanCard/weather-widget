package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.nowcast.NowcastSeries
import com.weatherwidget.shared.nowcast.NowcastSlot
import com.weatherwidget.shared.nowcast.SLOT_MS
import com.weatherwidget.shared.util.HeaderContent
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.widget.AndroidNowcast
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The daily header's rain % is the plain percent even when the 15-minute nowcast has a rain start:
 * the nowcast is model output (HRRR), not radar, and said "10am–" while it was already raining
 * (user, 2026-10-22). It stays only as the hourly precip graph's band.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyHeaderNowcastRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 4242
    private val lat = 52.52
    private val lon = 13.40

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
    }

    private fun epochMs(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun hourly(now: LocalDateTime, source: WeatherSource) =
        (-24L..48L).map { offset ->
            HourlyForecastEntity(
                dateTime = epochMs(now.withMinute(0).withSecond(0).withNano(0).plusHours(offset)),
                locationLat = lat,
                locationLon = lon,
                temperature = 55f,
                condition = "Rain",
                source = source.id,
                precipProbability = 60,
                fetchedAt = System.currentTimeMillis(),
            )
        }

    /** Dry in now's quarter and the next, then rain: a "from" at the third quarter's start. */
    private fun storeRainStartingSoon(now: LocalDateTime): Long {
        val nowMs = epochMs(now)
        val slotStart = nowMs - nowMs % SLOT_MS
        val slots = (0 until 12).map { i -> NowcastSlot(slotStart + i * SLOT_MS, if (i >= 2) 0.5f else 0f) }
        val store = AndroidNowcast.store(context)
        store.saveCoverage(lat, lon, native = true, checkedAtMs = nowMs)
        store.saveSeries(lat, lon, NowcastSeries(slots, fetchedAtMs = nowMs))
        return slotStart + 2 * SLOT_MS
    }

    private fun resolve(now: LocalDateTime, source: WeatherSource) = runBlocking {
        val rows = hourly(now, source)
        DailyHeaderResolver.resolveState(
            context = context,
            displaySource = source,
            now = now,
            lat = lat,
            lon = lon,
            weatherByDate = emptyMap(),
            hourlyForecasts = rows,
            currentTempHourlyForecasts = rows,
            lastObservedTemp = 55f,
            observedAt = epochMs(now),
            dimensions = WidgetDimensions(cols = 5, rows = 2, widthDp = 400, heightDp = 180, isIconWidth = false),
            stateManager = stateManager,
            appWidgetId = appWidgetId,
            numColumns = 5,
            numRows = 2,
            useGraph = true,
            sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon),
            deltaFromYesterday = null,
            dateOffset = 0,
            skipYesterday = false,
        ).state
    }

    @Test
    fun `the daily header logs what it shows and whose data it is`() {
        ShadowLog.clear()
        val state = resolve(LocalDateTime.now(), WeatherSource.OPEN_METEO)

        val line = ShadowLog.getLogsForTag(HeaderContent.LOG_TAG).last().msg
        assertTrue(line, "widget=$appWidgetId source=OPEN_METEO view=DAILY" in line)
        assertTrue(line, "precip=${state.precipText}" in line)
    }

    @Test
    fun `open-meteo with native coverage and rain coming keeps the plain rain percent`() {
        val now = LocalDateTime.now()
        storeRainStartingSoon(now)
        val state = resolve(now, WeatherSource.OPEN_METEO)
        assertTrue("rain % visible: ${state.precipProb}", state.isPrecipVisible)
        assertEquals("${state.precipProb}%", state.precipText)
    }
}
