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
        val batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        return isEffectivelyCharging(status, plugged, batteryLevel)
    }
}
