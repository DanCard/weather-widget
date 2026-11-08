package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Screen-on UI ticks are delayed jobs, not alarms: Samsung Freecess held the widget's alarms (wakeup
 * ones too) for 35 minutes with the screen on, while a job start unfreezes the app
 * (plans/261108-samsung-freecess-stalls-now-dot.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class UiTickSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val powerManager get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Before
    fun setup() {
        WeatherDatabase.setDatabaseForTesting(TestDatabase.create())
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    private fun tickInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(UIUpdateScheduler.UI_TICK_WORK_NAME)
            .get(5, TimeUnit.SECONDS)

    @Test
    fun `screen on schedules the tick as a delayed job and no alarm`() = runBlocking {
        shadowOf(powerManager).setIsInteractive(true)

        UIUpdateScheduler(context).scheduleNextUpdate()

        val infos = tickInfos()
        println("tick work=${infos.map { it.state }} nextAlarm=${shadowOf(alarmManager).nextScheduledAlarm}")
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertNull("no alarm while the screen is on", shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `screen off keeps the opportunistic RTC alarm and schedules no job`() = runBlocking {
        shadowOf(powerManager).setIsInteractive(false)

        UIUpdateScheduler(context).scheduleNextUpdate()

        val alarm = shadowOf(alarmManager).nextScheduledAlarm
        println("tick work=${tickInfos().map { it.state }} alarmType=${alarm?.type}")
        assertNotNull(alarm)
        assertEquals(AlarmManager.RTC, alarm!!.type)
        assertTrue("no tick job while the screen is off", tickInfos().isEmpty())
    }

    @Test
    fun `rescheduling replaces a pending tick instead of stacking`() = runBlocking {
        shadowOf(powerManager).setIsInteractive(true)

        UIUpdateScheduler(context).scheduleNextUpdate()
        UIUpdateScheduler(context).scheduleNextUpdate()

        val states = tickInfos().map { it.state }
        println("tick work after two schedules=$states")
        assertEquals(1, states.count { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `tick policy never cancels a running tick`() {
        val cases = mapOf(
            emptyList<WorkInfo.State>() to ExistingWorkPolicy.REPLACE,
            listOf(WorkInfo.State.ENQUEUED) to ExistingWorkPolicy.REPLACE,
            listOf(WorkInfo.State.SUCCEEDED) to ExistingWorkPolicy.REPLACE,
            listOf(WorkInfo.State.RUNNING) to ExistingWorkPolicy.APPEND_OR_REPLACE,
            listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED) to ExistingWorkPolicy.KEEP,
        )
        cases.forEach { (states, expected) ->
            val policy = UiTick.tickWorkPolicy(states)
            println("states=$states policy=$policy")
            assertEquals("states=$states", expected, policy)
        }
    }
}
