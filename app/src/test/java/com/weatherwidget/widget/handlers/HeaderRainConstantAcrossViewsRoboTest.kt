package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Real Room rows → `GraphDataLoader` (now-centred header rows and the panned graph window) →
 * [DailyHeaderResolver] and [TemperatureHeaderStateBuilder]: the header rain % is the daily view's
 * value in the hourly view panned 4 days out (plans/261119-header-rain-constant-across-views.md).
 *
 * The only rain near now is 30% at +7 h (inside the 8 h lookahead, past the current-temp window's
 * +3 h), and 90% sits 4 days out. Old code: the now rows stopped at +3 h (daily showed nothing) and the
 * panned hourly view scanned from day 4 (90%).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class HeaderRainConstantAcrossViewsRoboTest {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 6161
    private val lat = 37.417
    private val lon = -122.089
    private val source = WeatherSource.NWS
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 11, 19, 10, 0)
    private val panned = now.plusDays(4).withHour(13)

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
        stateManager.setHeaderRainLookaheadHours(8)
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    private fun precipAt(t: LocalDateTime): Int = when {
        t == now.plusHours(7) -> 30
        !t.isBefore(panned.minusHours(1)) && !t.isAfter(panned.plusHours(3)) -> 90
        else -> 0
    }

    private fun seed() = runBlocking {
        val qLat = LocationMatch.quantize(lat)
        val qLon = LocationMatch.quantize(lon)
        val fetchedAt = ms(now.minusMinutes(30))
        val rows = (-14L..(5L * 24)).map { offset ->
            val t = now.plusHours(offset)
            HourlyForecastEntity(
                dateTime = ms(t), locationLat = qLat, locationLon = qLon, temperature = 55f + (offset % 5),
                condition = "Cloudy", cloudCover = 80, source = source.id, precipProbability = precipAt(t),
                fetchedAt = fetchedAt,
            )
        }
        db.hourlyForecastDao().insertAll(rows)
        db.hourlyForecastHistoryDao().insertAll(rows.map { it.copy(timestampToGroupPredictions = fetchedAt) })
    }

    @Test
    fun `panned hourly header shows the daily view's rain chance from now`() = runBlocking {
        seed()
        val input = GraphDataLoader.loadCurrentTempInputHourlyForecasts(
            db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), source, lat, lon, now,
        )
        val nowRows = GraphDataLoader.nowCentred(input, now)
        val graphRows = GraphDataLoader.loadGraphWindowHourlyForecasts(
            hourlyDao = db.hourlyForecastDao(), lat = lat, lon = lon, centerTime = panned,
            zoom = ZoomStage.WIDE.window(), now = now, source = source,
        )
        println("rows input=${input.size} nowRows=${nowRows.size} graphRows=${graphRows.size}")
        val dims = WidgetDimensions(cols = 5, rows = 2, widthDp = 400, heightDp = 180, isIconWidth = false)

        val daily = DailyHeaderResolver.resolveState(
            context = context, displaySource = source, now = now, lat = lat, lon = lon,
            weatherByDate = emptyMap(), hourlyForecasts = graphRows, currentTempHourlyForecasts = nowRows,
            lastObservedTemp = null, observedAt = null, dimensions = dims,
            stateManager = stateManager, appWidgetId = appWidgetId, numColumns = 5, numRows = 2, useGraph = true,
            sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon), deltaFromYesterday = null,
            dateOffset = 0, skipYesterday = false, rainNow = null,
        ).state.precipText

        val resolution = CurrentTempResolutionHelper.header(now, source, nowRows, null, null)
        val (_, hourlyShown) = TemperatureHeaderStateBuilder.buildHeaderState(
            currentTempResolution = resolution, deltaFromYesterday = null, centerTime = panned, now = now,
            displaySource = source, dimensions = dims, hourlyForecasts = graphRows,
            currentTempHourlyForecasts = nowRows, lat = lat, lon = lon, precipProbability = 90,
            useCelsius = false, rainNow = null, rainLookaheadHours = stateManager.getHeaderRainLookaheadHours(),
        )
        println("HEADER_RAIN daily=$daily hourlyPanned=$hourlyShown center=$panned")
        assertEquals("30%", daily)
        assertEquals(30, hourlyShown)

        // The temperature reads the same rows but stays on its own window: the wider rows change nothing.
        val observationInput = GraphDataLoader.currentTempInput(input, now)
        val window = com.weatherwidget.widget.CurrentTemperatureResolver.resolutionWindowMs(ms(now), zone)
        val narrow = CurrentTempResolutionHelper.header(now, source, nowRows.filter { it.dateTime in window }, null, null)
        println("CURR_TEMP wide=${resolution.displayTemp} narrow=${narrow.displayTemp} obsInput=${observationInput.size}")
        assertEquals(narrow.displayTemp, resolution.displayTemp)
        assertEquals(true, observationInput.all { it.dateTime <= window.last })
    }
}
