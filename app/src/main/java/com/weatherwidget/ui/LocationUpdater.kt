package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.R
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.util.FriendlyLocationName
import com.weatherwidget.util.LocationMode
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.CandidateLocation
import com.weatherwidget.widget.CandidateProposal
import com.weatherwidget.widget.HandoffLocation
import com.weatherwidget.widget.LocationHandoffStore
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager

/**
 * Owns explicit active-location updates and the passive follow-device candidate handoff.
 * User-confirmed locations update all widget coordinates immediately. GPS samples stay candidates
 * until their weather can produce a useful body.
 */
object LocationUpdater {

    fun getWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, WeatherWidgetProvider::class.java),
        )

    /**
     * Checks if any widget location needs to be auto-healed to the fresh location (i.e. is not same-site).
     */
    fun shouldHealTo(context: Context, freshLat: Double, freshLon: Double): Boolean {
        val ids = getWidgetIds(context)
        if (ids.isEmpty()) return false
        val stateManager = WidgetStateManager(context)
        return ids.any { id ->
            val loc = stateManager.getWidgetLocation(id) ?: (WeatherWidgetWorker.DEFAULT_LAT to WeatherWidgetWorker.DEFAULT_LON)
            !LocationMatch.sameSite(loc.first, loc.second, freshLat, freshLon)
        }
    }

    /**
     * True when every placed widget is still pinned to the hard default coordinates (or has no
     * location set yet). This is the signal that GPS never resolved — the auto-heal condition.
     * Returns false when there are no widgets (nothing to heal).
     */
    fun allWidgetsAtDefault(context: Context): Boolean {
        val ids = getWidgetIds(context)
        if (ids.isEmpty()) return false
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val defaultLat = WeatherWidgetWorker.DEFAULT_LAT.toFloat()
        val defaultLon = WeatherWidgetWorker.DEFAULT_LON.toFloat()
        return ids.all { id ->
            val lat = prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN)
            val lon = prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN)
            (lat.isNaN() || lon.isNaN()) || (lat == defaultLat && lon == defaultLon)
        }
    }

    /** The location the summary label describes: first widget → last historical POI → hard default. */
    private data class EffectiveLocation(val lat: Double, val lon: Double, val isWidgetLocation: Boolean)

    private fun effectiveLocation(context: Context): EffectiveLocation {
        val ids = getWidgetIds(context)
        if (ids.isNotEmpty()) {
            WidgetStateManager(context).getWidgetLocation(ids[0])?.let { (lat, lon) ->
                return EffectiveLocation(lat, lon, isWidgetLocation = true)
            }
        }

        // Fallback to historical_pois default POI
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        weatherPrefs.getString("historical_pois", null)
            ?.split(";")
            ?.lastOrNull()
            ?.split("|")
            ?.takeLast(3)
            ?.let { parts ->
                if (parts.size == 3) {
                    val lat = parts[1].toDoubleOrNull()
                    val lon = parts[2].toDoubleOrNull()
                    if (lat != null && lon != null) return EffectiveLocation(lat, lon, isWidgetLocation = false)
                }
            }

        return EffectiveLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, isWidgetLocation = false)
    }

    /**
     * Human-readable summary of the effective location (first widget → last historical POI →
     * hard default) plus whether it's pinned or follows the device. Shown on both the Settings
     * screen and the location setup screen ([ConfigActivity]). Prepends a friendly place name
     * when one is known locally; [describeCurrentLocationResolved] adds a reverse-geocode
     * fallback for the callers that can suspend.
     */
    fun describeCurrentLocation(context: Context): String =
        describe(context, effectiveLocation(context)) { lat, lon -> FriendlyLocationName.cached(context, lat, lon) }

    /** [describeCurrentLocation], but reverse-geocodes (and caches) a name when none is stored. */
    suspend fun describeCurrentLocationResolved(
        context: Context,
        resolver: com.weatherwidget.data.repository.SharedLocationResolver,
    ): String {
        val effective = effectiveLocation(context)
        val name = FriendlyLocationName.resolve(context, resolver, effective.lat, effective.lon)
        return describe(context, effective) { _, _ -> name }
    }

    private fun describe(
        context: Context,
        effective: EffectiveLocation,
        nameLookup: (Double, Double) -> String?,
    ): String {
        val latText = String.format("%.4f", effective.lat)
        val lonText = String.format("%.4f", effective.lon)
        val name = nameLookup(effective.lat, effective.lon)
        val labelText = when {
            name != null && effective.isWidgetLocation ->
                context.getString(R.string.widget_location_named_format, name, latText, lonText)
            name != null ->
                context.getString(R.string.default_location_named_format, name, latText, lonText)
            effective.isWidgetLocation ->
                context.getString(R.string.widget_location_format, latText, lonText)
            else ->
                context.getString(R.string.default_location_format, latText, lonText)
        }

        val modeSuffix = if (LocationMode.get(context) == LocationMode.FIXED) {
            context.getString(R.string.location_mode_pinned)
        } else {
            context.getString(R.string.location_mode_follow)
        }
        return "$labelText • $modeSuffix"
    }

    /**
     * Writes [lat]/[lon] to all widgets, records the POI, and force-refreshes. Mirrors the path that
     * the Settings "save location" button has always used. [ids] defaults to every placed widget;
     * tests pass synthetic ids so they never rewrite a real widget's configured location.
     */
    fun applyToAllWidgets(
        context: Context,
        lat: Double,
        lon: Double,
        label: String,
        ids: IntArray = getWidgetIds(context),
    ) {
        LocationHandoffStore.clear(context)
        writeActiveLocation(context, lat, lon, ids)
        recordHistoricalPoi(context, lat, lon, label)
        enqueueForceRefresh(context)
    }

    internal fun proposeFollowDeviceLocation(
        context: Context,
        lat: Double,
        lon: Double,
        label: String,
        enqueueRefresh: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        ids: IntArray = getWidgetIds(context),
    ): CandidateProposal {
        val stateManager = WidgetStateManager(context)
        val active = ids.toList().firstNotNullOfOrNull(stateManager::getWidgetLocation)
            ?: (WeatherWidgetWorker.DEFAULT_LAT to WeatherWidgetWorker.DEFAULT_LON)
        val proposal = LocationHandoffStore.propose(
            context = context,
            activeLocation = active,
            freshLocation = HandoffLocation(lat, lon, label),
            nowMs = nowMs,
        )
        if (proposal == CandidateProposal.UPDATED && enqueueRefresh) {
            enqueueCandidateRefresh(context)
        }
        return proposal
    }

    internal fun promoteCandidateIfMatches(
        context: Context,
        candidate: CandidateLocation,
        ids: IntArray = getWidgetIds(context),
    ): Boolean = LocationHandoffStore.promoteIfMatches(context, candidate) { location ->
        writeActiveLocation(context, location.lat, location.lon, ids)
        recordHistoricalPoi(context, location.lat, location.lon, location.label)
    }

    private fun writeActiveLocation(
        context: Context,
        lat: Double,
        lon: Double,
        ids: IntArray,
    ) {
        val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val editor = widgetPrefs.edit()
        for (id in ids) {
            editor.putFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", lat.toFloat())
            editor.putFloat("${ConfigActivity.KEY_LON_PREFIX}$id", lon.toFloat())
        }
        // Promotion clears the candidate immediately afterward. Persist active coordinates first
        // so a process death cannot leave neither durable active nor candidate state.
        editor.commit()
    }

    private fun recordHistoricalPoi(context: Context, lat: Double, lon: Double, label: String) {
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        val historicalPois = weatherPrefs.getString("historical_pois", null)
        val newPoi = "$label|$lat|$lon"
        val updatedPois = if (historicalPois.isNullOrBlank()) {
            newPoi
        } else {
            val pois = historicalPois.split(";").toMutableList()
            pois.removeAll { it.contains("|$lat|$lon") || it.startsWith("$label|") }
            pois.add(newPoi)
            pois.takeLast(5).joinToString(";")
        }
        weatherPrefs.edit().putString("historical_pois", updatedPois).apply()
    }

    private fun enqueueForceRefresh(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    private fun enqueueCandidateRefresh(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                    .putBoolean(WeatherWidgetWorker.KEY_LOCATION_CANDIDATE_REFRESH, true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetWorker.WORK_NAME_LOCATION_CANDIDATE,
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
    }
}
