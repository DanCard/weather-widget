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
    private lateinit var receiver: WidgetActionReceiver
    private lateinit var mockWorkManager: WorkManager
    private val widgetId = 9112
    private val lat = 37.42
    private val lon = -122.08
    private var source = WeatherSource.NWS

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

        receiver = WidgetActionReceiver().also {
            it.repository = mockk(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        db.close()
        WeatherDatabase.resetInstanceForTesting()
        unmockkAll()
    }

    @Test
    fun `day click when no hourly data opens the hourly view under a fetching banner and enqueues scoped refresh`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        val workSlot = slot<OneTimeWorkRequest>()
        every {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_ONE_TIME),
                any<ExistingWorkPolicy>(),
                capture(workSlot),
            )
        } returns mockk()

        receiver.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertNotNull("Active transient message should not be null", message)
        assertTrue("Message should say it is fetching: $message", message!!.contains("Fetching hourly forecast for"))
        assertTrue("Message should contain target day", message.contains(NoHourlyDayClickCoordinator.formatDayLabel(targetDay.toString())))
        assertTrue("Pending message should not be framed as refresh result yet", !message.contains("Result of refresh"))
        assertEquals("the day's hourly view opens at once, empty", ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))

        val input = workSlot.captured.workSpec.input
        assertEquals(true, input.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false))
        assertEquals(source.id, input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
        assertEquals(widgetId, input.getInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, -1))
        assertEquals(targetDay.toString(), input.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE))
        assertEquals(lat, input.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LAT, 0.0), 0.001)
        assertEquals(lon, input.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LON, 0.0), 0.001)

        verify(exactly = 1) {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.WORK_NAME_ONE_TIME),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `refresh complete still missing posts result message`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        receiver.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        receiver.onReceive(context, refreshCompleteIntent(targetDay))
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
        assertEquals("stays on the day's (empty) hourly view", ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))
    }

    @Test
    fun `refresh complete with new hourly clears the fetching banner`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)
        receiver.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()
        assertNotNull("fetching banner up", stateManager.getActiveTransientMessage(widgetId))

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

        receiver.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()

        assertNull("the graph has its data; the banner goes", stateManager.getActiveTransientMessage(widgetId))
        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))
    }

    @Test
    fun `result message expires after display duration`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)

        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)

        receiver.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()
        assertNotNull(stateManager.getActiveTransientMessage(widgetId))

        val afterExpiry =
            System.currentTimeMillis() +
                WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS +
                WidgetTransientMessagePolicy.CLEAR_BUFFER_MS +
                1
        assertNull(stateManager.getActiveTransientMessage(widgetId, nowMs = afterExpiry))
    }

    @Test
    fun `refresh result returns promptly and leaves a durable per-widget clear`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        receiver.scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay)
        val delayedClear = slot<OneTimeWorkRequest>()
        every {
            mockWorkManager.enqueueUniqueWork(
                eq(WidgetWorkScheduler.delayedUiWorkName(widgetId)),
                eq(ExistingWorkPolicy.APPEND_OR_REPLACE),
                capture(delayedClear),
            )
        } returns mockk()

        receiver.onReceive(context, refreshCompleteIntent(targetDay))
        advanceUntilIdle()

        assertEquals(
            WidgetTransientMessagePolicy.NO_HOURLY_MESSAGE_DURATION_MS +
                WidgetTransientMessagePolicy.CLEAR_BUFFER_MS,
            delayedClear.captured.workSpec.initialDelay,
        )
        assertTrue(
            db.appLogDao().getLogsByTag("CLICK_WATCHDOG", 10).isEmpty(),
        )
    }

    /** Google as stored by a routine fetch: every hour from now to now + 72 h. */
    private fun seedGoogleRoutine(targetDay: LocalDate) {
        source = WeatherSource.GOOGLE_WEATHER
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS))
        seedMissingHourlyScenario(targetDay, withFarHourlyRow = false)
        assertEquals("precondition", WeatherSource.GOOGLE_WEATHER, stateManager.getCurrentDisplaySource(widgetId))
        val nowMs = System.currentTimeMillis()
        val hourMs = 3_600_000L
        val currentHour = nowMs - nowMs % hourMs
        runBlocking {
            db.hourlyForecastDao().insertAll(
                (0 until 72).map { h ->
                    HourlyForecastEntity(
                        dateTime = currentHour + h * hourMs,
                        locationLat = lat,
                        locationLon = lon,
                        temperature = 60f + h % 12,
                        condition = "Clear",
                        source = source.id,
                        fetchedAt = nowMs,
                    )
                },
            )
        }
    }

    private fun noHourlyFollowUps(): List<OneTimeWorkRequest> {
        val requests = mutableListOf<OneTimeWorkRequest>()
        verify(atLeast = 0) {
            mockWorkManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(requests))
        }
        return requests.filter { it.workSpec.input.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE) != null }
    }

    @Test
    fun `Google day partly past its stored 72 h opens the hourly view under the fetching banner`() = runTest {
        receiver.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        // The routine 72 h ends in this day's morning: it has hours, but not through its end.
        val targetDay = LocalDate.now().plusDays(3)
        seedGoogleRoutine(targetDay)

        receiver.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertTrue("$message", message!!.contains("Fetching hourly forecast for"))
        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))
        val followUp = noHourlyFollowUps().single()
        assertEquals(targetDay.toString(), followUp.workSpec.input.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE))
        assertEquals(WeatherSource.GOOGLE_WEATHER.id, followUp.workSpec.input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
    }

    @Test
    fun `Google day inside its stored 72 h opens the hourly view with no fetch and no banner`() = runTest {
        receiver.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val targetDay = LocalDate.now().plusDays(1)
        seedGoogleRoutine(targetDay)

        receiver.onReceive(context, dayClickIntent(targetDay))
        advanceUntilIdle()

        assertNull(stateManager.getActiveTransientMessage(widgetId))
        assertTrue(noHourlyFollowUps().isEmpty())
        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))
    }

    /** [withFarHourlyRow]: one row at today+6 17:00, the "data ends" point of the NWS cases. */
    /** The hourly view resting on [day]'s noon, as ‹ › leaves it. */
    private fun restHourlyOn(day: LocalDate) {
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)
        stateManager.setHourlyOffset(
            widgetId,
            java.time.Duration.between(LocalDateTime.now(), day.atTime(12, 0)).toHours().toInt(),
        )
    }

    private fun panFollowUps(): List<Pair<String, OneTimeWorkRequest>> {
        val names = mutableListOf<String>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        verify(atLeast = 0) {
            mockWorkManager.enqueueUniqueWork(capture(names), any<ExistingWorkPolicy>(), capture(requests))
        }
        return names.zip(requests).filter { it.first.startsWith("no_hourly_pan_") }
    }

    @Test
    fun `panning onto a Google day past its stored hours fetches it after the settle`() = runTest {
        val targetDay = LocalDate.now().plusDays(5)
        seedGoogleRoutine(targetDay)
        restHourlyOn(targetDay)

        WidgetDayClickCoordinator.afterHourlyNavigate(context, widgetId)

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertTrue("$message", message!!.contains("Fetching hourly forecast for"))
        val (name, request) = panFollowUps().single()
        assertEquals("no_hourly_pan_$widgetId", name)
        assertEquals(com.weatherwidget.data.remote.HourlyOnDemand.PAN_SETTLE_MS, request.workSpec.initialDelay)
        assertEquals(targetDay.toString(), request.workSpec.input.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE))
        assertEquals(WeatherSource.GOOGLE_WEATHER.id, request.workSpec.input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
    }

    @Test
    fun `panning onto a covered day does nothing`() = runTest {
        val targetDay = LocalDate.now().plusDays(1)
        seedGoogleRoutine(targetDay)
        restHourlyOn(targetDay)

        WidgetDayClickCoordinator.afterHourlyNavigate(context, widgetId)

        assertNull(stateManager.getActiveTransientMessage(widgetId))
        assertTrue(panFollowUps().isEmpty())
    }

    @Test
    fun `panning onto an NWS day past its data says where it ends, without fetching`() = runTest {
        val targetDay = LocalDate.now().plusDays(7)
        seedMissingHourlyScenario(targetDay) // NWS, data ends today+6 17:00
        restHourlyOn(targetDay)

        WidgetDayClickCoordinator.afterHourlyNavigate(context, widgetId)

        val message = stateManager.getActiveTransientMessage(widgetId)
        assertTrue("$message", message!!.startsWith("No hourly forecast for"))
        assertTrue("$message", message.contains("data ends"))
        assertTrue(panFollowUps().isEmpty())
    }

    @Test
    fun `an older day's fetch result never replaces a newer day's banner`() = runTest {
        receiver.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val olderDay = LocalDate.now().plusDays(4)
        val newerDay = LocalDate.now().plusDays(5)
        seedGoogleRoutine(newerDay)
        restHourlyOn(newerDay)
        WidgetDayClickCoordinator.afterHourlyNavigate(context, widgetId)
        val banner = stateManager.getActiveTransientMessage(widgetId)

        receiver.onReceive(context, refreshCompleteIntent(olderDay))
        advanceUntilIdle()

        assertEquals(banner, stateManager.getActiveTransientMessage(widgetId))
    }

    private fun seedMissingHourlyScenario(targetDay: LocalDate, withFarHourlyRow: Boolean = true) {
        stateManager.setViewMode(widgetId, ViewMode.DAILY)
        stateManager.setCurrentDisplaySource(widgetId, source)

        runBlocking {
            db.forecastDao().insertAll(
                listOf(
                    ForecastEntity(
                        targetDate = targetDay.toEpochDay() * 24 * 60 * 60 * 1000L,
                        dateOfPrediction = LocalDate.now().toEpochDay() * 24 * 60 * 60 * 1000L,
                        locationLat = lat,
                        locationLon = lon,
                        highTemp = 78f,
                        lowTemp = 55f,
                        condition = "Sunny",
                        source = source.id,
                        precipProbability = 0,
                        fetchedAt = System.currentTimeMillis(),
                    ),
                ),
            )

            if (!withFarHourlyRow) return@runBlocking
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
        Intent(context, WidgetActionReceiver::class.java).apply {
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
        Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetActions.ACTION_NO_HOURLY_REFRESH_COMPLETE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("date", targetDay.toString())
            putExtra(ForecastHistoryActivity.EXTRA_LAT, lat)
            putExtra(ForecastHistoryActivity.EXTRA_LON, lon)
        }
}
