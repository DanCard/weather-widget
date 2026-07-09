package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

/**
 * Robolectric test to verify the intent building logic for daily forecast clicks,
 * specifically for historical days.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
class DailyHistoryClickIntentRoboTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testWidgetId = 8883

    @Test
    fun `full flow - clicking first column when offset is -2 results in correct historical offset`() {
        val now = LocalDateTime.of(2024, 6, 15, 14, 0)
        val today = now.toLocalDate()
        val dateOffset = -2
        val centerDate = today.plusDays(dateOffset.toLong()) // June 13
        
        // NavigationUtils.getDayOffsets(5, false) -> [-1, 0, 1, 2, 3]
        // days[0].date = June 13 - 1 = June 12 (3 days ago)
        
        val displaySource = WeatherSource.NWS
        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = centerDate,
            today = today,
            weatherByDate = emptyMap(),
            forecastSnapshots = emptyMap(),
            numColumns = 5,
            displaySource = displaySource,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = emptyList()
        )

        assertEquals(5, days.size)
        val firstDay = days[0]
        assertEquals(LocalDate.of(2024, 6, 12), firstDay.date)
        assertEquals(0, firstDay.columnIndex)

        val intent = DailyClickHandlerFactory.buildDayClickIntent(
            context = context,
            appWidgetId = testWidgetId,
            dayIndex = (firstDay.columnIndex ?: 0) + 1,
            date = firstDay.date,
            iconRes = firstDay.iconRes,
            lat = 0.0,
            lon = 0.0,
            displaySource = displaySource,
            now = now
        )

        // June 12th noon is -74 hours from June 15th 2pm.
        val expectedOffset = -74
        assertEquals("Offset for June 12 (3 days ago) relative to June 15 2pm should be -74", 
            expectedOffset, intent.getIntExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0))
    }

    private fun assertTrue(message: String, condition: Boolean) {
        assertEquals(message, true, condition)
    }
}
