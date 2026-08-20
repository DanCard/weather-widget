package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.mockAppWidgetManager
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyViewHandlerLocationScopeRoboTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.weatherwidget.widget.WidgetPushDispatcher.resetForTest()
    }

    /**
     * The today-column overlay re-derives the dominant station against whatever location the daily
     * handler passes to the observation query, and it must match the location the producer used to
     * derive `observedAt` (the configured widget location) or the station rows drop with
     * `observed_at_skew`. Regression for the location handoff on 2026-08-19 where the handler used
     * only the forecast data location (~800 m away, a different observation site).
     */
    @Test
    fun `updateWidget queries observations at the configured widget location not the data location`() = runBlocking {
        val now = LocalDateTime.of(2030, 6, 15, 12, 0)
        val todayStr = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val widgetId = 99
        val configuredLat = 37.4241668
        val configuredLon = -122.0884441
        val dataLat = 37.417
        val dataLon = -122.089

        val stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))
        stateManager.setWidgetLocations(intArrayOf(widgetId), configuredLat, configuredLon)
        val resolvedLocation = stateManager.getWidgetLocation(widgetId)!!

        val (appWidgetManager, _) = mockAppWidgetManager(widgetId = widgetId, widthDp = 200, heightDp = 90)

        val repository = mockk<WeatherRepository>(relaxed = true)
        val latSlot = slot<Double>()
        val lonSlot = slot<Double>()
        coEvery {
            repository.getObservationsInRange(any(), any(), capture(latSlot), capture(lonSlot))
        } returns emptyList()

        DailyViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = widgetId,
            weatherData = WeatherData(
                weatherList = listOf(createWeather(todayStr, dataLat, dataLon)),
                forecastSnapshots = emptyMap(),
                hourlyForecasts = emptyList(),
                currentTemps = emptyList(),
                dailyActualsBySource = emptyMap(),
            ),
            observationData = ObservationData(
                lastObservedTemp = 70f,
                observedAt = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
            now = now,
            startupToken = null,
            stateManagerNullable = stateManager,
            repository = repository,
        )

        assertEquals("observation query lat should use the configured location", resolvedLocation.first, latSlot.captured, 0.000001)
        assertEquals("observation query lon should use the configured location", resolvedLocation.second, lonSlot.captured, 0.000001)
    }

    private fun createWeather(date: String, lat: Double, lon: Double): ForecastEntity =
        ForecastEntity(
            targetDate = dateEpoch(date),
            dateOfPrediction = dateEpoch(date),
            locationLat = lat,
            locationLon = lon,
            highTemp = 70f,
            lowTemp = 55f,
            condition = "Clear",
            source = WeatherSource.NWS.id,
            precipProbability = 0,
            fetchedAt = 1L,
        )
}
