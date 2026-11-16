package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.AndroidRadarNowStore
import com.weatherwidget.shared.actuals.TomorrowIoActuals
import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RainNow
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.util.SunPositionUtils
import com.weatherwidget.widget.AndroidRainNow
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * Stored rows and the stored radar sample → [AndroidRainNow] → [DailyHeaderResolver] → the header
 * drawable (plans/261115-header-rain-now-dots.md): KNUQ `-RA` paints the rain-now dots over a clear
 * forecast; dry stations with no radar keep the forecast icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyHeaderRainNowRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val appWidgetId = 4343
    private val lat = 37.417
    private val lon = -122.089

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setIsTesting(true)
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(appWidgetId)
        context.getSharedPreferences("radar_now", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        WeatherDatabase.resetInstanceForTesting()
        WeatherDatabase.setIsTesting(false)
    }

    private fun epochMs(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun clearHourly(now: LocalDateTime) = (-2L..8L).map { offset ->
        HourlyForecastEntity(
            dateTime = epochMs(now.withMinute(0).withSecond(0).withNano(0).plusHours(offset)),
            locationLat = lat, locationLon = lon, temperature = 55f, condition = "Clear", cloudCover = 0,
            source = WeatherSource.NWS.id, precipProbability = 0, fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun knuq(at: Long, raw: String) = ObservationEntity(
        stationId = "KNUQ", stationName = "Moffett", timestamp = at, temperature = 54f, condition = "",
        locationLat = lat, locationLon = lon, distanceKm = 3.8f, stationType = StationType.OFFICIAL,
        api = WeatherSource.NWS.id, isMetar = true, rawMetar = raw,
    )

    private fun iconRes(now: LocalDateTime, source: WeatherSource = WeatherSource.NWS) = runBlocking {
        val rows = clearHourly(now)
        val level = AndroidRainNow.level(context, lat, lon, source, where = "test", nowMs = epochMs(now))
        println("RAIN_NOW level=$level")
        DailyHeaderResolver.resolveState(
            context = context, displaySource = source, now = now, lat = lat, lon = lon,
            weatherByDate = emptyMap(), hourlyForecasts = rows, currentTempHourlyForecasts = rows,
            lastObservedTemp = 54f, observedAt = epochMs(now),
            dimensions = WidgetDimensions(cols = 5, rows = 2, widthDp = 400, heightDp = 180, isIconWidth = false),
            stateManager = stateManager, appWidgetId = appWidgetId, numColumns = 5, numRows = 2, useGraph = true,
            sunInfo = SunPositionUtils.getSunInfoOrUnknown(now, lat, lon), deltaFromYesterday = null,
            dateOffset = 0, skipYesterday = false, rainNow = level,
        ).state.iconRes
    }

    @Test
    fun `a stored KNUQ -RA report paints the rain-now dots`() = runBlocking {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val dry = iconRes(now)
        assertNotEquals("dry forecast icon is not the dots", R.drawable.ic_weather_rain_now, dry)

        WeatherDatabase.getDatabase(context).observationDao().insertAll(
            listOf(knuq(epochMs(now.minusMinutes(5)), "KNUQ 160455Z AUTO 33004KT 2SM -RA BR OVC008 12/12 A3008 RMK AO2")),
        )
        assertEquals(R.drawable.ic_weather_rain_now, iconRes(now))
    }

    @Test
    fun `a stored green radar sample paints the dots while stations read dry`() {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        runBlocking {
            WeatherDatabase.getDatabase(context).observationDao().insertAll(
                listOf(knuq(epochMs(now.minusMinutes(5)), "KNUQ 160455Z AUTO 33004KT 10SM OVC008 12/11 A3008 RMK AO2")),
            )
        }
        assertNotEquals(R.drawable.ic_weather_rain_now, iconRes(now))
        AndroidRadarNowStore(context).write(RadarNowSample(lat, lon, epochMs(now.minusMinutes(3)), 14, 214, 20, 255))
        assertEquals(R.drawable.ic_weather_rain_now, iconRes(now))
    }

    @Test
    fun `Tomorrow io rain paints the dots only while Tomorrow io is displayed`() = runBlocking {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        WeatherDatabase.getDatabase(context).observationDao().insertAll(
            listOf(
                ObservationEntity(
                    stationId = TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID, stationName = "Tomorrow.io",
                    timestamp = epochMs(now.minusMinutes(5)), temperature = 54f, condition = "Drizzle",
                    locationLat = lat, locationLon = lon, distanceKm = 0f, stationType = StationType.OFFICIAL,
                    api = WeatherSource.TOMORROW_IO.id,
                ),
            ),
        )
        assertEquals(null, AndroidRainNow.level(context, lat, lon, WeatherSource.NWS, "test", epochMs(now)))
        assertEquals(
            RainNow.Level.SPRINKLING,
            AndroidRainNow.level(context, lat, lon, WeatherSource.TOMORROW_IO, "test", epochMs(now)),
        )
    }
}
