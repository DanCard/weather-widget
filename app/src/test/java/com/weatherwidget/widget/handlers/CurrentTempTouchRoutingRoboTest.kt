package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.MediumDuration
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class CurrentTempTouchRoutingRoboTest {
    private lateinit var context: Context
    private lateinit var app: Application
    private val appWidgetId = 2718
    private val zoneId = ZoneId.systemDefault()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        app = RuntimeEnvironment.getApplication()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `daily current temp touch zone toggles to temperature view`() = runBlocking {
        val views = renderDailyWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `temperature current temp touch zone toggles back to daily view`() = runBlocking {
        val views = renderTemperatureWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `daily current temp delta routes to temperature view`() = runBlocking {
        val views = renderDailyWidget(lastObservedTemp = 72.4f, precipProbability = 0)

        val intent = clickView(views, R.id.current_temp_delta)

        assertNotNull("Expected current temp delta to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `precipitation current temp touch zone routes to temperature view`() = runBlocking {
        val views = renderPrecipitationWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_SET_VIEW, intent!!.action)
        assertEquals(ViewMode.TEMPERATURE.name, intent.getStringExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW))
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `cloud cover current temp touch zone routes to temperature view`() = runBlocking {
        val views = renderCloudCoverWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetIntentRouter.ACTION_SET_VIEW, intent!!.action)
        assertEquals(ViewMode.TEMPERATURE.name, intent.getStringExtra(WidgetIntentRouter.EXTRA_TARGET_VIEW))
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    private suspend fun renderDailyWidget(
        lastObservedTemp: Float? = null,
        precipProbability: Int = 20,
    ): RemoteViews {
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
            dailyActualsBySource = sampleDailyActuals(now.toLocalDate()),
            repository = null,
            now = now,
            lastObservedTemp = lastObservedTemp,
            observedAt = now.atZone(zoneId).toInstant().toEpochMilli(),
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderTemperatureWidget(
        lastObservedTemp: Float? = null,
        precipProbability: Int = 20,
    ): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now, precipProbability),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = precipProbability,
            lastObservedTemp = lastObservedTemp,
            observedAt = now.atZone(zoneId).toInstant().toEpochMilli(),
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderPrecipitationWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.PRECIPITATION)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        PrecipViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            precipProbability = 20,
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderCloudCoverWidget(): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.CLOUD_COVER)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(graphOptions())
        val now = LocalDateTime.of(2026, 3, 27, 12, 0)
        CloudCoverViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now),
            centerTime = now,
            displaySource = WeatherSource.NWS,
            precipProbability = 20,
        )
        return appWidgetManager.second.captured
    }

    private fun clickCurrentTempZone(views: RemoteViews): android.content.Intent? {
        return clickView(views, R.id.current_temp_zone)
    }

    private fun clickView(views: RemoteViews, viewId: Int): android.content.Intent? {
        val applied = applyViews(views)
        val target = applied.findViewById<View>(viewId)
        assertNotNull("Expected tapped view to exist", target)

        val shadowApp = shadowOf(app)
        val beforeTap = shadowApp.broadcastIntents.size
        assertTrue("Expected tapped view to be visible", target.visibility == View.VISIBLE)
        target.performClick()
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

    private fun applyViews(views: RemoteViews): View {
        val root = FrameLayout(context)
        val applied = views.apply(context, root as ViewGroup)
        val widthSpec = MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(220, MeasureSpec.EXACTLY)
        applied.measure(widthSpec, heightSpec)
        applied.layout(0, 0, applied.measuredWidth, applied.measuredHeight)
        return applied
    }

    private fun textOptions(): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }

    private fun graphOptions(): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }

    private fun sampleHourlyForecasts(
        now: LocalDateTime,
        precipProbability: Int = 20,
    ): List<HourlyForecastEntity> {
        val start = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).minusHours(8)
        val fetchedAt = System.currentTimeMillis()
        return (0..24).map { index ->
            val time = start.plusHours(index.toLong())
            HourlyForecastEntity(
                dateTime = time.atZone(zoneId).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60f + index,
                condition = if (index % 3 == 0) "Cloudy" else "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = precipProbability,
                cloudCover = (30 + index).coerceAtMost(100),
                fetchedAt = fetchedAt,
            )
        }
    }

    private fun sampleDailyForecasts(today: LocalDate): List<ForecastEntity> {
        val fetchedAt = System.currentTimeMillis()
        return listOf(
            forecast(today, 68f, 52f, "Clear", fetchedAt),
            forecast(today.plusDays(1), 70f, 54f, "Cloudy", fetchedAt),
        )
    }

    private fun sampleDailyActuals(today: LocalDate): DailyActualsBySource =
        mapOf(
            WeatherSource.NWS.id to mapOf(
                today to ObservationResolver.DailyActual(
                    date = today,
                    highTemp = 67f,
                    lowTemp = 51f,
                    condition = "Clear",
                ),
            ),
        )

    private fun forecast(
        date: LocalDate,
        highTemp: Float,
        lowTemp: Float,
        condition: String,
        fetchedAt: Long,
    ) = ForecastEntity(
        targetDate = date.toEpochDay() * 86_400_000L,
        forecastDate = date.minusDays(1).toEpochDay() * 86_400_000L,
        locationLat = 37.0,
        locationLon = -122.0,
        locationName = "Test",
        highTemp = highTemp,
        lowTemp = lowTemp,
        condition = condition,
        source = WeatherSource.NWS.id,
        precipProbability = 20,
        fetchedAt = fetchedAt,
        batchFetchedAt = fetchedAt,
    )
}
