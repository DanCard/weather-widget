package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

/**
 * The on-battery, screen-on observation loop end to end, on a fake clock: the real
 * [ScreenOnReceiver] -> [CurrentTempUpdateScheduler] -> [BatteryObservationAlarm] ->
 * [BatteryObservationAlarmReceiver] -> [WidgetLoopScheduler] post-run chain, with the
 * `ui_update_alarm` heartbeat interleaved. WorkManager is captured at "fetch enqueued"; a fetch is
 * completed by doing what the worker does after it (stamp the fetch time, run the post-run loop step).
 *
 * Every bug found on 2026-10-03 sat between these classes, where per-class tests with mocks pass:
 * gates that disagreed (a run scheduled then blocked), and a heartbeat replacing an alarm whose
 * window was still open (the fetch postponed). Battery Saver's deferral needs a device; see
 * BatteryObservationAlarmBatterySaverTest. Plan:
 * performance/261003-observations-every-20-min-on-battery-screen-on.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class BatteryScreenOnObservationLoopIntegrationTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var screenReceiver: ScreenOnReceiver
    private lateinit var alarmReceiver: BatteryObservationAlarmReceiver

    private var now = START_MS
    private var charging = false
    private var level = 75

    private data class Fetch(val atMs: Long, val request: OneTimeWorkRequest)
    private val fetches = mutableListOf<Fetch>()

    /** When each pending alarm (by trigger time) was armed, to know its system delivery window. */
    private val armedAtByTrigger = mutableMapOf<Long, Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())

        workManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        every { workManager.getWorkInfosForUniqueWork(any()) } returns Futures.immediateFuture(emptyList())
        every { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } answers {
            fetches += Fetch(now, thirdArg())
            mockk(relaxed = true)
        }

        mockkObject(BatterySnapshotProvider)
        every { BatterySnapshotProvider.snapshot(any()) } answers { BatterySnapshot(isCharging = charging, batteryLevel = level) }
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).turnScreenOn(true)

        screenReceiver = ScreenOnReceiver().apply {
            ioDispatcher = UnconfinedTestDispatcher()
            resampleLocation = { _, _ -> }
            clock = { now }
        }
        alarmReceiver = BatteryObservationAlarmReceiver().apply {
            ioDispatcher = UnconfinedTestDispatcher()
            clock = { now }
        }
        // Last fetch half an hour ago, so screen-on catches up immediately.
        FetchMetadata.setLastCurrentTempFetchTime(context, START_MS - TimeUnit.MINUTES.toMillis(30))
    }

    @After
    fun tearDown() {
        unmockkAll()
        WeatherDatabase.setIsTesting(false)
    }

    @Test
    fun `two hours on screen at 75 percent fetch the primary source every 14 to 25 minutes when alarms come early`() {
        runLoop(hours = 2, deliverAtWindowEnd = false)
        assertEveryFetchIsLoopShaped()
    }

    // The worst case: the system delivers each alarm at the very end of its window, so a heartbeat
    // at +15 always lands after the trigger time and inside the window — the 2026-10-03 16:00:30
    // case, which replaced the pending alarm and postponed the fetch.
    @Test
    fun `heartbeats never postpone a fetch even when every alarm is delivered at the end of its window`() {
        runLoop(hours = 2, deliverAtWindowEnd = true)
        assertEveryFetchIsLoopShaped()
    }

    @Test
    fun `plugging in ends the battery loop at the next alarm`() {
        screenOn()
        completeFetch()
        charging = true

        deliverNextAlarm(deliverAtWindowEnd = false)

        assertEquals("only the screen-on catch-up fetch", 1, fetches.size)
        assertTrue("no alarm re-armed", alarms().isEmpty())
    }

    @Test
    fun `falling below 70 percent ends the battery loop at the next alarm`() {
        screenOn()
        completeFetch()
        level = 69

        deliverNextAlarm(deliverAtWindowEnd = false)

        assertEquals(1, fetches.size)
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun `screen off cancels the pending alarm`() {
        screenOn()
        completeFetch()
        assertEquals(1, alarms().size)

        screenReceiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))

        assertTrue(alarms().isEmpty())
    }

    /** Screen on, then alarms and 15-minute heartbeats in time order until [hours] have passed. */
    private fun runLoop(hours: Long, deliverAtWindowEnd: Boolean) {
        val endMs = START_MS + TimeUnit.HOURS.toMillis(hours)
        screenOn()
        completeFetch()
        var nextHeartbeatMs = START_MS + HEARTBEAT_MS
        while (true) {
            val alarmAtMs = nextDeliveryMs(deliverAtWindowEnd) ?: error("loop stopped: no alarm pending at $now")
            if (minOf(alarmAtMs, nextHeartbeatMs) > endMs) break
            if (nextHeartbeatMs < alarmAtMs) {
                now = nextHeartbeatMs
                heartbeat()
                nextHeartbeatMs += HEARTBEAT_MS
            } else {
                deliverNextAlarm(deliverAtWindowEnd)
            }
        }
    }

    private fun assertEveryFetchIsLoopShaped() {
        assertTrue("expected at least 5 fetches in 2 h, got ${fetches.size}", fetches.size >= 5)
        val primary = WidgetStateManager(context).getPrimarySource().id
        fetches.forEach { fetch ->
            val spec = fetch.request.workSpec
            assertEquals(primary, spec.input.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE))
            assertTrue("expedited, so Battery Saver does not defer it", spec.expedited)
            assertTrue(spec.input.getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON)!!.startsWith("battery_screen_on"))
        }
        fetches.zipWithNext().forEach { (a, b) ->
            val gapMin = (b.atMs - a.atMs) / 60_000.0
            assertTrue("gap ${"%.1f".format(gapMin)} min between fetches is outside 14-25", gapMin in 14.0..25.2)
        }
    }

    private fun screenOn() {
        screenReceiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        recordNewAlarm()
    }

    /** What the worker does after a fetch: stamp it, then run the post-run loop step. */
    private fun completeFetch() {
        now += FETCH_DURATION_MS
        FetchMetadata.setLastCurrentTempFetchTime(context, now)
        runBlocking {
            WidgetLoopScheduler.manageCurrentTempLoopAfterRun(
                context = context,
                appLogDao = WeatherDatabase.getDatabase(context).appLogDao(),
                device = DeviceContext(isCharging = charging, batteryLevel = level, isScreenInteractive = true, lastFullFetchAgeSeconds = 0),
                nowMs = now,
            )
        }
        recordNewAlarm()
    }

    /** The `ui_update_alarm` heartbeat's request for the loop. */
    private fun heartbeat() {
        runBlocking {
            CurrentTempUpdateScheduler.scheduleNextChargingUpdate(
                context = context,
                workManager = workManager,
                nowMs = now,
                isScreenInteractive = true,
            )
        }
        recordNewAlarm()
    }

    private fun deliverNextAlarm(deliverAtWindowEnd: Boolean) {
        val alarm = alarms().single()
        now = nextDeliveryMs(deliverAtWindowEnd)!!
        alarmManager().cancel(alarm.operation!!) // delivered: no longer pending in the shadow
        val fetchesBefore = fetches.size
        alarmReceiver.onReceive(context, Intent())
        recordNewAlarm()
        if (fetches.size > fetchesBefore) completeFetch()
    }

    /** When the system would deliver the pending alarm: its trigger, or the end of its 75% window. */
    private fun nextDeliveryMs(deliverAtWindowEnd: Boolean): Long? {
        val trigger = alarms().singleOrNull()?.triggerAtTime ?: return null
        if (!deliverAtWindowEnd) return trigger
        val armedAt = armedAtByTrigger.getValue(trigger)
        return trigger + (BatteryObservationAlarm.SYSTEM_WINDOW_FRACTION * (trigger - armedAt)).toLong()
    }

    private fun recordNewAlarm() {
        alarms().singleOrNull()?.let { armedAtByTrigger.putIfAbsent(it.triggerAtTime, now) }
    }

    private fun alarms(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager()).scheduledAlarms

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private companion object {
        const val START_MS = 1_791_060_000_000L // 2026-10-03 13:40 PDT
        val HEARTBEAT_MS = TimeUnit.MINUTES.toMillis(15)
        const val FETCH_DURATION_MS = 5_000L
    }
}
