package com.weatherwidget.widget

import android.content.SharedPreferences
import com.weatherwidget.shared.notify.DominantTempWatchState

/**
 * Persistence for the one-shot "notify me when the dominant station's reading changes" watch.
 *
 * The armed flag and the baseline live in ONE store because they are one unit: arming without
 * clearing the baseline would compare a new watch against a reading from a previous one, and could
 * fire immediately on a stale number. Every write here goes through [setArmed] or [save], both of
 * which keep that invariant.
 *
 * The decision logic is platform-free in
 * [com.weatherwidget.shared.notify.DominantTempWatch]; this class only stores its state.
 */
internal class DominantTempWatchPreferences(
    private val prefs: SharedPreferences,
) {
    fun load(): DominantTempWatchState =
        DominantTempWatchState(
            armed = prefs.getBoolean(KEY_ARMED, false),
            baselineStationId = prefs.getString(KEY_BASELINE_STATION, null),
            baselineTempF = prefs.getFloat(KEY_BASELINE_TEMP_F, Float.NaN)
                .takeIf { it.isFinite() },
        )

    fun save(state: DominantTempWatchState) {
        prefs.edit().apply {
            putBoolean(KEY_ARMED, state.armed)
            if (state.baselineStationId == null) remove(KEY_BASELINE_STATION)
            else putString(KEY_BASELINE_STATION, state.baselineStationId)
            val baseline = state.baselineTempF
            if (baseline == null || !baseline.isFinite()) remove(KEY_BASELINE_TEMP_F)
            else putFloat(KEY_BASELINE_TEMP_F, baseline)
        }.apply()
    }

    fun isArmed(): Boolean = prefs.getBoolean(KEY_ARMED, false)

    /**
     * Arms or disarms, always dropping the baseline.
     *
     * Dropping it on ARM is the point: the first evaluation after arming must capture a fresh
     * baseline, so "changed" is measured from when the user asked, not from whatever the previous
     * watch happened to leave behind.
     */
    fun setArmed(armed: Boolean) {
        save(DominantTempWatchState(armed = armed))
    }

    companion object {
        const val KEY_ARMED = "notify_dominant_temp_change"
        private const val KEY_BASELINE_STATION = "notify_dominant_temp_baseline_station"
        private const val KEY_BASELINE_TEMP_F = "notify_dominant_temp_baseline_f"
    }
}
