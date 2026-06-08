package com.weatherwidget.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object DeviceUtils {
    /**
     * Detects if the current device is an emulator.
     */
    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    /**
     * Checks if the device has a standard GPS reporter.
     * We consider a device to "report GPS" if it's NOT an emulator AND has GPS hardware.
     */
    fun reportsStandardGps(context: Context): Boolean {
        return !isEmulator() && context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
    }
}
