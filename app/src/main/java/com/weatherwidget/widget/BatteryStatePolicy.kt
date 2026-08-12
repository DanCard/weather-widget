package com.weatherwidget.widget

import android.content.Intent
import android.os.BatteryManager
import com.weatherwidget.shared.util.BatteryTier

object BatteryStatePolicy {
    fun isEffectivelyCharging(
        status: Int,
        plugged: Int,
        batteryLevel: Int,
    ): Boolean {
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged > 0 ||
            batteryLevel >= BatteryTier.FULL_BATTERY_LEVEL
    }

    fun isEffectivelyCharging(batteryStatus: Intent?): Boolean {
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val rawLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return isEffectivelyCharging(status, plugged, batteryLevelPercent(rawLevel, scale))
    }

    /**
     * Battery percentage normalized against [BatteryManager.EXTRA_SCALE], or -1 when unknown. Raw
     * [BatteryManager.EXTRA_LEVEL] is 0..EXTRA_SCALE, not necessarily 0..100; using it directly (as
     * this file once did) misreads level on devices with a non-100 scale.
     */
    fun batteryLevelPercent(rawLevel: Int, scale: Int): Int =
        if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else -1
}
