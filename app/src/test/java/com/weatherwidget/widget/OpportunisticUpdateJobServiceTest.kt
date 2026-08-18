package com.weatherwidget.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.LongDuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class OpportunisticUpdateJobServiceTest {

    @Test
    fun `unplugged 66 percent schedules a 45 minute periodic job`() {
        val fixture = fixture(level = 66, charging = false)
        val jobSlot = slot<JobInfo>()
        every { fixture.jobScheduler.schedule(capture(jobSlot)) } returns JobScheduler.RESULT_SUCCESS

        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(fixture.context)

        assertEquals(
            TimeUnit.MINUTES.toMillis(CurrentTempFetchPolicy.OPPORTUNISTIC_INTERVAL_MINUTES),
            jobSlot.captured.intervalMillis,
        )
        verify(exactly = 0) { fixture.jobScheduler.cancel(any()) }
    }

    @Test
    fun `unplugged 65 percent cancels instead of scheduling`() {
        val fixture = fixture(level = 65, charging = false)

        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(fixture.context)

        verify(exactly = 1) { fixture.jobScheduler.cancel(any()) }
        verify(exactly = 0) { fixture.jobScheduler.schedule(any()) }
    }

    @Test
    fun `charging does not bypass battery cutoff for opportunistic job`() {
        val fixture = fixture(level = 20, charging = true)

        OpportunisticUpdateJobService.scheduleOpportunisticUpdate(fixture.context)

        verify(exactly = 0) { fixture.jobScheduler.schedule(any()) }
        verify(exactly = 1) { fixture.jobScheduler.cancel(any()) }
    }

    private fun fixture(
        level: Int,
        charging: Boolean,
    ): Fixture {
        val context = mockk<Context>()
        val jobScheduler = mockk<JobScheduler>(relaxed = true)
        val batteryStatus =
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(
                    BatteryManager.EXTRA_STATUS,
                    if (charging) {
                        BatteryManager.BATTERY_STATUS_CHARGING
                    } else {
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING
                    },
                )
                .putExtra(
                    BatteryManager.EXTRA_PLUGGED,
                    if (charging) BatteryManager.BATTERY_PLUGGED_AC else 0,
                )

        // The battery read now folds the level into a persisted trend (BatteryChargeTrend), so the
        // mocked Context needs real preferences behind it. A distinct store per fixture keeps the
        // trend's deliberate stickiness from leaking a verdict between tests.
        val trendPrefs =
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("trend_${System.nanoTime()}", Context.MODE_PRIVATE)

        every { context.packageName } returns "com.weatherwidget"
        every { context.getSystemService(Context.JOB_SCHEDULER_SERVICE) } returns jobScheduler
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryStatus
        every { context.getSharedPreferences(any(), any()) } returns trendPrefs

        return Fixture(context, jobScheduler)
    }

    private data class Fixture(
        val context: Context,
        val jobScheduler: JobScheduler,
    )
}
