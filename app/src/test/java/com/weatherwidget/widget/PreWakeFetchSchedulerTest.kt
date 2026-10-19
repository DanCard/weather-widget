package com.weatherwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** performance/261019-pre-alarm-long-fetch-while-charging.md */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(ShortDuration::class)
class PreWakeFetchSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_800_000_000_000L

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        context.getSharedPreferences("pre_wake_fetch_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun work(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_PRE_WAKE).get()

    private fun pending(): WorkInfo? = work().singleOrNull { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `next alarm is read from the clock app's alarm`() {
        assertNull(PreWakeFetchScheduler.nextAlarmMs(context))
        val alarm = System.currentTimeMillis() + 8 * hour
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm, pi), pi)
        assertEquals(alarm, PreWakeFetchScheduler.nextAlarmMs(context))
    }

    @Test
    fun `arming enqueues a charging-only sync 40 min before the alarm`() {
        val alarm = now + 8 * hour
        val log = PreWakeFetchScheduler.arm(context, alarm, now, trigger = "test")
        assertTrue(log!!, log.startsWith("action=armed") && log.contains("delayMin=440"))
        val info = pending()!!
        assertTrue(info.constraints.requiresCharging())
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        val expectedDelay = alarm - ForecastHorizonCadence.PRE_WAKE_LEAD_MS + ForecastHorizonCadence.PRE_WAKE_CHECK_SLACK_MS - now
        assertEquals(expectedDelay, info.initialDelayMillis)
    }

    @Test
    fun `the same alarm is not re-armed - a moved one replaces the work`() {
        val alarm = now + 8 * hour
        PreWakeFetchScheduler.arm(context, alarm, now, trigger = "test")
        val first = pending()!!.id
        assertNull(PreWakeFetchScheduler.arm(context, alarm, now + hour, trigger = "tick"))
        assertEquals(first, pending()!!.id)

        val log = PreWakeFetchScheduler.arm(context, alarm + 30 * minute, now + hour, trigger = "alarm_changed")
        assertTrue(log!!.startsWith("action=armed"))
        assertNotEquals(first, pending()!!.id)
        assertEquals(1, work().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `no alarm, or one more than 14 h away, cancels what was armed`() {
        PreWakeFetchScheduler.arm(context, now + 8 * hour, now, trigger = "test")
        val log = PreWakeFetchScheduler.arm(context, null, now, trigger = "alarm_changed")
        assertTrue(log!!, log.startsWith("action=cancelled reason=no_alarm"))
        assertNull(pending())
        assertNull("nothing armed, nothing to say", PreWakeFetchScheduler.arm(context, null, now, trigger = "tick"))

        PreWakeFetchScheduler.arm(context, now + 8 * hour, now, trigger = "test")
        val far = PreWakeFetchScheduler.arm(context, now + 40 * hour, now, trigger = "alarm_changed")
        assertTrue(far!!, far.startsWith("action=cancelled reason=too_far"))
        assertNull(pending())
    }

    @Test
    fun `after the alarm the armed state clears without cancelling a later one`() {
        val alarm = now + 8 * hour
        PreWakeFetchScheduler.arm(context, alarm, now, trigger = "test")
        // Tomorrow's alarm, 24 h on, is too far at 06:31: cleared, reason too_far.
        val log = PreWakeFetchScheduler.arm(context, alarm + 24 * hour, alarm + minute, trigger = "tick")
        assertTrue(log!!, log.startsWith("action=cancelled reason=too_far"))
        // Evening: tomorrow's alarm is within 14 h and arms afresh.
        assertTrue(PreWakeFetchScheduler.arm(context, alarm + 24 * hour, alarm + 14 * hour, trigger = "tick")!!.startsWith("action=armed"))
    }
}
