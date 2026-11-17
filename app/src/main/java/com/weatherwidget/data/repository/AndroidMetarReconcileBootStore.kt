package com.weatherwidget.data.repository

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.weatherwidget.shared.observations.BootInfo
import com.weatherwidget.shared.observations.MetarReconcileBootStore

/**
 * Android half of [MetarReconcileBootStore]: `Settings.Global.BOOT_COUNT` for the boot, wall clock minus
 * `elapsedRealtime` for when it began, and a small preferences file, one `siteKey -> bootCount` entry
 * per site. Desktop's is `DesktopMetarReconcileBootStore`.
 * plans/261117-metar-reconcile-once-per-device-boot.md
 */
class AndroidMetarReconcileBootStore(private val context: Context) : MetarReconcileBootStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun currentBoot(nowMs: Long): BootInfo? {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (count < 0) {
            Log.w(TAG, "No BOOT_COUNT; reconciling on every pass")
            return null
        }
        return BootInfo(count.toString(), nowMs - SystemClock.elapsedRealtime())
    }

    override fun reconciledBootId(siteKey: String): String? = prefs.getString(siteKey, null)

    override fun markReconciled(siteKey: String, bootId: String) {
        prefs.edit().putString(siteKey, bootId).apply()
    }

    private companion object {
        const val TAG = "AndroidMetarReconcileBoot"
        const val PREFS_NAME = "nws_metar_reconcile_boot"
    }
}
