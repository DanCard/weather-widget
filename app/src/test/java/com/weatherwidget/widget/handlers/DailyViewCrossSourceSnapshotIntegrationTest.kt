package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.RainAnalyzer
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.widget.DailyForecastGraphRenderer
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Regression for the Pixel 2026-10-06 paint: right after widgets switched to Google Weather (no
 * Google rows yet), the "Google" daily graph drew NWS's future-day forecasts because the snapshot
 * fallback accepted any real source. Real DailyViewHandler → DailyViewLogic → DailyColumnSource.
 * See plans/261006-daily-future-column-cross-source-snapshot-fallback.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewCrossSourceSnapshotIntegrationTest {
    private lateinit var context: Context
    private val widgetId = 4401

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(DailyForecastGraphRenderer)
    }

    @After
    fun teardown() = unmockkObject(DailyForecastGraphRenderer)

    private fun row(source: WeatherSource, date: LocalDate, hi: Float, lo: Float) = ForecastEntity(
        targetDate = dateEpoch(date.toString()),
        dateOfPrediction = dateEpoch(date.toString()),
        locationLat = 37.4168,
        locationLon = -122.0889,
        highTemp = hi,
        lowTemp = lo,
        condition = "Sunny",
        source = source.id,
        precipProbability = 0,
        fetchedAt = 1L,
    )

    private fun render(weather: List<ForecastEntity>, snapshots: List<ForecastEntity>): List<DailyForecastGraphRenderer.DayData> = runBlocking {
        val now = LocalDateTime.now().withHour(11).withMinute(0)
        val sm = WidgetStateManager(context)
        sm.clearWidgetState(widgetId)
        sm.setVisibleSourcesOrder(listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS))
        sm.setCurrentDisplaySource(widgetId, WeatherSource.GOOGLE_WEATHER)
        val awm = mockk<AppWidgetManager>()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 400)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250)
        }
        every { awm.getAppWidgetOptions(widgetId) } returns options
        every { awm.updateAppWidget(widgetId, any()) } just runs
        val days = slot<List<DailyForecastGraphRenderer.DayData>>()
        every {
            DailyForecastGraphRenderer.renderGraph(
                any(), capture(days), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), useCelsius = false,
            )
        } returns DailyForecastGraphRenderer.DailyGraphRenderResult(
            bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
            rainLabelPlacements = emptyList(),
        )
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = awm,
            appWidgetId = widgetId,
            weatherData = WeatherData(
                weatherList = weather,
                forecastSnapshots = snapshots.groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) },
                hourlyForecasts = emptyList(),
                currentTemps = emptyList(),
                dailyActualsBySource = emptyMap(),
            ),
            observationData = ObservationData(lastObservedTemp = null, observedAt = null),
            now = now,
            startupToken = null,
            stateManagerNullable = null,
            repository = null,
        )
        days.captured
    }

    @Test
    fun `a Google column never draws NWS's forecast when Google has no row yet`() {
        val today = LocalDate.now()
        val nws = listOf(
            row(WeatherSource.NWS, today, 89f, 64f),
            row(WeatherSource.NWS, today.plusDays(1), 87f, 63f),
            row(WeatherSource.NWS, today.plusDays(2), 85f, 60f),
            row(WeatherSource.NWS, today.plusDays(3), 80f, 61f),
            row(WeatherSource.NWS, today.plusDays(4), 73f, 59f),
        )

        val days = render(weather = nws, snapshots = nws)

        val nwsHighs = setOf(89f, 87f, 85f, 80f, 73f)
        days.forEach { day ->
            assertTrue(
                "${day.date} drew ${day.solidLineHigh} — another source's forecast under the Google label",
                day.solidLineHigh == null || day.solidLineHigh !in nwsHighs,
            )
        }
    }

    @Test
    fun `the selected source's own stored snapshot still fills a column`() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val days = render(
            weather = emptyList(),
            snapshots = listOf(row(WeatherSource.NWS, tomorrow, 87f, 63f), row(WeatherSource.GOOGLE_WEATHER, tomorrow, 82f, 59f)),
        )
        assertEquals(82f, days.first { it.date == tomorrow }.solidLineHigh!!, 0.01f)
    }
}
