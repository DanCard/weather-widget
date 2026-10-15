package com.weatherwidget.widget.handlers

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.PreferredSourceHome
import com.weatherwidget.shared.util.RainAnalyzer
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The daily home button on the preferred source: offered while today is panned off screen, and its
 * offset ([PreferredSourceHome.homeDateOffset]) paints yesterday then today. Real
 * [DailyViewHandler] paint → header resolver → `setupDailyHomeShortcut` → RemoteViews click.
 * See plans/261015-daily-home-button-recentres-today.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyHomeRecenterTodayIntegrationTest {

    private lateinit var context: Context
    private val now = LocalDateTime.of(2026, 10, 15, 12, 0)

    private class Paint(val dates: List<LocalDate>, val todayIndex: Int, val views: RemoteViews)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ActiveLocationResolver.persist(context, 37.7749, -122.4194)
        mockkObject(RainAnalyzer)
        mockkObject(DailyForecastGraphRenderer)
    }

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
        unmockkObject(DailyForecastGraphRenderer)
    }

    @Test
    fun `preferred source panned past today offers recenter`() = runBlocking {
        for (widthDp in listOf(270, 570)) {
            val paint = paint(widthDp, offset = 6, displayed = WeatherSource.NWS)
            assertEquals("widthDp=$widthDp: today is off screen", -1, paint.todayIndex)
            assertEquals("widthDp=$widthDp", WidgetActions.ACTION_RECENTER_TODAY, homeTapAction(paint.views))
        }
    }

    @Test
    fun `preferred source panned into history offers recenter`() = runBlocking {
        val paint = paint(widthDp = 270, offset = -8, displayed = WeatherSource.NWS)
        assertEquals(-1, paint.todayIndex)
        assertEquals(WidgetActions.ACTION_RECENTER_TODAY, homeTapAction(paint.views))
    }

    @Test
    fun `preferred source with today on screen hides the home button`() = runBlocking {
        val paint = paint(widthDp = 270, offset = 0, displayed = WeatherSource.NWS)
        assertEquals(0, paint.todayIndex)
        assertEquals(View.GONE, applied(paint.views).findViewById<View>(R.id.home_touch_zone).visibility)
    }

    @Test
    fun `non-preferred source still resets the source, today off screen or not`() = runBlocking {
        for (offset in listOf(0, 6)) {
            val paint = paint(widthDp = 570, offset = offset, displayed = WeatherSource.OPEN_METEO)
            assertEquals("offset=$offset", WidgetActions.ACTION_RESET_SOURCE, homeTapAction(paint.views))
        }
    }

    @Test
    fun `home offset paints yesterday then today and hides the button`() = runBlocking {
        // 270dp → 5 columns (narrow: home offset −1); 570dp → 10 columns (wide: home offset 0).
        for (widthDp in listOf(270, 570)) {
            val probe = paint(widthDp, offset = 6, displayed = WeatherSource.NWS)
            val homeOffset = PreferredSourceHome.homeDateOffset(probe.dates.size)
            val paint = paint(widthDp, offset = homeOffset, displayed = WeatherSource.NWS)
            val today = now.toLocalDate()
            assertEquals("widthDp=$widthDp first column", today.minusDays(1), paint.dates.first())
            assertEquals("widthDp=$widthDp today column", 1, paint.todayIndex)
            assertEquals(
                "widthDp=$widthDp: button gone once today is back",
                View.GONE,
                applied(paint.views).findViewById<View>(R.id.home_touch_zone).visibility,
            )
        }
    }

    private fun applied(views: RemoteViews): View = views.apply(context, FrameLayout(context))

    private fun homeTapAction(views: RemoteViews): String {
        val root = applied(views)
        val zone = root.findViewById<View>(R.id.home_touch_zone)
        assertEquals("home touch zone visible", View.VISIBLE, zone.visibility)
        val app = RuntimeEnvironment.getApplication() as Application
        val before = shadowOf(app).broadcastIntents.size
        zone.performClick()
        val intent = shadowOf(app).broadcastIntents.drop(before).lastOrNull()
        assertNotNull("home tap broadcasts", intent)
        return intent!!.action!!
    }

    private suspend fun paint(widthDp: Int, offset: Int, displayed: WeatherSource): Paint {
        val widgetId = 700 + widthDp + offset
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setCurrentDisplaySource(widgetId, displayed)
        stateManager.setDateOffset(widgetId, offset)

        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(widgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        val viewsSlot = slot<RemoteViews>()
        every { appWidgetManager.updateAppWidget(widgetId, capture(viewsSlot)) } returns Unit

        var days: List<DailyForecastGraphRenderer.DayData> = emptyList()
        every {
            DailyForecastGraphRenderer.renderGraph(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), useCelsius = false,
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            days = args[1] as List<DailyForecastGraphRenderer.DayData>
            DailyForecastGraphRenderer.DailyGraphRenderResult(
                bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
                rainLabelPlacements = emptyList(),
            )
        }

        val today = now.toLocalDate()
        val weatherList = (-20L..20L).flatMap { d ->
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO).map { forecast(today.plusDays(d), it) }
        }
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = widgetId,
            weatherData = WeatherData(
                weatherList = weatherList,
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
            ),
            observationData = ObservationData(),
            now = now,
            startupToken = null,
            stateManagerNullable = stateManager,
            repository = null,
        )
        check(days.isNotEmpty()) { "renderGraph was not called for widthDp=$widthDp offset=$offset" }
        return Paint(days.map { it.date }, days.indexOfFirst { it.isToday }, viewsSlot.captured)
    }

    private fun forecast(date: LocalDate, source: WeatherSource): ForecastEntity {
        val iso = date.toString()
        return ForecastEntity(
            targetDate = dateEpoch(iso),
            dateOfPrediction = dateEpoch(iso),
            locationLat = 37.7749,
            locationLon = -122.4194,
            highTemp = 75f,
            lowTemp = 55f,
            condition = "Clear",
            source = source.id,
            precipProbability = 0,
            fetchedAt = 1L,
        )
    }
}
