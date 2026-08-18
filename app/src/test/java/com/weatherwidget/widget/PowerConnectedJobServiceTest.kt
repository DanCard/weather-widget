package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plug-in trigger replaces an `ACTION_POWER_CONNECTED` broadcast that this app never receives
 * (manifest-declared receivers get no non-exempt implicit broadcasts at targetSdk 26+). These
 * tests pin the two properties that make the JobScheduler stand-in behave like the broadcast:
 * it waits on the charging constraint, and arming it repeatedly does not disturb a pending wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PowerConnectedJobServiceTest {

    private lateinit var context: Context
    private lateinit var jobScheduler: JobScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(PowerConnectedJobService.JOB_ID)
        // ensureScheduled now consults the battery, and the trend behind it is persisted, so both
        // have to be reset or a verdict latched by one test leaks into the next.
        clearTrendState()
        setStickyBattery(status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0, level = 50)
    }

    private fun clearTrendState() {
        com.weatherwidget.util.SharedPreferencesUtil
            .getPrefs(context, "battery_charge_trend_prefs")
            .edit()
            .clear()
            .commit()
    }

    private fun setStickyBattery(status: Int, plugged: Int, level: Int) {
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_STATUS, status)
                putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
                putExtra(BatteryManager.EXTRA_LEVEL, level)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
            },
        )
    }

    @After
    fun tearDown() {
        jobScheduler.cancel(PowerConnectedJobService.JOB_ID)
    }

    @Test
    fun `job requires charging so it fires on the plug-in transition`() {
        val info = PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = 0L)

        assertTrue("Charging constraint is the whole mechanism", info.isRequireCharging)
    }

    @Test
    fun `job persists so the first plug-in after a reboot is not missed`() {
        val info = PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = 0L)

        assertTrue(info.isPersisted)
    }

    @Test
    fun `first arm carries no latency so an imminent plug-in is caught at once`() {
        val info = PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = 0L)

        assertEquals(
            "A zero-latency arm must not become a delayed job",
            0L,
            info.minLatencyMillis,
        )
    }

    @Test
    fun `arming is skipped while already charging so the trigger cannot spin`() {
        // The regression this pins: the job used to re-arm unconditionally after every run. On a
        // device left plugged in, the charging constraint was already satisfied when the re-arm
        // landed, so it fired again, re-armed again, and became a permanent 10-minute loop
        // duplicating the charging loop. A plug-in trigger has nothing to wait for while charging.
        setStickyBattery(status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_AC, level = 80)

        PowerConnectedJobService.ensureScheduled(context)

        assertNull(
            "A charging device must not arm a plug-in trigger",
            jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID),
        )
    }

    @Test
    fun `arming happens once the device is discharging again`() {
        setStickyBattery(status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_AC, level = 80)
        PowerConnectedJobService.ensureScheduled(context)
        assertNull(jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID))

        // Unplugged and falling: the trend must not keep reporting charging, or the trigger would
        // never re-arm and the next genuine plug-in would go unnoticed.
        setStickyBattery(status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0, level = 79)
        PowerConnectedJobService.ensureScheduled(context)

        assertNotNull(
            "Expected the trigger to re-arm once discharging",
            jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID),
        )
    }

    @Test
    fun `ensureScheduled arms the trigger when nothing is pending`() {
        PowerConnectedJobService.ensureScheduled(context)

        assertNotNull(
            "Expected a pending plug-in trigger",
            jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID),
        )
    }

    @Test
    fun `ensureScheduled leaves an already-armed trigger untouched`() {
        // Widget lifecycle paths call this often. Overwriting a job that is already waiting on the
        // charger would reset its latency clock, so the arm has to be a genuine no-op. Stand the
        // pending job up carrying a distinctive latency — ensureScheduled arms with zero latency,
        // so an overwrite would flatten this to 0, which is exactly what must not happen.
        val sentinelLatencyMs = 987_000L
        jobScheduler.schedule(
            PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = sentinelLatencyMs),
        )

        PowerConnectedJobService.ensureScheduled(context)

        val pending = jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID)
        assertNotNull("Expected the pending trigger to survive", pending)
        assertEquals(
            "ensureScheduled must not overwrite an already-armed trigger",
            sentinelLatencyMs,
            pending!!.minLatencyMillis,
        )
    }

    @Test
    fun `a plug-in refresh is not blocked by the opportunistic battery cutoff`() {
        // The refresh PowerConnectedRefresh enqueues must not be flagged opportunistic: that gate
        // ignores charging and looks only at battery level, so it would reject the plug-in refresh
        // at exactly the battery levels that make people plug in.
        val lowBattery = CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT - 25

        assertTrue(
            "Charging plug-in refresh must run at low battery",
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = false,
                batteryLevel = lowBattery,
            ),
        )
        assertFalse(
            "Guard: the opportunistic flag is what would have blocked it",
            CurrentTempFetchPolicy.shouldFetchNow(
                isCharging = true,
                isScreenInteractive = true,
                isOpportunisticContext = true,
                batteryLevel = lowBattery,
            ),
        )
    }

    @Test
    fun `job needs no network of its own`() {
        val info = PowerConnectedJobService.buildJobInfo(context, minimumLatencyMs = 0L)

        assertEquals(JobInfo.NETWORK_TYPE_NONE, info.networkType)
    }
}
