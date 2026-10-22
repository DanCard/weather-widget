package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
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
import java.util.TimeZone

/** performance/261019-learned-unplug-time-and-overnight-short-skip.md */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class ChargeSessionTrackerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        listOf("charge_session_prefs", "pre_wake_fetch_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /** A night's charge seen by the charging loop every 15 min, then seen on battery [seenOnBatteryAfterMin] later. */
    private fun night(plugAt: Long, lastSightingAt: Long, seenOnBatteryAfterMin: Long = 10): String? {
        var t = plugAt
        while (t <= lastSightingAt) {
            ChargeSessionTracker.observe(context, isCharging = true, nowMs = t)
            t += 15 * minute
        }
        return ChargeSessionTracker.observe(context, isCharging = false, nowMs = lastSightingAt + seenOnBatteryAfterMin * minute)
    }

    @Test
    fun `an unplug seen later is recorded at the last charging sighting`() {
        val plug = 1_800_000_000_000L
        val line = night(plug, plug + 8 * hour)!!
        assertTrue(line, line.startsWith("action=recorded") && line.contains("estimate=last_seen"))
        val u = ChargeSessionTracker.unplugs(context).single()
        assertEquals(plug + 8 * hour, u.atMs)
        assertEquals(8 * 60, u.sessionMinutes)
        // Repeated battery sightings record nothing more.
        assertNull(ChargeSessionTracker.observe(context, isCharging = false, nowMs = plug + 9 * hour))
        assertEquals(1, ChargeSessionTracker.unplugs(context).size)
    }

    @Test
    fun `the broadcast records the exact time`() {
        val plug = 1_800_000_000_000L
        ChargeSessionTracker.observe(context, true, plug, exact = true)
        ChargeSessionTracker.observe(context, true, plug + 7 * hour)
        val line = ChargeSessionTracker.observe(context, false, plug + 7 * hour + 9 * minute, exact = true)!!
        assertTrue(line, line.contains("estimate=exact"))
        assertEquals(plug + 7 * hour + 9 * minute, ChargeSessionTracker.unplugs(context).single().atMs)
    }

    @Test
    fun `a top-up and an unbounded gap are not recorded`() {
        val plug = 1_800_000_000_000L
        assertTrue(night(plug, plug + 2 * hour)!!.contains("reason=top_up"))
        // The loop went quiet for 3 h before the battery was next seen: the unplug is anywhere in it.
        assertTrue(night(plug + 10 * hour, plug + 18 * hour, seenOnBatteryAfterMin = 180)!!.contains("reason=unbounded"))
        assertTrue(ChargeSessionTracker.unplugs(context).isEmpty())
    }

    @Test
    fun `an all-day desk charge is skipped as daytime`() {
        val line = night(ms(LocalDate.of(2026, 10, 19).atTime(9, 0)), ms(LocalDate.of(2026, 10, 19).atTime(17, 30)))!!
        assertTrue(line, line.contains("reason=daytime"))
        assertTrue(ChargeSessionTracker.unplugs(context).isEmpty())
    }

    @Test
    fun `the ring keeps the last 28`() {
        var plug = 1_800_000_000_000L
        repeat(30) {
            night(plug, plug + 8 * hour)
            plug += 24 * hour
        }
        val unplugs = ChargeSessionTracker.unplugs(context)
        assertEquals(ChargeSessionTracker.MAX_UNPLUGS, unplugs.size)
        assertEquals(plug - 24 * hour + 8 * hour, unplugs.last().atMs)
    }

    /** Integration: tracker → UnplugRoutine → PreWakeFetchScheduler, no alarm set. */
    @Test
    fun `two weeks of 06 40 unplugs arm a learned pre-wake check, and an alarm then takes over`() {
        val monday = LocalDate.of(2026, 10, 19)
        for (d in 14 downTo 1) {
            val date = monday.minusDays(d.toLong())
            if (date.dayOfWeek.value >= 6) continue
            night(ms(date.minusDays(1).atTime(22, 40)), ms(date.atTime(6, 40)))
        }
        val now = ms(monday.atTime(22, 0))
        val routine = PreWakeFetchScheduler.dailyRoutineLog(context, now)!!
        assertTrue(routine, routine.startsWith("weekday=06:40±0m n=10"))

        val wake = PreWakeFetchScheduler.nextWake(context, now)!!
        assertEquals(PreWakeFetchScheduler.Wake(ms(monday.plusDays(1).atTime(6, 40)), "learned"), wake)
        val armed = PreWakeFetchScheduler.arm(context, wake, now, trigger = "sync")!!
        assertTrue(armed, armed.contains("wake=learned") && armed.contains("06:00"))
        val pending = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_PRE_WAKE).get()
            .single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(ms(monday.plusDays(1).atTime(6, 0)) - now, pending.initialDelayMillis)

        val alarm = PreWakeFetchScheduler.Wake(ms(monday.plusDays(1).atTime(6, 0)), "alarm")
        assertTrue(PreWakeFetchScheduler.arm(context, alarm, now, trigger = "alarm_changed")!!.contains("wake=alarm"))
    }
}
