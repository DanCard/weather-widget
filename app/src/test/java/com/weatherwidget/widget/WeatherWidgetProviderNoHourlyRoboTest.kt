package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.ui.ForecastHistoryActivity
import com.weatherwidget.widget.handlers.NoHourlyDayClickCoordinator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderNoHourlyRoboTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var stateManager: WidgetStateManager
    private lateinit var provider: WeatherWidgetProvider
    private lateinit var mockWorkManager: WorkManager
    private val widgetId = 9112
    private val lat = 37.42
    private val lon = -122.08
    private val source = WeatherSource.NWS

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()
        WeatherDatabase.setDatabaseForTesting(db)

        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(widgetId)
        stateManager.clearTransientMessage(widgetId)

        mockWorkManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWorkManager

        provider = WeatherWidgetProvider()
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `day click when no hourly data sets pending message and enqueues scoped refresh`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        val workSlot = slot<OneTimeWorkRequest>()
        every {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME),
                any<ExistingWorkPolicy>(),
                capture(workSlot),
            )
        } returns mockk()

        provider.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertNotNull("Active transient message should not be null", message)
        assertTrue("Message should be pending", message!!.contains("refresh will be triggered"))
        assertTrue("Message should contain target day", message.contains(NoHourlyDayClickCoordinator.formatDayLabel(targetDay.toString())))
        assertTrue("Pending message should not be framed as refresh result yet", !message.contains("Result of refresh"))

        val input = workSlot.captured.workSpec.input
        assertEquals(true, input.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false))
        assertEquals(source.id, input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
        assertEquals(widgetId, input.getInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, -1))
        assertEquals(targetDay.toString(), input.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE))
        assertEquals(lat, input.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LAT, 0.0), 0.001)
        assertEquals(lon, input.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LON, 0.0), 0.001)

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `refresh complete still missing posts result message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        provider.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        provider.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertNotNull(message)
        assertTrue("Result should be framed as refresh outcome", message!!.contains("Result of refresh"))
        assertTrue(
            "Result should say no new hourly data retrieved",
            message.contains("No new hourly temperature data was able to be retrieved", ignoreCase = true),
        )
        assertTrue(message.contains(NoHourlyDayClickCoordinator.formatDayLabel(targetDay.toString())))
        assertTrue(message.contains("Data ends") || message.contains("at"))
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(widgetId))
    }

    @Test
    fun `refresh complete with new hourly posts available message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        runBlocking {
            val noon = targetDay.atTime(12, 0)
            val noonMs = noon.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = noonMs,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = 72.0f,
                        condition = "Sunny",
                        source = source.id,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }

        provider.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertNotNull(message)
        assertTrue(message!!.contains("Results of refresh"))
        assertTrue(message.contains("now available", ignoreCase = true))
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(widgetId))
    }

    @Test
    fun `result message expires after display duration`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        provider.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        provider.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()
        assertNotNull(stateManager.getActiveTransientMessage(widgetId))

        val afterExpiry =
            System.currentTimeMillis() +
                WeatherWidgetProvider.NO_HOURLY_MESSAGE_DURATION_MS +
                WeatherWidgetProvider.NO_HOURLY_CLEAR_BUFFER_MS +
                1
        assertNull(stateManager.getActiveTransientMessage(widgetId, nowMs = afterExpiry))
    }

    private fun seedMissingHourlyScenario(targetDay: LocalDate) {
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)

        runBlocking {
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = targetDay.toEpochDay() * 24 * 60 * 60 * 1000L,
                        forecastDate = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        locationLat = lat,
                        locationLon = lon,
                        locationName = "Mountain View, CA",
                        highTemp = 78f,
                        lowTemp = 55f,
                        condition = "Sunny",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )

            val lastHourlyTime = LocalDateTime.now().plusDays(6).withHour(17).withMinute(0)
            val lastHourlyEpochMs = lastHourlyTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            db.hourlyForecastDao().insertAll(
                listOf(
                    HourlyForecastEntity(
                        dateTime = lastHourlyEpochMs,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = 65.0f,
                        condition = "Partly Cloudy",
                        source = source.id,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    private fun dayClickIntent(targetDay: LocalDate): Intent =
        Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_DAY_CLICK
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", targetDay.toString())
            putExtra("isHistory", false)
            putExtra("showHistory", false)
            putExtra("index", 8)
            putExtra(WidgetActions.EXTRA_TARGET_VIEW, ViewMode.TEMPERATURE.name)
            putExtra(WidgetActions.EXTRA_HOURLY_OFFSET, 0)
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
            putExtra(ForecastHistoryActivity.EXTRA_SOURCE, source.displayName)
        }

    private fun refreshCompleteIntent(targetDay: LocalDate): Intent =
        Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", targetDay.toString())
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
        }
}