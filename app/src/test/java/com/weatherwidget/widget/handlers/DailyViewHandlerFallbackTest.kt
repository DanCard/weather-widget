package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.mockAppWidgetManager
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class DailyViewHandlerFallbackTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `updateWidget today preserves incomplete NWS forecast instead of falling back to Generic`() = runBlocking {
        val now = LocalDateTime.of(2026, 5, 12, 22, 0)
        val today = now.toLocalDate()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // NWS is incomplete (low is null)
        val nwsToday = createWeather(todayStr, source = WeatherSource.NWS.id, high = 76f, low = null)
        // Generic (climate normal) is complete
        val genericToday = createWeather(todayStr, source = WeatherSource.GENERIC_GAP.id, high = 61f, low = 47f)

        val weatherList = listOf(nwsToday, genericToday)

        val stateManager = mockk<WidgetStateManager>(relaxed = true)
        every { stateManager.getCurrentDisplaySource(any()) } returns WeatherSource.NWS
        every { stateManager.getViewMode(any()) } returns com.weatherwidget.widget.ViewMode.DAILY
        every { stateManager.getDateOffset(any()) } returns 0
        every { stateManager.getWidgetLocation(any()) } returns null

        val captured = mockAppWidgetManager(widgetId = 1, heightDp = 200)
        
        val db = WeatherDatabase.getDatabase(context)
        db.appLogDao().clearAllLogs()
        
        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = captured.appWidgetManager,
            appWidgetId = 1,
            weatherList = weatherList,
            forecastSnapshots = emptyMap(),
            hourlyForecasts = emptyList(),
            currentTemps = emptyList(),
            dailyActualsBySource = emptyMap(),
            repository = mockk(relaxed = true),
            now = now,
            stateManager = stateManager
        )

        val logs = db.appLogDao().getLogsByTag("TODAY_BAR_DEBUG", limit = 100)
        val todayLog = logs.firstOrNull()
        
        assert(todayLog != null) { "TODAY_BAR_DEBUG log missing" }
        // tripleValues.forecastHigh should be 76.0 from NWS, not 61.0 from Generic
        assert(todayLog!!.message.contains("fHigh=76.0")) { "Expected fHigh=76.0 (NWS), but got: ${todayLog.message}" }
        assert(todayLog.message.contains("fLow=null")) { "Expected fLow=null (NWS), but got: ${todayLog.message}" }
    }

    private fun createWeather(
        date: String,
        source: String,
        high: Float?,
        low: Float?
    ): ForecastEntity {
        return ForecastEntity(
            targetDate = dateEpoch(date),
            forecastDate = dateEpoch(date),
            locationLat = 0.0,
            locationLon = 0.0,
            locationName = "Test",
            highTemp = high,
            lowTemp = low,
            condition = "Clear",
            source = source,
            precipProbability = 0,
            fetchedAt = 1L,
        )
    }
}
