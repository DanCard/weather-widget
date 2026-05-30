package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.DailyExtremeEntity
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
        activity.dailyExtremeDao = mockk(relaxed = true)
        activity.accuracyCalculator = mockk(relaxed = true)

        // Mock data to ensure it WOULD show if not for the date check
        val targetDateEpoch = todayDate.toEpochDay() * 86400000L
        val mockActual = ForecastEntity(
            targetDate = targetDateEpoch,
            forecastDate = targetDateEpoch,
            locationLat = 37.0,
            locationLon = -122.0,
            locationName = "Test",
            highTemp = 80f,
            lowTemp = 60f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            fetchedAt = System.currentTimeMillis()
        )
        
        val mockAppActual = DailyExtremeEntity(
            date = targetDateEpoch,
            locationLat = 37.0,
            locationLon = -122.0,
            highTemp = 81f,
            lowTemp = 61f,
            source = WeatherSource.NWS.id,
            condition = "Clear",
            updatedAt = System.currentTimeMillis()
        )

        coEvery { activity.forecastDao.getForecastEvolution(any(), any(), any()) } returns listOf(mockActual)
        coEvery { activity.forecastDao.getForecastForDate(any(), any(), any()) } returns mockActual
        coEvery { activity.dailyExtremeDao.getExtremesInRange(any(), any(), any(), any()) } returns listOf(mockAppActual)

        controller.setup()
        ShadowLooper.idleMainLooper()
        
        val actualsLegendCard = activity.findViewById<View>(R.id.actuals_legend_card)
        assertEquals("Actuals legend card should be GONE for today", View.GONE, actualsLegendCard.visibility)
    }
}
