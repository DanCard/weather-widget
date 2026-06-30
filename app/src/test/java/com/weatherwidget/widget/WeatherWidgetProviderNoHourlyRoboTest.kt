package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.ui.ForecastHistoryActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // Inject UnconfinedTestDispatcher for synchronous deterministic execution
        provider.scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `day click when no hourly data sets transient message and triggers refresh`() = runTest {
        // GIVEN: a future day (e.g. July 7, 2026) with only a daily forecast but no hourly data.
        val targetDay = LocalDate.now().plusDays(7)
        val lat = 37.42
        val lon = -122.08
        val source = WeatherSource.NWS

        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)

        // Insert daily forecast
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
                    fetchedAt = System.currentTimeMillis()
                )
            )
        )

        // Insert last hourly data ending on today + 6 days (meaning today + 7 has no hourly data)
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
                    fetchedAt = System.currentTimeMillis()
                )
            )
        )

        // Setup the simulated intent
        val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
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

        // WHEN: simulated broadcast is received
        provider.onReceive(context, intent)

        // THEN: transient message is stored in SharedPreferences
        val message = stateManager.getActiveTransientMessage(widgetId)
        assertNotNull("Active transient message should not be null", message)
        assertTrue("Message should contain 'Data missing'", message!!.contains("Data missing"))
        assertTrue("Message should contain target day", message.contains(WeatherWidgetProvider.formatNoHourlyDayLabel(targetDay.toString())))
        assertTrue("Message should contain 'refresh triggered'", message.contains("refresh triggered"))
        assertTrue("Message should contain ending hourly date", message.contains("data ends"))

        // AND: immediate update was triggered
        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WeatherWidgetProvider.WORK_NAME_ONE_TIME),
                any<androidx.work.ExistingWorkPolicy>(),
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
    }
}
