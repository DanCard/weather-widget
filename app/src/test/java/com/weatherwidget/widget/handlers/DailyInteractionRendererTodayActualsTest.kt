package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.fog.FogLevel
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * A widget tap's daily load ([DailyInteractionRenderer.loadData]) reads today's actuals through the
 * worker's shared composition (`DailyActualsStore` → `DailyActualsAssembler`), not a parallel loader.
 * Fold 4, 2026-11-11: worker renders drew today's NWS fog, every tap render after them dropped it
 * (plans/261111-tap-render-today-fog-shared-actuals.md).
 */
@Category(LongDuration::class)
class DailyInteractionRendererTodayActualsTest : RobolectricTest() {
    private lateinit var context: Context
    private lateinit var db: WeatherDatabase

    private val lat = 37.42
    private val lon = -122.09
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val widgetId = 392

    @Before
    fun setUp() {
        WidgetInteractionCache.clear()
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        // One NWS widget on the home screen: the tap path's active sources are the widgets' display sources.
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(context, WeatherWidgetProvider::class.java) }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(widgetId, info)
        WidgetStateManager(context).setCurrentDisplaySource(widgetId, WeatherSource.NWS)
    }

    @After
    fun tearDown() {
        WidgetInteractionCache.clear()
        db.close()
    }

    private fun at(h: Int, m: Int) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun metar(h: Int, m: Int, tempF: Float, raw: String) = ObservationEntity(
        stationId = "KNUQ", stationName = "Moffett", timestamp = at(h, m), temperature = tempF,
        condition = "", locationLat = lat, locationLon = lon, distanceKm = 3.8f, stationType = StationType.OFFICIAL,
        api = WeatherSource.NWS.id, fetchedAt = at(h, m), isMetar = true, rawMetar = raw,
    )

    private suspend fun insertHourly() {
        db.hourlyForecastDao().insertAll(
            (0..23).map { h ->
                HourlyForecastEntity(
                    dateTime = at(h, 0), locationLat = lat, locationLon = lon, temperature = 52f + h * 0.5f,
                    condition = "Clear", source = WeatherSource.NWS.id, fetchedAt = at(0, 0),
                )
            },
        )
    }

    private suspend fun load() = DailyInteractionRenderer.loadData(
        context = context,
        database = db,
        lat = lat,
        lon = lon,
        today = today,
        range = DailyInteractionRenderer.rangeFor(today, NavigationUtils.DailyLoadWindow(historyDays = 2, forecastDays = 9)),
    )

    @Test
    fun `tap load carries today's observed fog`() = runTest {
        // KNUQ 2026-11-11: dense fog at dawn, clear by 09:15. Nothing stored on daily_history for today.
        db.observationDao().insertAll(
            listOf(
                metar(5, 55, 50.0f, "METAR KNUQ 111355Z AUTO 00000KT M1/4SM FG OVC002 10/10 A3009 RMK AO2"),
                metar(7, 55, 51.8f, "METAR KNUQ 111555Z AUTO 00000KT M1/4SM FG OVC002 11/11 A3010 RMK AO2"),
                metar(9, 15, 57.2f, "METAR KNUQ 111715Z AUTO 00000KT 10SM CLR 14/11 A3012 RMK AO2"),
            ),
        )
        insertHourly()

        val actual = load().dailyActuals[WeatherSource.NWS.id]?.get(today)
        println("tap today actual=$actual fog=${actual?.observedFog}")
        assertNotNull("tap load has a live NWS today", actual)
        val fog = actual!!.observedFog
        assertNotNull("today's fog is computed live on the tap path", fog)
        assertTrue(fog!!.isFoggy)
        assertEquals(FogLevel.DENSE, fog.level)
        // Midway between 07:55 (11 °C) and the first clear report 09:15 (14 °C).
        assertEquals(54.5f, fog.topTempF!!, 0.01f)
    }

    @Test
    fun `tap load uses the live today blend, not a persisted today row`() = runTest {
        val obsTimeMs = at(16, 55)
        db.observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = "KNEAR", stationName = "Near Station", timestamp = obsTimeMs, temperature = 82.56303f,
                    condition = "Clear", locationLat = lat, locationLon = lon, distanceKm = 1f,
                    stationType = StationType.UNKNOWN, api = WeatherSource.NWS.id, fetchedAt = obsTimeMs,
                ),
            ),
        )
        insertHourly()
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY, source = WeatherSource.NWS.id,
                    locationLat = lat, locationLon = lon, computedHighTemp = 83.40072f, computedLowTemp = 62.6f,
                    condition = "Clear", updatedAt = obsTimeMs,
                ),
            ),
        )

        val actual = load().dailyActuals[WeatherSource.NWS.id]?.get(today)
        println("tap today actual=$actual")
        assertNotNull("Expected live NWS actual for today", actual)
        assertEquals("tap daily actuals ignore the persisted today row", 82.56303f, actual!!.computedHighTemp!!, 0.001f)
    }
}
