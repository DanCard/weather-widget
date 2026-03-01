package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureViewHandlerCenterTimeTest {

    @Test
    fun `updateWidget uses now for header temp while keeping centerTime day label`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val widgetId = 777
        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val appWidgetManager = mockk<AppWidgetManager>()
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 260)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
            }
        every { appWidgetManager.getAppWidgetOptions(widgetId) } returns options
        val viewsSlot = slot<android.widget.RemoteViews>()
        every { appWidgetManager.updateAppWidget(widgetId, capture(viewsSlot)) } just runs

        val now = LocalDateTime.now()
        val nowHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val nextHour = nowHour.plusHours(1)
        val hourFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
        val centerTime = now.plusDays(2).withHour(8).withMinute(20)
        val hourly =
            listOf(
                // "Now" points: expected header temp should come from these.
                hourly(nowHour.format(hourFormatter), 66f),
                hourly(nextHour.format(hourFormatter), 66f),
                // Future center points: if center-time semantics return, this test should fail.
                hourly(centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS).format(hourFormatter), 52f),
                hourly(centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1).format(hourFormatter), 58f),
            )

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = widgetId,
            hourlyForecasts = hourly,
            centerTime = centerTime,
            displaySource = com.weatherwidget.data.model.WeatherSource.NWS,
            precipProbability = 0,
        )

        val root = FrameLayout(context)
        val applied = viewsSlot.captured.apply(context, root as ViewGroup)
        val currentTempText = applied.findViewById<TextView>(R.id.current_temp).text.toString()
        val sourceText = applied.findViewById<TextView>(R.id.api_source).text.toString()

        assertEquals("66.0°", currentTempText)
        val expectedDay = centerTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        assertEquals("$expectedDay • NWS", sourceText)
        assertTrue(sourceText.contains("NWS"))
    }

    private fun hourly(dateTime: String, temp: Float): HourlyForecastEntity {
        return HourlyForecastEntity(
            dateTime = dateTime,
            locationLat = 37.42,
            locationLon = -122.08,
            temperature = temp,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}
