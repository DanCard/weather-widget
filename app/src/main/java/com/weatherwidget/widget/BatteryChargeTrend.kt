package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import com.weatherwidget.shared.util.BatteryTier
import com.weatherwidget.util.SharedPreferencesUtil

/**
 * Persists the battery-level trend so charging can be inferred on devices whose platform charging
 * signal is unreliable.
 *
 * The decision itself is [BatteryTier.inferChargingFromLevelTrend]; this class only owns the two
 * values that decision needs to survive across process death — the last level seen and the last
 * verdict reached. Keeping the state here rather than in [BatterySnapshotProvider] leaves the
 * snapshot read a pure function of the sticky broadcast plus this one lookup.
 */
internal object BatteryChargeTrend {
    private const val TAG = "BatteryChargeTrend"
    private const val PREFS_NAME = "battery_charge_trend_prefs"
    private const val KEY_LAST_LEVEL = "last_level"
    private const val KEY_LAST_INFERENCE = "last_inference"

    /** Level value meaning "nothing recorded yet"; matches the unknown-level convention. */
    private const val NO_LEVEL = -1

    /**
     * Folds [currentLevel] into the stored trend and returns whether the device looks like it is
     * charging despite the platform saying otherwise.
     *
     * Writes only when something actually changed. This is called from every
     * [BatterySnapshotProvider.snapshot] site, several of which fire on widget-interaction paths,
     * and an unconditional commit on each would be a write per tap.
     */
    fun inferCharging(context: Context, currentLevel: Int): Boolean {
        if (currentLevel < 0) return false

        val prefs = SharedPreferencesUtil.getPrefs(context, PREFS_NAME)
        val previousLevel = prefs.getInt(KEY_LAST_LEVEL, NO_LEVEL)
        val previousInference = prefs.getBoolean(KEY_LAST_INFERENCE, false)

        val inference =
            BatteryTier.inferChargingFromLevelTrend(
                previousLevel = previousLevel,
                currentLevel = currentLevel,
                previousInference = previousInference,
            )

        // VERBOSE: this runs on every battery read, several per widget interaction. Log.v is
        // never persisted to app_logs and needs `setprop log.tag.BatteryChargeTrend VERBOSE` to
        // reach logcat, which is what keeps a per-read trace affordable to leave in.
        Log.v(TAG, "trend prev=$previousLevel cur=$currentLevel prevInf=$previousInference inf=$inference")
        if (previousLevel != currentLevel || previousInference != inference) {
            prefs.edit()
                .putInt(KEY_LAST_LEVEL, currentLevel)
                .putBoolean(KEY_LAST_INFERENCE, inference)
                .apply()
        }
        return inference
    }
}
