package com.weatherwidget.widget

import android.content.Context
import com.weatherwidget.shared.util.UnplugRoutine
import com.weatherwidget.util.SharedPreferencesUtil
import java.time.ZoneId

/**
 * Records when overnight charges end, for [UnplugRoutine] to learn the user's unplug time
 * (performance/261019-learned-unplug-time-and-overnight-short-skip.md).
 *
 * The app cannot hear an unplug in the background (`ACTION_POWER_DISCONNECTED` in the manifest is
 * never delivered at targetSdk 26+, and JobScheduler has no "not charging" constraint), so it combines
 * two observations: [ChargeStateReceiver], registered at runtime, gives the exact time while the
 * process lives; otherwise the last battery sighting while charging bounds it — the charging loop
 * sees the battery every 10–16 min, so that sighting is at most about that early. A sighting gap
 * over [MAX_ESTIMATE_GAP_MS] leaves the unplug unbounded and it is not recorded.
 *
 * Prefs only: times of day stay on the device, trimmed to [MAX_UNPLUGS].
 */
internal object ChargeSessionTracker {
    private const val PREFS_NAME = "charge_session_prefs"
    private const val KEY_CHARGING_SINCE_MS = "charging_since_ms"
    private const val KEY_LAST_SEEN_CHARGING_MS = "last_seen_charging_ms"
    private const val KEY_UNPLUGS = "unplugs"
    const val LOG_TAG = "UNPLUG_RECORDED"

    const val MAX_UNPLUGS = 28
    const val MAX_ESTIMATE_GAP_MS = 45 * 60_000L

    /**
     * One battery observation. [exact] when it is the power broadcast itself. Returns an
     * `UNPLUG_RECORDED` log line when a charge ended (recorded or not), else null.
     */
    @Synchronized
    fun observe(context: Context, isCharging: Boolean, nowMs: Long = System.currentTimeMillis(), exact: Boolean = false): String? {
        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val since = prefs.getLong(KEY_CHARGING_SINCE_MS, 0L)
        if (isCharging) {
            prefs.edit()
                .apply { if (since == 0L) putLong(KEY_CHARGING_SINCE_MS, nowMs) }
                .putLong(KEY_LAST_SEEN_CHARGING_MS, nowMs)
                .apply()
            return null
        }
        if (since == 0L) return null
        val lastSeen = prefs.getLong(KEY_LAST_SEEN_CHARGING_MS, since)
        prefs.edit().remove(KEY_CHARGING_SINCE_MS).remove(KEY_LAST_SEEN_CHARGING_MS).apply()
        val unplugAt = if (exact) nowMs else lastSeen
        val sessionMin = ((unplugAt - since) / 60_000L).toInt()
        val estimate = if (exact) "exact" else "last_seen"
        if (!exact && nowMs - lastSeen > MAX_ESTIMATE_GAP_MS) {
            return "action=skipped reason=unbounded gapMin=${(nowMs - lastSeen) / 60_000L} sessionMin=$sessionMin"
        }
        if (sessionMin < UnplugRoutine.MIN_SESSION_HOURS * 60) {
            return "action=skipped reason=top_up sessionMin=$sessionMin estimate=$estimate"
        }
        val unplug = UnplugRoutine.Unplug(unplugAt, sessionMin, exact)
        // A desk's all-day USB charge is long too; only the night's charge is kept.
        if (!UnplugRoutine.isOvernight(unplug, ZoneId.systemDefault())) {
            return "action=skipped reason=daytime sessionMin=$sessionMin estimate=$estimate"
        }
        val unplugs = (unplugs(context) + unplug).takeLast(MAX_UNPLUGS)
        prefs.edit().putString(KEY_UNPLUGS, unplugs.joinToString(";") { "${it.atMs},${it.sessionMinutes},${if (it.exact) 1 else 0}" }).apply()
        return "action=recorded at=$unplugAt sessionMin=$sessionMin estimate=$estimate n=${unplugs.size}"
    }

    fun unplugs(context: Context): List<UnplugRoutine.Unplug> =
        SharedPreferencesUtil.getPrefs(context, PREFS_NAME).getString(KEY_UNPLUGS, null)
            ?.split(';')
            ?.mapNotNull { entry ->
                val p = entry.split(',')
                if (p.size != 3) return@mapNotNull null
                UnplugRoutine.Unplug(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null, p[2] == "1")
            }
            .orEmpty()
}
