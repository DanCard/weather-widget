package com.weatherwidget.widget

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.util.ChargeHistory
import com.weatherwidget.shared.util.ChargeHistoryStore
import com.weatherwidget.shared.util.PreUnplugFetchRunner
import com.weatherwidget.shared.util.UnplugPredictor
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Android's [PreUnplugFetchRunner.Host]: prefs storage, the clock app's next alarm, and a
 * charging-only WorkManager sync as the check (plans/261028-learned-unplug-prediction.md). The rule —
 * what to record, what to predict, when to check, what to log — is the shared runner's.
 *
 * Power sightings come from the runtime [ChargeStateReceiver] (exact) and every worker run (the
 * charging loop sees the battery every 10–30 min). Re-armed from every full sync, the plug-in job and
 * [NextAlarmChangedReceiver], which also fires as an alarm goes off — that is how a fired alarm is
 * recorded.
 */
internal object PreUnplugFetchScheduler {
    private const val TAG = "PreUnplugFetch"

    @Volatile
    private var runner: PreUnplugFetchRunner? = null

    fun runner(context: Context): PreUnplugFetchRunner =
        runner ?: synchronized(this) {
            runner ?: PreUnplugFetchRunner(AndroidHost(context.applicationContext)).also { runner = it }
        }

    /** Robolectric gives each test a new Application; the singleton must not keep the last one's. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        runner = null
    }

    /** The next predicted unplug, for [ForecastFetchContext.wakeAtMs]; null when none. */
    fun nextWake(context: Context): UnplugPredictor.Prediction? = runner(context).nextWake()

    /** The next alarm the user's clock app set ([AlarmManager.setAlarmClock]); null when none. */
    fun nextAlarmMs(context: Context): Long? =
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock?.triggerTime
        } catch (e: Exception) {
            Log.w(TAG, "nextAlarmClock failed", e)
            null
        }

    class AndroidHost(
        private val context: Context,
        private val clock: () -> Long = System::currentTimeMillis,
        private val alarm: () -> Long? = { nextAlarmMs(context) },
    ) : PreUnplugFetchRunner.Host {
        override val store: ChargeHistoryStore = PrefsChargeHistoryStore(context)

        override fun nowMs(): Long = clock()

        override fun nextAlarmMs(): Long? = alarm()

        override fun scheduleCheck(delayMs: Long) {
            WidgetWorkScheduler.enqueuePreUnplugSync(context, delayMs)
        }

        override fun cancelCheck() {
            WidgetWorkScheduler.cancelPreUnplugSync(context)
        }

        override suspend fun log(tag: String, message: String, level: String) {
            WeatherDatabase.getDatabase(context).appLogDao().log(tag, message, level)
        }
    }
}

/**
 * [ChargeHistory.State] in `charge_session_prefs`. The first load converts the older overnight-only
 * `unplugs` ring (each `at,sessionMinutes,exact` becomes a session) and the open session's keys; the
 * first save drops them, along with the alarm-era `pre_wake_fetch_prefs` (its armed check is carried).
 */
internal class PrefsChargeHistoryStore(private val context: Context) : ChargeHistoryStore {
    private val prefs get() = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)

    override fun load(): ChargeHistory.State {
        val prefs = prefs
        ChargeHistory.State.decode(prefs.getString(KEY_STATE, null))?.let { return it }
        val since = prefs.getLong(LEGACY_CHARGING_SINCE_MS, 0L).takeIf { it > 0L }
        val sessions = prefs.getString(LEGACY_UNPLUGS, null)?.split(';')?.mapNotNull { entry ->
            val p = entry.split(',')
            if (p.size != 3) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            val minutes = p[1].toLongOrNull() ?: return@mapNotNull null
            ChargeHistory.Session(at - minutes * 60_000L, at, p[2] == "1")
        }.orEmpty()
        // The alarm-era check still pending: carried so the next arm cancels or re-arms it.
        val legacyArmed = SharedPreferencesUtil.getPrefs(context, LEGACY_PRE_WAKE_PREFS).getLong(LEGACY_ARMED_WAKE_AT_MS, 0L).takeIf { it > 0L }
        return ChargeHistory.State(
            chargingSinceMs = since,
            lastSeenChargingMs = since?.let { prefs.getLong(LEGACY_LAST_SEEN_CHARGING_MS, it) },
            sessions = sessions,
            armedWakeAtMs = legacyArmed,
            armedSource = legacyArmed?.let { "legacy_alarm" },
        )
    }

    override fun save(state: ChargeHistory.State) {
        prefs.edit()
            .putString(KEY_STATE, state.encode())
            .remove(LEGACY_CHARGING_SINCE_MS)
            .remove(LEGACY_LAST_SEEN_CHARGING_MS)
            .remove(LEGACY_UNPLUGS)
            .apply()
        SharedPreferencesUtil.getPrefs(context, LEGACY_PRE_WAKE_PREFS).edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "charge_session_prefs"
        const val KEY_STATE = "charge_history"
        const val LEGACY_CHARGING_SINCE_MS = "charging_since_ms"
        const val LEGACY_LAST_SEEN_CHARGING_MS = "last_seen_charging_ms"
        const val LEGACY_UNPLUGS = "unplugs"
        const val LEGACY_PRE_WAKE_PREFS = "pre_wake_fetch_prefs"
        const val LEGACY_ARMED_WAKE_AT_MS = "armed_wake_at_ms"
    }
}
