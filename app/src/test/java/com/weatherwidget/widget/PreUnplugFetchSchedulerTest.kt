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
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.shared.util.ChargeHistory
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android's host for the shared pre-unplug rule: prefs storage (and its migration from the
 * overnight-only ring), WorkManager checks, the clock app's alarm, and the charging screen-on
 * forecast catch-up. plans/261028-learned-unplug-prediction.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PreUnplugFetchSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val zone = ZoneId.systemDefault()
    private val monday = LocalDate.of(2026, 11, 2)
    private val tuesday = monday.plusDays(1)
    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun prefs(name: String) = SharedPreferencesUtil.getPrefs(context, name)

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        prefs(PrefsChargeHistoryStore.PREFS_NAME).edit().clear().commit()
        prefs(PrefsChargeHistoryStore.LEGACY_PRE_WAKE_PREFS).edit().clear().commit()
        PreUnplugFetchScheduler.resetForTesting()
    }

    private fun pending(): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_PRE_UNPLUG).get()
            .singleOrNull { it.state == WorkInfo.State.ENQUEUED }

    private fun runner(now: Long, alarm: Long? = null) =
        PreUnplugFetchRunner(PreUnplugFetchScheduler.AndroidHost(context, clock = { now }, alarm = { alarm }))

    /** The Fold's two Tuesday unplugs, as the old overnight ring stored them (`at,sessionMinutes,exact`). */
    private fun writeLegacyRing() {
        val ring = listOf(at(tuesday.minusWeeks(2), 11, 50) to 900, at(tuesday.minusWeeks(1), 11, 40) to 960)
            .joinToString(";") { (atMs, min) -> "$atMs,$min,1" }
        prefs(PrefsChargeHistoryStore.PREFS_NAME).edit()
            .putString(PrefsChargeHistoryStore.LEGACY_UNPLUGS, ring)
            .putLong(PrefsChargeHistoryStore.LEGACY_CHARGING_SINCE_MS, at(monday, 20, 0))
            .putLong(PrefsChargeHistoryStore.LEGACY_LAST_SEEN_CHARGING_MS, at(monday, 21, 50))
            .commit()
    }

    @Test
    fun `next alarm is read from the clock app's alarm`() {
        assertNull(PreUnplugFetchScheduler.nextAlarmMs(context))
        val alarm = System.currentTimeMillis() + 8 * hour
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm, pi), pi)
        assertEquals(alarm, PreUnplugFetchScheduler.nextAlarmMs(context))
    }

    @Test
    fun `the old overnight ring migrates to sessions and is dropped on the first save`() {
        writeLegacyRing()
        prefs(PrefsChargeHistoryStore.LEGACY_PRE_WAKE_PREFS).edit().putLong(PrefsChargeHistoryStore.LEGACY_ARMED_WAKE_AT_MS, at(tuesday, 8, 0)).commit()
        val store = PrefsChargeHistoryStore(context)
        val state = store.load()
        assertEquals(
            ChargeHistory.Session(at(tuesday.minusWeeks(2), 11, 50) - 900 * minute, at(tuesday.minusWeeks(2), 11, 50), true),
            state.sessions.first(),
        )
        assertEquals(at(monday, 20, 0), state.chargingSinceMs)
        assertEquals("the alarm-era check is carried", at(tuesday, 8, 0), state.armedWakeAtMs)
        store.save(state)
        assertFalse(prefs(PrefsChargeHistoryStore.PREFS_NAME).contains(PrefsChargeHistoryStore.LEGACY_UNPLUGS))
        assertFalse(prefs(PrefsChargeHistoryStore.LEGACY_PRE_WAKE_PREFS).contains(PrefsChargeHistoryStore.LEGACY_ARMED_WAKE_AT_MS))
        assertEquals(state, store.load())
    }

    @Test
    fun `a migrated Tuesday habit enqueues a charging-only check before 11-40`() = runBlocking {
        writeLegacyRing()
        val now = at(monday, 22, 0)
        runner(now).arm(trigger = "sync")
        val info = pending()!!
        assertTrue(info.constraints.requiresCharging())
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        val checkAt = at(tuesday, 11, 40) - ForecastHorizonCadence.PRE_WAKE_LEAD_MS + ForecastHorizonCadence.PRE_WAKE_CHECK_SLACK_MS
        assertEquals(checkAt - now, info.initialDelayMillis)
    }

    @Test
    fun `a bare alarm cancels the alarm-era check and arms nothing`() = runBlocking {
        val now = at(monday, 20, 0)
        WidgetWorkScheduler.enqueuePreUnplugSync(context, delayMs = 75 * minute)
        prefs(PrefsChargeHistoryStore.LEGACY_PRE_WAKE_PREFS).edit().putLong(PrefsChargeHistoryStore.LEGACY_ARMED_WAKE_AT_MS, at(monday, 22, 0)).commit()
        runner(now, alarm = at(monday, 22, 0)).arm(trigger = "alarm_changed")
        assertNull(pending())
        val state = PrefsChargeHistoryStore(context).load()
        assertNull(state.armedWakeAtMs)
        assertEquals(at(monday, 22, 0), state.pendingAlarmMs)
    }

    @Test
    fun `charging screen-on catch-up asks for the displayed source only when its forecast is past 4 h`() {
        val manager = WidgetStateManager(context)
        manager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        val displayed = manager.getActiveDisplaySourceIds().single()
        val lat = 37.42
        val lon = -122.08
        val now = at(monday, 9, 0)
        manager.markLongFetch(displayed, lat, lon, now - 5 * hour)
        assertEquals(listOf(displayed), ScreenOnReceiver.chargingForecastCatchUpSources(manager, lat, lon, now))
        manager.markLongFetch(displayed, lat, lon, now - 2 * hour)
        assertTrue(ScreenOnReceiver.chargingForecastCatchUpSources(manager, lat, lon, now).isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a screen-on on the charger records a sighting`() {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(android.os.BatteryManager.EXTRA_PLUGGED, android.os.BatteryManager.BATTERY_PLUGGED_AC)
                putExtra(android.os.BatteryManager.EXTRA_LEVEL, 90)
                putExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            },
        )
        val receiver = ScreenOnReceiver().apply {
            ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
            resampleLocation = { _, _ -> }
        }
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        val state = PrefsChargeHistoryStore(context).load()
        assertTrue("charging session opened by the sighting", state.chargingSinceMs != null && state.lastSeenChargingMs != null)
    }
}
