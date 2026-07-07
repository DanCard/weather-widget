package com.weatherwidget.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.LocationMode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Samples the cached Fused last-known location and auto-heals every widget's configured location
 * when it is no longer same-site with what's stored
 * ([LocationMatch.sameSite][com.weatherwidget.data.local.LocationMatch]).
 *
 * No heal path here requests an active GPS fix: passive [lastLocation][com.google.android.gms.location.FusedLocationProviderClient.lastLocation]
 * reads don't power up GPS and don't trigger Samsung's "app got your precise location" notice.
 * (The only active fix in the app is the user-initiated "Use precise device location" button in
 * [ConfigActivity][com.weatherwidget.ui.ConfigActivity] — foreground and explicit.) The cache is
 * refreshed whenever any other app on the device obtains a fix, which is sufficient for
 * weather-granularity healing; when the cache is empty the run is a no-op (best-effort).
 *
 * When the user pins a location (zip/address/coordinates → [LocationMode.FIXED]), both heal paths
 * skip with an `outcome=skipped_pinned` breadcrumb so a deliberate choice is never clobbered.
 *
 * The location acquisition, permission check, and heal application are injected so the decision
 * pipeline is unit-testable without Play services or WorkManager; production wiring in
 * [com.weatherwidget.di.AppModule] uses the defaults. Every run leaves a [LOG_TAG] breadcrumb in
 * app_logs recording the outcome, queryable from a pulled DB.
 */
class GpsResampler(
    private val appLogDao: AppLogDao,
    private val sharedLocationResolver: SharedLocationResolver,
    private val locationProvider: suspend (context: Context) -> Location? = ::awaitLastLocation,
    private val permissionChecker: (Context, String) -> Boolean = { context, permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    },
    private val applyHeal: (Context, Double, Double, String) -> Unit = { context, lat, lon, label ->
        LocationUpdater.applyToAllWidgets(context, lat, lon, label)
    },
) {
    /**
     * Background entry point (worker): permission check → cached location → [healIfNeeded].
     */
    suspend fun resample(context: Context) {
        if (!permissionChecker(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            appLogDao.log(LOG_TAG, "outcome=skipped_no_permission")
            return
        }

        // Checked again in healIfNeeded; checking here too skips the Play services call.
        if (LocationMode.get(context) == LocationMode.FIXED) {
            appLogDao.log(LOG_TAG, "outcome=skipped_pinned trigger=worker")
            return
        }

        val location = locationProvider(context)
        if (location == null) {
            appLogDao.log(LOG_TAG, "outcome=no_fix mode=last_location")
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
        if (LocationMode.get(context) == LocationMode.FIXED) {
            appLogDao.log(LOG_TAG, "outcome=skipped_pinned trigger=$trigger")
            return false
        }
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
         * Production location source: the passive cached fix only. Suspends until the Play
         * services task completes; resolves null on any failure (best-effort, never throws).
         */
        suspend fun awaitLastLocation(context: Context): Location? =
            try {
                val task = LocationServices.getFusedLocationProviderClient(context).lastLocation
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
