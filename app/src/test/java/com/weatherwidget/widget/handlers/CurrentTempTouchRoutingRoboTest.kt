package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.WidgetConstants
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
@Config(sdk = [35])
@Category(LongDuration::class)
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

    private fun extreme(date: LocalDate, high: Float, low: Float) = DailyHistory(
        date = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
        source = WeatherSource.NWS.id,
        locationLat = 0.0,
        locationLon = 0.0,
        highTemp = high,
        lowTemp = low,
        condition = "Clear",
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `daily current temp touch zone toggles to temperature view`() = runBlocking {
        val views = renderDailyWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `temperature current temp touch zone toggles to daily view`() = runBlocking {
        val views = renderTemperatureWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `daily current temp delta routes to temperature view`() = runBlocking {
        val views = renderDailyWidget(lastObservedTemp = 72.4f, precipProbability = 0)

        // Delta text is rendered in bitmap; touch goes through the current_temp_zone overlay
        val intent = clickView(views, R.id.current_temp_zone)

        assertNotNull("Expected current temp zone to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `precipitation current temp touch zone toggles to daily view`() = runBlocking {
        val views = renderPrecipitationWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `precipitation graph selector routes to temperature view`() = runBlocking {
        val views = renderPrecipitationWidget()

        val intent = clickGraphSelector(views)

        assertNotNull("Expected graph selector to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_SET_VIEW, intent!!.action)
        assertEquals(ViewMode.TEMPERATURE.name, intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `temperature graph selector routes to cloud cover view`() = runBlocking {
        val views = renderTemperatureWidget()

        val intent = clickGraphSelector(views)

        assertNotNull("Expected graph selector to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_SET_VIEW, intent!!.action)
        assertEquals(ViewMode.CLOUD_COVER.name, intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `cloud cover graph selector routes to precipitation view`() = runBlocking {
        val views = renderCloudCoverWidget()

        val intent = clickGraphSelector(views)

        assertNotNull("Expected graph selector to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_SET_VIEW, intent!!.action)
        assertEquals(ViewMode.PRECIPITATION.name, intent.getStringExtra(WidgetActions.EXTRA_TARGET_VIEW))
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `cloud cover current temp touch zone toggles to daily view`() = runBlocking {
        val views = renderCloudCoverWidget()

        val intent = clickCurrentTempZone(views)

        assertNotNull("Expected current temp touch zone to send a broadcast", intent)
        assertEquals(WidgetActions.ACTION_TOGGLE_VIEW, intent!!.action)
        assertEquals(appWidgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun `temperature graph hides daily date header`() = runBlocking {
        val views = renderTemperatureWidget()

        assertDailyDateHeaderClearedByReapply(views)
    }

    @Test
    fun `precipitation graph hides daily date header`() = runBlocking {
        val views = renderPrecipitationWidget()

        assertDailyDateHeaderClearedByReapply(views)
    }

    @Test
    fun `cloud cover graph hides daily date header`() = runBlocking {
        val views = renderCloudCoverWidget()

        assertDailyDateHeaderClearedByReapply(views)
    }

    private suspend fun renderDailyWidget(
        lastObservedTemp: Float? = null,
        precipProbability: Int = 20,
    ): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(graphOptions())
        val now = fixtureNow()
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            weatherData = WeatherData(
                weatherList = sampleDailyForecasts(now.toLocalDate()),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = sampleHourlyForecasts(now),
                currentTemps = emptyList(),
                dailyActualsBySource = sampleDailyActuals(now.toLocalDate()),
            ),
            observationData = ObservationData(
                lastObservedTemp = lastObservedTemp,
                observedAt = now.atZone(zoneId).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )
        return appWidgetManager.second.captured
    }

    private suspend fun renderDailyGraphWidgetWithDate(): View {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(wideGraphOptions())
        val now = fixtureNow()
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            weatherData = WeatherData(
                weatherList = sampleWideDailyForecasts(now.toLocalDate()),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = sampleHourlyForecasts(now),
                currentTemps = emptyList(),
                dailyActualsBySource = sampleDailyActuals(now.toLocalDate()),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )

        return applyViews(appWidgetManager.second.captured)
    }

    private suspend fun renderTemperatureWidget(
        lastObservedTemp: Float? = null,
        precipProbability: Int = 20,
    ): RemoteViews {
        val stateManager = WidgetStateManager(context)
        stateManager.setViewMode(appWidgetId, ViewMode.TEMPERATURE)
        stateManager.setCurrentDisplaySource(appWidgetId, WeatherSource.NWS)

        val appWidgetManager = mockWidgetManager(graphOptions())
        val now = fixtureNow()
        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager.first,
            appWidgetId = appWidgetId,
            hourlyForecasts = sampleHourlyForecasts(now, precipProbability),
            currentTempHourlyForecasts = sampleHourlyForecasts(now, precipProbability),
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
        val now = fixtureNow()
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
        val now = fixtureNow()
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

    private fun clickGraphSelector(views: RemoteViews): android.content.Intent? {
        val applied = applyViews(views)
        val floatingZone = applied.findViewById<View>(R.id.graph_selector_touch_zone)
        val targetId = if (floatingZone != null && floatingZone.visibility == View.VISIBLE) {
            R.id.graph_selector_touch_zone
        } else {
            R.id.graph_selector_touch_zone_inline
        }
        return clickView(views, targetId)
    }

    private fun clickAtCoordinate(views: RemoteViews, dpX: Float, dpY: Float): android.content.Intent? {
        val applied = applyViews(views)
        val density = context.resources.displayMetrics.density
        val x = dpX * density
        val y = dpY * density

        val target = findTouchTarget(applied, x, y)
        assertNotNull("Expected to find a touch target at ($dpX, $dpY) dp", target)

        val shadowApp = shadowOf(app)
        val beforeTap = shadowApp.broadcastIntents.size
        target!!.performClick()
        return shadowApp.broadcastIntents.drop(beforeTap).lastOrNull()
    }

    private fun findTouchTarget(view: View, x: Float, y: Float): View? {
        if (view.visibility != View.VISIBLE) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    val childX = x - child.left
                    val childY = y - child.top
                    if (childX >= 0 && childX <= child.width && childY >= 0 && childY <= child.height) {
                        val target = findTouchTarget(child, childX, childY)
                        if (target != null) return target
                    }
                }
            }
        }

        return if (view.isClickable) view else null
    }

    private suspend fun assertDailyDateHeaderClearedByReapply(views: RemoteViews) {
        val applied = renderDailyGraphWidgetWithDate()
        views.reapply(context, applied)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.header_date_center).visibility)
        assertEquals(View.GONE, applied.findViewById<View>(R.id.header_date_right).visibility)
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

    private fun fixtureNow(): LocalDateTime =
        LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)

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

    private fun wideGraphOptions(): Bundle =
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
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

    private fun sampleWideDailyForecasts(today: LocalDate): List<ForecastEntity> {
        val fetchedAt = System.currentTimeMillis()
        return (0..6).map { index ->
            forecast(
                date = today.plusDays(index.toLong()),
                highTemp = 68f + index,
                lowTemp = 52f + index,
                condition = if (index % 2 == 0) "Clear" else "Cloudy",
                fetchedAt = fetchedAt,
            )
        }
    }

    private fun sampleDailyActuals(today: LocalDate): DailyActualsBySource =
        mapOf(
            WeatherSource.NWS.id to mapOf(
                today to extreme(today, 67f, 51f),
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
        dateOfPrediction = date.minusDays(1).toEpochDay() * 86_400_000L,
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
