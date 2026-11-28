package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.ObservationPoolDiagnostics
import com.weatherwidget.data.local.ObservationRangeRead
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.FetchDotDebug
import com.weatherwidget.widget.TemperatureGraphRenderer
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureFetchDotUpdateRoboTest {
    private lateinit var context: Context
    private val appWidgetId = 78

    private fun observationReadOf(rows: List<ObservationEntity>): ObservationRangeRead {
        val newest = rows.maxOfOrNull { it.timestamp }
        val diag = ObservationPoolDiagnostics.Summary(
            candidateCount = rows.size,
            mergedCount = rows.size,
            candidateNewestMs = newest,
            mergedNewestMs = newest,
            siteCount = if (rows.isEmpty()) 0 else 1,
            droppedFresherSites = emptyList(),
        )
        return ObservationRangeRead(rows, diag)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager(context).clearWidgetState(appWidgetId)
    }

    @Test
    fun `fetch dot callback updates when graphed actual timestamp changes`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        }
        every { appWidgetManager.updateAppWidget(appWidgetId, any()) } returns Unit
        val repository = mockk<WeatherRepository>()

        val now = LocalDateTime.now()
        val baseHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourly = listOf(
            HourlyForecastEntity(
                dateTime = baseHour.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 70.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
            HourlyForecastEntity(
                dateTime = baseHour.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 71.0f,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            ),
        )

        val firstObservedAt = now.minusMinutes(5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val secondObservedAt = now.minusMinutes(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val firstActuals = listOf(
            ObservationEntity(
                stationId = "KTEST",
                stationName = "Test Station",
                timestamp = firstObservedAt,
                temperature = 70.5f,
                condition = "Clear",
                locationLat = 37.0,
                locationLon = -122.0,
                api = "NWS",
            ),
        )
        val secondActuals = listOf(
            ObservationEntity(
                stationId = "KTEST",
                stationName = "Test Station",
                timestamp = secondObservedAt,
                temperature = 70.6f,
                condition = "Clear",
                locationLat = 37.0,
                locationLon = -122.0,
                api = "NWS",
            ),
        )
        val resolved = mutableListOf<FetchDotDebug>()
        io.mockk.coEvery { repository.getObservationsInRange(any(), any(), any(), any(), any()) } returns firstActuals andThen secondActuals
        io.mockk.coEvery { repository.readObservationsInRange(any(), any(), any(), any(), any()) } returns observationReadOf(firstActuals) andThen observationReadOf(secondActuals)

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            lastObservedTemp = 70.5f,
            observedAt = firstObservedAt,
            onFetchDotResolved = { resolved.add(it) },
            repository = repository,
        )

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            lastObservedTemp = 70.6f,
            observedAt = secondObservedAt,
            onFetchDotResolved = { resolved.add(it) },
            repository = repository,
        )

        assertEquals("Should resolve fetch dot once per update", 2, resolved.size)
        assertEquals(firstObservedAt, resolved[0].observedAt)
        assertEquals(secondObservedAt, resolved[1].observedAt)
    }

    @Test
    fun `fetch dot callback uses last raw observation not later extrapolated point`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
        }
        every { appWidgetManager.updateAppWidget(appWidgetId, any()) } returns Unit
        val repository = mockk<WeatherRepository>()

        val now = LocalDateTime.now()
        val baseHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourly =
            (-1L..2L).map { offset ->
                HourlyForecastEntity(
                    dateTime = baseHour.plusHours(offset).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    locationLat = 37.0,
                    locationLon = -122.0,
                    temperature = 68.0f + offset,
                    condition = "Clear",
                    source = WeatherSource.NWS.id,
                    precipProbability = 0,
                    fetchedAt = System.currentTimeMillis(),
                )
            }

        val rawObservedAt = baseHour.minusMinutes(55).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val actuals = listOf(
            ObservationEntity(
                stationId = "LOAC1",
                stationName = "Test Station",
                timestamp = rawObservedAt,
                temperature = 70.0f,
                condition = "Clear",
                locationLat = 37.0,
                locationLon = -122.0,
                api = "NWS",
            ),
        )
        val resolved = mutableListOf<FetchDotDebug>()
        io.mockk.coEvery { repository.getObservationsInRange(any(), any(), any(), any(), any()) } returns actuals
        io.mockk.coEvery { repository.readObservationsInRange(any(), any(), any(), any(), any()) } returns observationReadOf(actuals)

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            lastObservedTemp = 70.0f,
            observedAt = rawObservedAt,
            onFetchDotResolved = { resolved.add(it) },
            repository = repository,
        )

        assertEquals("Should resolve fetch dot once", 1, resolved.size)
        assertEquals("Anchor should stay at raw observed timestamp", rawObservedAt, resolved.single().observedAt)
        assertTrue("Fetch dot should resolve within the graphed window", resolved.single().withinWindow)
    }

    /**
     * The startup paint used to skip the observation read ("deferred actuals") and draw the past
     * hours' forecast where the actual line goes, swapped for the observed line up to 30 s later — a
     * fake actual line on the Fold 4 (2026-11-27). Every paint, startup included, now reads the
     * observations, so the very first frame graphs the real actual line.
     */
    @Test
    fun `startup paint reads observations and graphs the actual line`() = runBlocking {
        val appWidgetManager = mockk<AppWidgetManager>()
        every { appWidgetManager.getAppWidgetOptions(appWidgetId) } returns Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
        }
        every { appWidgetManager.updateAppWidget(appWidgetId, any()) } returns Unit
        every { appWidgetManager.partiallyUpdateAppWidget(appWidgetId, any()) } returns Unit
        val repository = mockk<WeatherRepository>(relaxed = true)

        val now = LocalDateTime.now()
        val zone = java.time.ZoneId.systemDefault()
        val baseHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourly = (-3L..3L).map { offset ->
            HourlyForecastEntity(
                dateTime = baseHour.plusHours(offset).atZone(zone).toInstant().toEpochMilli(),
                locationLat = 37.0,
                locationLon = -122.0,
                temperature = 60.0f + offset,
                condition = "Clear",
                source = WeatherSource.NWS.id,
                precipProbability = 0,
                fetchedAt = System.currentTimeMillis(),
            )
        }
        val observedAt = now.minusMinutes(20).atZone(zone).toInstant().toEpochMilli()
        val actuals = (0L..2L).map { i ->
            ObservationEntity(
                stationId = "KTEST",
                stationName = "Test Station",
                timestamp = observedAt - i * 60 * 60 * 1000L,
                temperature = 70.0f - i,
                condition = "Clear",
                locationLat = 37.0,
                locationLon = -122.0,
                api = "NWS",
            )
        }
        io.mockk.coEvery { repository.getObservationsInRange(any(), any(), any(), any(), any()) } returns actuals
        io.mockk.coEvery { repository.readObservationsInRange(any(), any(), any(), any(), any()) } returns observationReadOf(actuals)
        val resolved = mutableListOf<FetchDotDebug>()

        TemperatureViewHandler.updateWidget(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetId = appWidgetId,
            hourlyForecasts = hourly,
            currentTempHourlyForecasts = hourly,
            centerTime = now,
            displaySource = WeatherSource.NWS,
            lastObservedTemp = 70.0f,
            observedAt = observedAt,
            onFetchDotResolved = { resolved.add(it) },
            repository = repository,
            startupToken = "startup-test",
        )

        io.mockk.coVerify(atLeast = 1) { repository.readObservationsInRange(any(), any(), any(), any(), any()) }
        println("startup paint fetch dot: $resolved")
        assertEquals("startup paint must graph the observed actual line", 1, resolved.size)
        assertEquals(observedAt, resolved.single().observedAt)
        assertTrue("actual line should end inside the graphed window", resolved.single().withinWindow)
    }
}
