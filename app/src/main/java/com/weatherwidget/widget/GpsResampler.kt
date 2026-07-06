package com.weatherwidget.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.ui.LocationUpdater
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Samples a fresh GPS fix and auto-heals every widget's configured location when the fix is no
 * longer same-site with what's stored ([LocationMatch.sameSite][com.weatherwidget.data.local.LocationMatch]).
 * This is the only place the app actively powers up GPS in the background. It only requests active
 * GPS fixes when charging. When on battery (not charging), it uses Fused Location's last known location.
 *
 * The GPS acquisition, permission check, and heal application are injected so the decision
 * pipeline is unit-testable without Play services or WorkManager; production wiring in
 * [com.weatherwidget.di.AppModule] uses the defaults. Every run leaves a [LOG_TAG] breadcrumb in
 * app_logs recording the outcome, queryable from a pulled DB.
 */
class GpsResampler(
    private val appLogDao: AppLogDao,
    private val sharedLocationResolver: SharedLocationResolver,
    private val locationProvider: suspend (context: Context, useActiveFix: Boolean) -> Location? =
        ::awaitFusedFix,
    private val permissionChecker: (Context, String) -> Boolean = { context, permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    },
    private val applyHeal: (Context, Double, Double, String) -> Unit = { context, lat, lon, label ->
        LocationUpdater.applyToAllWidgets(context, lat, lon, label)
    },
) {
    /**
     * Background entry point (worker): permission check → GPS fix → [healIfNeeded].
     */
    suspend fun resample(context: Context) {
        val batteryStatus: Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)

        if (!permissionChecker(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            appLogDao.log(LOG_TAG, "outcome=skipped_no_permission")
            return
        }

        // Without background-location permission an active fix request would be rejected;
        // degrade to the passive last-known location (best-effort).
        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionChecker(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }

        // Only use active high-accuracy fix when charging and background location is available.
        // When in battery mode (not charging), always use passive last-known location (best-effort).
        val useActiveFix = isCharging && hasBackgroundLocation
        val mode = if (useActiveFix) "active_fix" else "last_location"

        val location = locationProvider(context, useActiveFix)
        if (location == null) {
            appLogDao.log(LOG_TAG, "outcome=no_fix mode=$mode")
            return
        }
        healIfNeeded(context, location.latitude, location.longitude, trigger = "worker")
    }

    /**
     * Shared tail of both heal paths (background worker and foreground [MainActivity][com.weatherwidget.ui.MainActivity]):
     * applies the fix to all widgets when any widget is not same-site with it.
     *
     * @return true when a heal was applied, so foreground callers can surface it (Toast).
     */
    suspend fun healIfNeeded(context: Context, lat: Double, lon: Double, trigger: String): Boolean {
        if (!LocationUpdater.shouldHealTo(context, lat, lon)) {
            appLogDao.log(LOG_TAG, "outcome=same_site trigger=$trigger lat=$lat lon=$lon")
            return false
        }
        val label = try {
            sharedLocationResolver.fromCoordinates(lat, lon).label
        } catch (e: Exception) {
            // Don't fail silently: a revoked permission or geocoder error would otherwise just
            // show raw coordinates with no clue why. Surface it to logcat/bug report.
            Log.w(TAG, "Location label lookup failed for ($lat, $lon); using raw coordinates", e)
            String.format("%.4f, %.4f", lat, lon)
        }
        applyHeal(context, lat, lon, label)
        appLogDao.log(LOG_TAG, "outcome=healed trigger=$trigger lat=$lat lon=$lon label=$label", "INFO")
        return true
    }

    companion object {
        private const val TAG = "GpsResampler"

        /** app_logs tag; one row per resample attempt with an outcome= token. */
        const val LOG_TAG = "GPS_RESAMPLE"

        /**
         * Production location source: active high-accuracy fix with a lastLocation fallback, or
         * lastLocation only when [useActiveFix] is false. Suspends until the Play services task
         * completes; resolves null on any failure (best-effort, never throws).
         */
        suspend fun awaitFusedFix(context: Context, useActiveFix: Boolean): Location? {
            val client = LocationServices.getFusedLocationProviderClient(context)
            return if (useActiveFix) {
                try {
                    val task = client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token,
                    )
                    suspendCancellableCoroutine { cont ->
                        task.addOnCompleteListener { t ->
                            if (t.isSuccessful && t.result != null) {
                                cont.resume(t.result)
                            } else {
                                cont.resume(null)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation failed in background, trying lastLocation", e)
                    awaitLastLocation(client)
                }
            } else {
                awaitLastLocation(client)
            }
        }

        private suspend fun awaitLastLocation(client: FusedLocationProviderClient): Location? =
            try {
                val task = client.lastLocation
                suspendCancellableCoroutine { cont ->
                    task.addOnCompleteListener { t ->
                        cont.resume(if (t.isSuccessful) t.result else null)
                    }
                }
            } catch (e: Exception) {
                null
            }
    }
}
