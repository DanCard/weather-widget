package com.weatherwidget.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weatherwidget.BuildConfig
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.DebugLocationOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * adb entry point for [DebugLocationOverride], so a location move can be driven on an emulator.
 *
 * ```
 * adb shell am broadcast -a com.weatherwidget.debug.SET_LOCATION \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver \
 *   --es lat 37.7749 --es lon -122.4194
 *
 * adb shell am broadcast -a com.weatherwidget.debug.CLEAR_LOCATION \
 *   -n com.weatherwidget/com.weatherwidget.debug.DebugLocationReceiver
 * ```
 *
 * The override is consumed by the passive background read only, so the next full sync picks it up —
 * `GPS_RESAMPLE` will report the injected coordinates and the handoff proceeds exactly as it would
 * for a real move. Nothing else in the app is redirected; see [DebugLocationOverride].
 *
 * This class lives in `src/debug`, so it is absent from a release build's manifest and dex. The
 * `BuildConfig.DEBUG` re-check below is belt-and-braces for the same reason the store has one: a
 * receiver that can move a user's weather location is worth two locks.
 *
 * Coordinates are passed as strings (`--es`, not `--ef`): `am`'s float extras are 32-bit, and a
 * coordinate through `Float` no longer equals the double it came from — the same precision trap
 * `LocationMatch.sameSite` comparisons keep falling into.
 */
class DebugLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val appContext = context.applicationContext
        val dao = WeatherDatabase.getDatabase(appContext).appLogDao()

        when (intent.action) {
            ACTION_SET -> {
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull()
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull()
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) {
                    Log.w(TAG, "SET_LOCATION ignored: lat/lon missing or unparseable")
                    record(dao, "outcome=rejected reason=bad_coordinates raw=${intent.extras?.getString("lat")}," +
                        "${intent.extras?.getString("lon")}")
                    return
                }
                DebugLocationOverride.set(appContext, lat, lon)
                Log.i(TAG, "location override set to $lat,$lon")
                record(dao, "outcome=set lat=$lat lon=$lon")
            }
            ACTION_CLEAR -> {
                DebugLocationOverride.clear(appContext)
                Log.i(TAG, "location override cleared")
                record(dao, "outcome=cleared")
            }
            else -> Log.w(TAG, "unhandled action ${intent.action}")
        }
    }

    /**
     * Persisted, not just logcat: an armed override changes what every later `GPS_RESAMPLE` line
     * means, and a pulled database that shows a location move with no record of why is exactly the
     * kind of evidence gap this session spent hours on.
     */
    private fun record(dao: com.weatherwidget.data.local.AppLogDao, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { dao.log(TAG, message, "WARN") }
        }
    }

    companion object {
        private const val TAG = "DEBUG_LOCATION_OVERRIDE"
        const val ACTION_SET = "com.weatherwidget.debug.SET_LOCATION"
        const val ACTION_CLEAR = "com.weatherwidget.debug.CLEAR_LOCATION"
    }
}
