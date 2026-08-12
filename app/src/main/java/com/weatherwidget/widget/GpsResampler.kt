package com.weatherwidget.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.log
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.ui.LocationUpdater
import com.weatherwidget.util.LocationMode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Samples the cached Fused last-known location and proposes a display-location candidate when it
 * is no longer same-site with the active widget location
 * ([LocationMatch.sameSite][com.weatherwidget.data.local.LocationMatch]).
 *
 * No handoff path here requests an active GPS fix: passive [lastLocation][com.google.android.gms.location.FusedLocationProviderClient.lastLocation]
 * reads don't power up GPS and don't trigger Samsung's "app got your precise location" notice.
 * (The only active fix in the app is the user-initiated "Use precise device location" button in
 * [ConfigActivity][com.weatherwidget.ui.ConfigActivity] — foreground and explicit.) The cache is
 * refreshed whenever any other app on the device obtains a fix, which is sufficient for
 * weather-granularity healing; when the cache is empty the run is a no-op (best-effort).
 *
 * When the user pins a location (zip/address/coordinates → [LocationMode.FIXED]), both heal paths
 * skip with an `outcome=skipped_pinned` breadcrumb so a deliberate choice is never clobbered.
 *
 * The location acquisition, permission check, and candidate proposal are injected so the decision
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
    private val proposeCandidate: (Context, Double, Double, String, Boolean) -> Boolean =
        { context, lat, lon, label, enqueueRefresh ->
            LocationUpdater.proposeFollowDeviceLocation(context, lat, lon, label, enqueueRefresh) ==
                CandidateProposal.UPDATED
    },
) {
    /**
     * Background entry point: permission check → cached location → [healIfNeeded].
     *
     * [trigger] names the caller in the breadcrumb and, via [healIfNeeded], decides whether a newly
     * detected candidate also enqueues a refresh: the periodic worker is already mid-sync so it
     * fetches the candidate itself, while event-driven callers (charger plug-in, unlock) must ask
     * for one. Keep the worker's value as `"worker"` for that reason.
     */
    suspend fun resample(context: Context, trigger: String = "worker"): Boolean {
        if (!permissionChecker(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            appLogDao.log(LOG_TAG, "outcome=skipped_no_permission trigger=$trigger")
            return false
        }

        // Checked again in healIfNeeded; checking here too skips the Play services call.
        if (LocationMode.get(context) == LocationMode.FIXED) {
            appLogDao.log(LOG_TAG, "outcome=skipped_pinned trigger=$trigger")
            return false
        }

        val location = locationProvider(context)
        if (location == null) {
            appLogDao.log(LOG_TAG, "outcome=no_fix mode=last_location trigger=$trigger")
            return false
        }
        return healIfNeeded(context, location.latitude, location.longitude, trigger = trigger)
    }

    /**
     * Shared tail of the background and foreground sampling paths. A different fix becomes a
     * candidate; the configured/active coordinates remain unchanged until candidate weather is
     * useful enough for the current widget views.
     *
     * @return true when candidate state changed, allowing the current worker to force one fetch.
     */
    suspend fun healIfNeeded(context: Context, lat: Double, lon: Double, trigger: String): Boolean {
        if (LocationMode.get(context) == LocationMode.FIXED) {
            appLogDao.log(LOG_TAG, "outcome=skipped_pinned trigger=$trigger")
            return false
        }
        val ids = LocationUpdater.getWidgetIds(context)
        if (ids.isEmpty()) {
            appLogDao.log(LOG_TAG, "outcome=same_site trigger=$trigger reason=no_widgets")
            return false
        }
        val stateManager = WidgetStateManager(context)
        // Null when the app has no location at all — the state this heal exists to escape. A fresh fix
        // can never be "the same site" as nothing, so it falls through to become a candidate.
        val active = ActiveLocationResolver.current(context)
            ?: ids.toList().firstNotNullOfOrNull(stateManager::getWidgetLocation)
        val existingCandidate = LocationHandoffStore.getCandidate(context)
        if (active != null && LocationMatch.sameSite(active.first, active.second, lat, lon)) {
            if (existingCandidate != null) {
                LocationHandoffStore.clear(context)
                appLogDao.log(LOG_TAG, "outcome=returned_to_active trigger=$trigger lat=$lat lon=$lon", "INFO")
                return true
            }
            appLogDao.log(LOG_TAG, "outcome=same_site trigger=$trigger lat=$lat lon=$lon")
            return false
        }
        if (existingCandidate != null && LocationMatch.sameSite(
                existingCandidate.location.lat,
                existingCandidate.location.lon,
                lat,
                lon,
            )
        ) {
            appLogDao.log(LOG_TAG, "outcome=candidate_pending trigger=$trigger lat=$lat lon=$lon")
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
        val candidateUpdated = proposeCandidate(context, lat, lon, label, trigger != "worker")
        appLogDao.log(
            LOG_TAG,
            "outcome=candidate_detected trigger=$trigger lat=$lat lon=$lon label=$label updated=$candidateUpdated",
            "INFO",
        )
        return candidateUpdated
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
