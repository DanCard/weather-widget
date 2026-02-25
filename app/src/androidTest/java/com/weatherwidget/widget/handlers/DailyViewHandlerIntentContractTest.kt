package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class DailyViewHandlerIntentContractTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun pastDayClick_buildsHistoryIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val targetDate = LocalDate.of(2030, 6, 14).toString()

        val intent =
            DailyViewHandler.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 1,
                dateStr = targetDate,
                hasRainForecast = true,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
            )

        assertEquals(WeatherWidgetProvider.ACTION_DAY_CLICK, intent.action)
        assertEquals(TEST_WIDGET_ID, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(targetDate, intent.getStringExtra("date"))
        assertTrue(intent.getBooleanExtra("isHistory", false))
        assertTrue(intent.getBooleanExtra("showHistory", false))
        assertEquals(1, intent.getIntExtra("index", -1))
        assertEquals(LAT, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.00001)
        assertEquals(LON, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0), 0.00001)
        assertEquals(WeatherSource.NWS.displayName, intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE))
        assertNull(intent.getStringExtra(WeatherWidgetProvider.EXTRA_TARGET_VIEW))
    }

    @Test
    fun futureRainyDayClick_buildsPrecipitationIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val targetDate = LocalDate.of(2030, 6, 16).toString()

        val intent =
            DailyViewHandler.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 2,
                dateStr = targetDate,
                hasRainForecast = true,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
            )

        val expectedOffset =
            DayClickHelper.calculatePrecipitationOffset(
                now = now,
                targetDay = LocalDate.parse(targetDate),
            )

        assertEquals(WeatherWidgetProvider.ACTION_DAY_CLICK, intent.action)
        assertFalse(intent.getBooleanExtra("isHistory", true))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra(WeatherWidgetProvider.EXTRA_TARGET_VIEW))
        assertEquals(expectedOffset, intent.getIntExtra(WeatherWidgetProvider.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE))
        assertEquals(LAT, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LAT, 0.0), 0.00001)
        assertEquals(LON, intent.getDoubleExtra(ForecastHistoryActivity.EXTRA_LON, 0.0), 0.00001)
        assertNull(intent.getStringExtra(ForecastHistoryActivity.EXTRA_SOURCE))
    }

    @Test
    fun todaySuppressedDisplayRainStillBuildsPrecipitationIntentContract() {
        val now = LocalDateTime.of(2030, 6, 15, 9, 0)
        val today = LocalDate.of(2030, 6, 15).toString()

        // Simulates "rain text suppressed" case by passing the raw click signal directly:
        // hasRainForecast=true should still route to precipitation for today.
        val intent =
            DailyViewHandler.buildDayClickIntent(
                context = context,
                appWidgetId = TEST_WIDGET_ID,
                dayIndex = 2,
                dateStr = today,
                hasRainForecast = true,
                lat = LAT,
                lon = LON,
                displaySource = WeatherSource.NWS,
                now = now,
            )

        assertFalse(intent.getBooleanExtra("isHistory", true))
        assertFalse(intent.getBooleanExtra("showHistory", true))
        assertEquals("PRECIPITATION", intent.getStringExtra(WeatherWidgetProvider.EXTRA_TARGET_VIEW))
        assertEquals(0, intent.getIntExtra(WeatherWidgetProvider.EXTRA_HOURLY_OFFSET, Int.MIN_VALUE))
    }

    companion object {
        private const val TEST_WIDGET_ID = 123
        private const val LAT = 37.7749
        private const val LON = -122.4194
    }
}
