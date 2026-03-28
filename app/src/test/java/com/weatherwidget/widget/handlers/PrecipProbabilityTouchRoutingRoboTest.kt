package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class PrecipProbabilityTouchRoutingRoboTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 2718

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `daily precip probability touch zone toggles precip mode`() = runBlocking {
        val views = renderDailyWidget()
        val intent = clickPrecipProbabilityZone(views)
        assertNotNull("Expected precip probability touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_PRECIP, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `temperature precip probability touch zone toggles precip mode`() = runBlocking {
        val views = renderTemperatureWidget()
        val intent = clickPrecipProbabilityZone(views)
        assertNotNull("Expected precip probability touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_PRECIP, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `precipitation precip probability touch zone toggles precip mode`() = runBlocking {
        val views = renderPrecipitationWidget()
        val intent = clickPrecipProbabilityZone(views)
        assertNotNull("Expected precip probability touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_PRECIP, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `cloud cover precip probability touch zone toggles precip mode`() = runBlocking {
        val views = renderCloudCoverWidget()
        val intent = clickPrecipProbabilityZone(views)
        assertNotNull("Expected precip probability touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_PRECIP, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    private suspend fun renderDailyWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(textOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            weatherList = sampleDailyForecasts(now.toLocalDate()),
            forecastSnapshots = emptyMap(),
            hourlyForecasts = sampleHourlyForecasts(now),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = null,
            now = now,
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderTemperatureWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(textOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 30,
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderPrecipitationWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(textOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        PrecipViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            precipProbability = 30,
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderCloudCoverWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(textOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        CloudCoverViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 30,
        )
        return appWidgetManager.second.captured
    }

    private fun clickPrecipProbabilityZone(views: RemoteViews): android.content.Intent? {
        val applied = views.apply(context, null)
        val touchZone = applied.findViewById<View>(R.id.precip_touch_zone)
        assertNotNull("Expected precip_touch_zone to exist", touchZone)

        val shadowApp = shadowOf(app)
        val beforeTap = shadowApp.broadcastIntents.size
        touchZone.performClick()
        return shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
    }

    private fun mockWidgetManager(
        options: Bundle,
    ): Pair<AppWidgetManager, io.mockk.CapturingSlot<RemoteViews>> {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns options
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(appWidgetId, capture(viewsSlot)) } returns Unit
        return appWidgetManager to viewsSlot
    }

    private fun textOptions(): Bundle {
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
    }

    private val zoneId = java.time.ZoneId.systemDefault()

    private fun sampleHourlyForecasts(now: LocalDateTime): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        val fetchedAt = System.currentTimeMillis()
        return (0..24).map { index ->
            val time = start.plusHours(index.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(zoneId).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60f + index,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 30,
                cloudCover = 50,
                fetchedAt = fetchedAt,
            )
        }
    }

    private fun sampleDailyForecasts(today: java.time.LocalDate): List<ForecastEntity> {
        val fetchedAt = System.currentTimeMillis()
        return listOf(
            ForecastEntity(
                targetDate = today.toEpochDay() * 86_400_000L,
                forecastDate = today.minusDays(1).toEpochDay() * 86_400_000L,
                locationLat = 37.0,
                locationLon = -122.0,
                locationName = "Test",
                highTemp = 68f,
                lowTemp = 52f,
                condition = "Clear",
                precipProbability = 30,
                source = WeatherSource.NWS.id,
                fetchedAt = fetchedAt
            )
        )
    }
}
