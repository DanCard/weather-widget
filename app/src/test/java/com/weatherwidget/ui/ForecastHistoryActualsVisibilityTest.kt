package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.stats.ComparisonStatistics
import io.mockk.mockk
import io.mockk.coEvery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ForecastHistoryActualsVisibilityTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun `actuals are hidden when viewing today`() {
        val todayDate = LocalDate.now()
        val today = todayDate.toString()
        val testWidgetId = 101

        val intent = Intent(context, ForecastHistoryActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, today)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, 37.0)
            putExtra(ForecastHistoryActivity.EXTRA_LON, -122.0)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }

        val controller = Robolectric.buildActivity(ForecastHistoryActivity::class.java, intent)
        val activity = controller.get()
        
        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.accuracyCalculator = mockk(relaxed = true)

        // Mock data to ensure it WOULD show if not for the date check
        val targetDateEpoch = todayDate.toEpochDay() * 86400000L
        val mockActual = ForecastEntity(
            targetDate = targetDateEpoch,
            dateOfPrediction = targetDateEpoch,
            locationLat = 37.0,
            locationLon = -122.0,
            highTemp = 80f,
            lowTemp = 60f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            fetchedAt = System.currentTimeMillis()
        )
        
        val mockAppActual = DailyHistoryEntity(
            date = targetDateEpoch,
            locationLat = 37.0,
            locationLon = -122.0,
            computedHighTemp = 81f,
            computedLowTemp = 61f,
            source = WeatherSource.NWS.id,
            condition = "Clear",
            updatedAt = System.currentTimeMillis()
        )

        coEvery { activity.forecastDao.getForecastEvolution(any(), any(), any()) } returns listOf(mockActual)
        coEvery { activity.forecastDao.getForecastForDate(any(), any(), any()) } returns mockActual
        coEvery { activity.dailyHistoryDao.getExtremesInRange(any(), any(), any(), any()) } returns listOf(mockAppActual)

        controller.setup()
        ShadowLooper.idleMainLooper()
        
        val actualsLegendCard = activity.findViewById<View>(R.id.actuals_legend_card)
        assertEquals("Actuals legend card should be GONE for today", View.GONE, actualsLegendCard.visibility)
    }

    @Test
    fun `API actual shows complete legacy fragment when nearest fragment is partial`() {
        // Regression for the on-device bug (Pixel/Samsung, 2026-08-05): the widget opens history
        // with the quantized data location, so the partial gridpoint row (apiLow null, written
        // after the minTemperature window rolled off) is the NEAREST fragment; the complete
        // legacy fragment sits a few metres away. The API actual footer must still appear.
        val yesterdayDate = LocalDate.now().minusDays(1)
        val quantizedLat = 37.417
        val quantizedLon = -122.089

        val intent = Intent(context, ForecastHistoryActivity::class.java).apply {
            putExtra(ForecastHistoryActivity.EXTRA_TARGET_DATE, yesterdayDate.toString())
            putExtra(ForecastHistoryActivity.EXTRA_LAT, quantizedLat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, quantizedLon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, WeatherSource.NWS.displayName)
        }

        val controller = Robolectric.buildActivity(ForecastHistoryActivity::class.java, intent)
        val activity = controller.get()

        // @AndroidEntryPoint injects real DAOs during onCreate (controller.setup()), overwriting
        // anything assigned earlier — so inject the mocks only AFTER setup, then re-trigger the
        // load with a day-navigation click, which re-runs loadData against the mocks.
        controller.setup()

        activity.forecastDao = mockk(relaxed = true)
        activity.dailyHistoryDao = mockk(relaxed = true)
        activity.accuracyCalculator = mockk(relaxed = true)

        fun fragment(lat: Double, lon: Double, apiHigh: Float?, apiLow: Float?) = DailyHistoryEntity(
            date = yesterdayDate.toEpochDay() * 86400000L,
            locationLat = lat,
            locationLon = lon,
            computedHighTemp = 75f,
            computedLowTemp = 60f,
            source = WeatherSource.NWS.id,
            condition = "Clear",
            updatedAt = System.currentTimeMillis(),
            apiHighTemp = apiHigh,
            apiLowTemp = apiLow,
        )
        val partialNearest = fragment(quantizedLat, quantizedLon, apiHigh = 82.0f, apiLow = null)
        val completeLegacy = fragment(37.416832, -122.089035, apiHigh = 77.2f, apiLow = 56.1f)

        coEvery { activity.forecastDao.getForecastEvolution(any(), any(), any()) } returns emptyList()
        coEvery { activity.dailyHistoryDao.getExtremesInRange(any(), any(), any(), any()) } returns
            listOf(partialNearest, completeLegacy)

        activity.findViewById<View>(R.id.prev_day_button).performClick()

        // loadData runs on Dispatchers.IO and posts back to the main looper; pump until it lands.
        val apiActualText = activity.findViewById<android.widget.TextView>(R.id.footer_api_actual_text)
        val deadline = System.currentTimeMillis() + 10_000
        while (apiActualText.text.isNullOrEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }

        val apiActualGroup = activity.findViewById<View>(R.id.footer_api_actual_group)
        assertEquals(
            "API actual group should be VISIBLE via the complete legacy fragment",
            View.VISIBLE,
            apiActualGroup.visibility,
        )
        assertEquals("NWS API actual: 77.2° / 56.1°", apiActualText.text.toString())
    }
}
