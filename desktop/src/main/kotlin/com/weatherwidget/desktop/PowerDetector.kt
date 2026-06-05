package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.io.File

/**
 * Detects whether the Linux system is running on AC power or battery.
 * Reads from /sys/class/power_supply/ which is standard for modern Linux kernels.
 */
object PowerDetector {
    private const val TAG = "PowerDetector"
    private const val POWER_SUPPLY_PATH = "/sys/class/power_supply"

    data class PowerState(val isCharging: Boolean, val batteryLevel: Int)

    /**
     * Determines current power state.
     * If no battery is found (e.g. desktop PC), returns isCharging=true, level=100.
     */
    fun getPowerState(): PowerState {
        try {
            val dir = File(POWER_SUPPLY_PATH)
            if (!dir.exists() || !dir.isDirectory) {
                return PowerState(isCharging = true, batteryLevel = 100)
            }

            val supplies = dir.listFiles() ?: return PowerState(isCharging = true, batteryLevel = 100)
            
            var acOnline = false
            var maxBatteryLevel = 0
            var batteryFound = false

            for (supply in supplies) {
                val type = readFile(File(supply, "type"))?.trim() ?: continue
                
                if (type == "Mains") {
                    val online = readFile(File(supply, "online"))?.trim()
                    if (online == "1") {
                        acOnline = true
                    }
                } else if (type == "Battery") {
                    batteryFound = true
                    val capacity = readFile(File(supply, "capacity"))?.trim()?.toIntOrNull() ?: 0
                    if (capacity > maxBatteryLevel) {
                        maxBatteryLevel = capacity
                    }
                    
                    // Some systems report charging status on the battery itself
                    val status = readFile(File(supply, "status"))?.trim()
                    if (status == "Charging" || status == "Full") {
                        acOnline = true
                    }
                }
            }

            // If no battery was ever found, we are almost certainly on a desktop with AC power.
            if (!batteryFound) {
                return PowerState(isCharging = true, batteryLevel = 100)
            }

            return PowerState(isCharging = acOnline, batteryLevel = maxBatteryLevel)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect power state, assuming AC: ${e.message}")
            return PowerState(isCharging = true, batteryLevel = 100)
        }
    }

    private fun readFile(file: File): String? {
        return if (file.exists() && file.canRead()) {
            file.readText().trim()
        } else null
    }
}
