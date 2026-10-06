package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.RainAnalyzer
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Midnight rollover through the real daily paint: [DailyViewHandler] → `NavigationUtils` window →
 * `DailyGraphRenderer` day assembly → the [DailyForecastGraphRenderer] call. One minute either side
 * of midnight, the Today column must sit at the same index (and keep the large-Today overlay
 * decision), with every column's date advanced by exactly one day.
 *
 * The 08:00 narrow skip-yesterday switch broke this: at midnight the dates froze and the Today
 * highlight moved one column right. See plans/261002-today-column-fixed-position-across-midnight.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHandlerMidnightRolloverIntegrationTest {

    private lateinit var context: Context

    private data class Paint(val dates: List<LocalDate>, val todayIndex: Int, val largeTodayOverlay: Boolean)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(RainAnalyzer)
        mockkObject(DailyForecastGraphRenderer)
    }

    @After
    fun teardown() {
        unmockkObject(RainAnalyzer)
        unmockkObject(DailyForecastGraphRenderer)
    }

    @Test
    fun `today column keeps its position across midnight`() = runBlocking {
        val before = LocalDateTime.of(2026, 10, 2, 23, 59)
        val after = LocalDateTime.of(2026, 10, 3, 0, 1)

        // 270dp → 5 columns (narrow, today-first); 570dp → 10 columns (wide, yesterday-first).
        // 300dp tall → graph mode with room for the large-Today overlay where the policy allows it.
        for (widthDp in listOf(270, 570)) {
            for (offset in listOf(-3, 0, 3)) {
                val a = paint(widthDp, offset, before)
                val b = paint(widthDp, offset, after)
                val label = "widthDp=$widthDp offset=$offset"
                assertEquals("$label: Today column index", a.todayIndex, b.todayIndex)
                assertEquals("$label: every date advances one day", a.dates.map { it.plusDays(1) }, b.dates)
                assertEquals("$label: large-Today overlay", a.largeTodayOverlay, b.largeTodayOverlay)
            }
        }
    }

    @Test
    fun `narrow widget paints today in the first column before 8am`() = runBlocking {
        // The hour the old rule still showed yesterday first.
        val paint = paint(widthDp = 270, offset = 0, now = LocalDateTime.of(2026, 10, 3, 6, 17))
        assertEquals("Today is the first column", 0, paint.todayIndex)
        assertEquals(LocalDate.of(2026, 10, 3), paint.dates.first())
    }

    private suspend fun paint(widthDp: Int, offset: Int, now: LocalDateTime): Paint {
        val widgetId = 300 + widthDp + offset
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))
        stateManager.setDateOffset(widgetId, offset)

        val appWidgetManager = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        }
        every { appWidgetManager.getAppWidgetOptions(widgetId) } returns options
        every { appWidgetManager.updateAppWidget(widgetId, any()) } just runs

        var captured: Paint? = null
        every {
            DailyForecastGraphRenderer.renderGraph(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), useCelsius = false,
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val days = args[1] as List<DailyForecastGraphRenderer.DayData>
            captured = Paint(
                dates = days.map { it.date },
                todayIndex = days.indexOfFirst { it.isToday },
                largeTodayOverlay = args[17] as Boolean,
            )
            DailyForecastGraphRenderer.DailyGraphRenderResult(
                bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888),
                rainLabelPlacements = emptyList(),
            )
        }

        // Forecast rows well past both edges of every window this test draws.
        val today = now.toLocalDate()
        val weatherList = (-20L..20L).map { forecast(today.plusDays(it)) }

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

        val paint = checkNotNull(captured) { "renderGraph was not called for widthDp=$widthDp offset=$offset now=$now" }
        assertTrue("dates are consecutive", paint.dates.zipWithNext().all { (x, y) -> y == x.plusDays(1) })
        return paint
    }

    private fun forecast(date: LocalDate): ForecastEntity {
        val iso = date.toString()
        return ForecastEntity(
            targetDate = dateEpoch(iso),
            dateOfPrediction = dateEpoch(iso),
            locationLat = 37.7749,
            locationLon = -122.4194,
            highTemp = 75f,
            lowTemp = 55f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = 1L,
        )
    }
}
