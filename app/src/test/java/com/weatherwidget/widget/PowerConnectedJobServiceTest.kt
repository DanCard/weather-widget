package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `re-arm carries latency so staying plugged in cannot spin`() {
        // Without this the re-armed job would find its charging constraint already satisfied and
        // re-fire immediately, forever.
        val info =
            PowerConnectedJobService.buildJobInfo(
                context,
                minimumLatencyMs = PowerConnectedJobService.REARM_LATENCY_MS,
            )

        assertEquals(PowerConnectedJobService.REARM_LATENCY_MS, info.minLatencyMillis)
        assertTrue("Re-arm latency must be positive", PowerConnectedJobService.REARM_LATENCY_MS > 0L)
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
        // pending job up as a re-armed one (latency-carrying) — an overwrite would flatten that
        // latency back to zero, which is exactly what must not happen.
        jobScheduler.schedule(
            PowerConnectedJobService.buildJobInfo(
                context,
                minimumLatencyMs = PowerConnectedJobService.REARM_LATENCY_MS,
            ),
        )

        PowerConnectedJobService.ensureScheduled(context)

        val pending = jobScheduler.getPendingJob(PowerConnectedJobService.JOB_ID)
        assertNotNull("Expected the pending trigger to survive", pending)
        assertEquals(
            "ensureScheduled must not overwrite an already-armed trigger",
            PowerConnectedJobService.REARM_LATENCY_MS,
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
