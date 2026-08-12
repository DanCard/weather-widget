package com.weatherwidget.widget

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Single owner of the sticky [Intent.ACTION_BATTERY_CHANGED] read. Consolidates what used to be a
 * ~10-site copy-paste of `registerReceiver(null, ACTION_BATTERY_CHANGED)` + a level/charging read,
 * with divergent fallbacks (100 vs -1) and divergent level normalization (some scale-normalized,
 * most did not). The battery level is now always scale-normalized to 0..100, unknown -> -1.
 */
internal object BatterySnapshotProvider {
    fun snapshot(context: Context): BatterySnapshot {
        val status: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawLevel = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return BatterySnapshot(
            isCharging = BatteryStatePolicy.isEffectivelyCharging(status),
            batteryLevel = BatteryStatePolicy.batteryLevelPercent(rawLevel, scale),
        )
    }
}

internal data class BatterySnapshot(
    val isCharging: Boolean,
    val batteryLevel: Int,
)
