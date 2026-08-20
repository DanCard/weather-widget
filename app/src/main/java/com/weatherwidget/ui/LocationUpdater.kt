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
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.CandidateLocation
import com.weatherwidget.widget.CandidateProposal
import com.weatherwidget.widget.HandoffLocation
import com.weatherwidget.widget.LocationHandoffStore
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.tagTestModeEnqueue
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

    // `shouldHealTo` and `allWidgetsAtDefault` used to live here. Neither had a production caller: the
    // decision they described is made by GpsResampler.followDeviceIfMoved, which compares a fresh fix against
    // the stored coordinates with LocationMatch.sameSite and needs no separate "is it unset?" signal.
    // They were nevertheless cited as "the GPS auto-heal signal" by CLAUDE.md and three KDocs,
    // including the one justifying LegacyDefaultLocationMigration — so the code that gated nothing was
    // load-bearing for how the subsystem got explained, and reasoning from it led somewhere wrong.

    /**
     * The location the summary label describes: active → first widget. Null when there is none; the
     * label then reads [R.string.no_location_set] rather than inventing coordinates.
     *
     * It used to end in a `historical_pois` fallback, which made this label disagree with the widget
     * in exactly the state that matters. With no active and no widget location the widget paints
     * "No location — tap to set", while Settings read the last saved POI back and announced
     * "Default Location: Springfield" — a coordinate the app is not using, under the name of a concept
     * that no longer exists. The POI list stays what it is elsewhere: a source of *names*
     * ([FriendlyLocationName]), never of coordinates.
     */
    private fun effectiveLocation(context: Context): Pair<Double, Double>? {
        ActiveLocationResolver.current(context)?.let { return it }
        val ids = getWidgetIds(context)
        if (ids.isNotEmpty()) {
            WidgetStateManager(context).getWidgetLocation(ids[0])?.let { return it }
        }
        return null
    }

    /**
     * Human-readable summary of the effective location (active → first widget) plus whether it's
     * pinned or follows the device. Shown on both the Settings screen and the location setup screen
     * ([ConfigActivity]). Prepends a friendly place name when one is known locally;
     * [describeCurrentLocationResolved] adds a reverse-geocode fallback for the callers that can suspend.
     */
    fun describeCurrentLocation(context: Context): String =
        describe(context, effectiveLocation(context)) { lat, lon -> FriendlyLocationName.cached(context, lat, lon) }

    /** [describeCurrentLocation], but reverse-geocodes (and caches) a name when none is stored. */
    suspend fun describeCurrentLocationResolved(
        context: Context,
        resolver: com.weatherwidget.data.repository.SharedLocationResolver,
    ): String {
        val effective = effectiveLocation(context) ?: return describe(context, null) { _, _ -> null }
        val name = FriendlyLocationName.resolve(context, resolver, effective.first, effective.second)
        return describe(context, effective) { _, _ -> name }
    }

    private fun describe(
        context: Context,
        effective: Pair<Double, Double>?,
        nameLookup: (Double, Double) -> String?,
    ): String {
        val modeSuffix = if (LocationMode.get(context) == LocationMode.FIXED) {
            context.getString(R.string.location_mode_pinned)
        } else {
            context.getString(R.string.location_mode_follow)
        }
        if (effective == null) {
            // No coordinates to format. Reverse-geocoding is skipped entirely — there is nothing to
            // look up, and inventing a place name here is the bug this change set out to remove.
            return "${context.getString(R.string.no_location_set)} • $modeSuffix"
        }
        val latText = String.format("%.4f", effective.first)
        val lonText = String.format("%.4f", effective.second)
        val name = nameLookup(effective.first, effective.second)
        val labelText = if (name != null) {
            context.getString(R.string.widget_location_named_format, name, latText, lonText)
        } else {
            context.getString(R.string.widget_location_format, latText, lonText)
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
        applyActiveLocationToAllWidgets(context, lat, lon, label, ids)
    }

    /**
     * Enforces the app-wide active-location invariant for widget-add and Settings flows. The worker,
     * GPS handoff, and startup renderer all operate on one active site, so allowing widget setup to
     * write only one ID would create preferences the rest of the application cannot honor.
     */
    internal fun applyActiveLocationToAllWidgets(
        context: Context,
        lat: Double,
        lon: Double,
        label: String?,
        ids: IntArray = getWidgetIds(context),
    ) {
        LocationHandoffStore.clear(context)
        writeActiveLocation(context, lat, lon, ids)
        if (label != null) {
            recordHistoricalPoi(context, lat, lon, label)
        }
        enqueueForceRefresh(context)
    }

    /**
     * Records "no location at all" across [ids] — the placeholder for "GPS never resolved", now that
     * the placeholder is the absence of coordinates rather than Google HQ. With nothing stored, the next
     * sampled fix cannot be "the same site we already show" and is proposed as a candidate; meanwhile
     * the worker paints the no-location state instead of fetching weather for a coordinate nobody chose.
     */
    internal fun clearActiveLocationForAllWidgets(
        context: Context,
        ids: IntArray = getWidgetIds(context),
    ) {
        LocationHandoffStore.clear(context)
        ActiveLocationResolver.clear(context)
        WidgetStateManager(context).clearWidgetLocations(ids)
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
        // Stored only, matching GpsResampler.followDeviceIfMoved — an inferred coordinate here would make
        // propose() judge a fresh fix against a location the user never chose. Null when nothing is
        // configured yet, which propose() reads as "any fresh fix is an improvement."
        val active = ActiveLocationResolver.current(context)
            ?: ids.toList().firstNotNullOfOrNull(stateManager::getStoredWidgetLocation)
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
        ActiveLocationResolver.persist(context, lat, lon)
        val stateManager = WidgetStateManager(context)
        // Promotion clears the candidate immediately afterward. Persist active coordinates first
        // so a process death cannot leave neither durable active nor candidate state.
        stateManager.setWidgetLocations(ids, lat, lon)
        // The newly-written location's current observations were last fetched when the device was
        // previously there (if ever), so they are stale for this site. Refresh them now — location-
        // scoped freshness (FetchMetadata) lets this run immediately instead of inheriting the
        // previous site's cooldown. Battery-gated: this is a background location change, not a user
        // interaction (the interaction path uses userInteraction=true to bypass the battery gate).
        com.weatherwidget.widget.CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = "location_changed",
            opportunistic = true,
        )
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
                    .tagTestModeEnqueue()
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
                    .tagTestModeEnqueue()
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
