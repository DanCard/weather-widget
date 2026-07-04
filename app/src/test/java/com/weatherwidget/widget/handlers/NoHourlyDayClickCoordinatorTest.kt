package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class NoHourlyDayClickCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `buildPendingMessage mentions refresh will be triggered`() {
        val dayLabel = NoHourlyDayClickCoordinator.formatDayLabel("2026-07-07")
        val message = NoHourlyDayClickCoordinator.buildPendingMessage(context, dayLabel)

        assertTrue(message.contains(dayLabel))
        assertTrue(message.contains("refresh will be triggered", ignoreCase = true))
        assertTrue(message.contains("Hourly temperature data missing", ignoreCase = true))
    }

    @Test
    fun `buildResultMessage still missing includes refresh results and end label`() {
        val dayLabel = "Tue Jul 7"
        val message =
            NoHourlyDayClickCoordinator.buildResultMessage(
                context = context,
                dayLabel = dayLabel,
                hasHourlyAfterRefresh = false,
                endLabel = "Mon Jul 6 at 6 PM",
            )

        assertTrue(message.contains("Result of refresh"))
        assertTrue(message.contains("No new hourly temperature data was able to be retrieved", ignoreCase = true))
        assertTrue(message.contains(dayLabel))
        assertTrue(message.contains("Mon Jul 6 at 6 PM"))
    }

    @Test
    fun `buildResultMessage available reports hourly data found`() {
        val dayLabel = "Tue Jul 7"
        val message =
            NoHourlyDayClickCoordinator.buildResultMessage(
                context = context,
                dayLabel = dayLabel,
                hasHourlyAfterRefresh = true,
                endLabel = null,
            )

        assertTrue(message.contains("Results of refresh"))
        assertTrue(message.contains("now available", ignoreCase = true))
        assertTrue(message.contains(dayLabel))
    }
}