package com.weatherwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.FriendlyLocationName
import com.weatherwidget.util.LocationMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * The location setup screen, serving two entry points:
 * - Widget configuration (APPWIDGET_CONFIGURE): saves to the single widget's prefs and completes
 *   the RESULT_OK handshake.
 * - Settings ("Set Location…", launched with [EXTRA_GLOBAL_CONFIG]): applies to all widgets via
 *   [LocationUpdater.applyToAllWidgets] and finishes without a result.
 *
 * "Use precise device location" sets [LocationMode.FOLLOW_DEVICE] (background auto-heal keeps
 * widgets tracking the device); search results and manual coordinates set [LocationMode.FIXED],
 * which pins the choice against the auto-heal.
 *
 * Widget setup auto-fills but never auto-exits: the device fix starts on open and, once
 * resolved, turns the GPS button into a one-tap "Use this location" confirm — the screen only
 * closes on an explicit user choice (confirm, search pick, or coordinates). An earlier version
 * saved and finished as soon as the auto-started fix resolved, which yanked the screen away
 * from users who wanted to pick a location. Only a user-tapped GPS request saves directly.
 *
 * Backing out of widget setup still completes the handshake ([completeWidgetAddOnExit]):
 * RESULT_CANCELED makes the launcher delete the pending widget, which users experience as
 * "adding the widget failed" after they'd already granted permissions (2026-07-09). Back in
 * global (Settings) mode remains "leave without changes".
 */
@AndroidEntryPoint
class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isGlobalMode = false

    /** True while the fix flow was started by onCreate rather than a user tap. */
    private var autoFillFlow = false

    /** Device fix resolved by the auto-fill flow, awaiting the user's confirm tap. */
    private var prefetchedFix: LocationFixFlow.Coordinates? = null

    /** Set by [finishWithSuccess]; onDestroy logs any widget-add exit that never got here. */
    private var completedOk = false

    /** Guards the one-time setup network decision and duplicate save taps. */
    private var saveInProgress = false
    private var saveJob: Job? = null

    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var sharedLocationResolver: com.weatherwidget.data.repository.SharedLocationResolver

    @Inject
    lateinit var setupSourceSelector: SetupSourceSelector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        setResult(RESULT_CANCELED)

        isGlobalMode = intent?.getBooleanExtra(EXTRA_GLOBAL_CONFIG, false) ?: false
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        logConfig(
            "OPEN widget=$appWidgetId global=$isGlobalMode " +
                "finePerm=${hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)} " +
                "bgPerm=${hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}",
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !isGlobalMode) {
            logConfig("RESULT outcome=invalid_widget_id", "WARN")
            finish()
            return
        }

        if (isGlobalMode) {
            findViewById<TextView>(R.id.config_title).setText(R.string.widget_location_title)
        }

        setupViews()

        // Initial widget setup defaults to the precise-location flow: most users want the widget
        // to follow the device, so kick off permissions → fix right away. The auto-fill flow
        // only pre-resolves the fix for a one-tap confirm — it never saves or finishes on its
        // own. Never auto-start from the Settings entry (savedInstanceState guard avoids
        // re-firing on configuration changes).
        if (!isGlobalMode && savedInstanceState == null) {
            autoFillFlow = true
            checkAndRequestLocationPermissions()
        }

        // System back (gesture or hardware key) must take the same save-and-complete path as
        // the in-app back button; the default dispatcher behavior would finish RESULT_CANCELED.
        if (!isGlobalMode) {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        completeWidgetAddOnExit("system_back")
                    }
                },
            )
        }
    }

    private fun setupViews() {
        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        val searchInput = findViewById<EditText>(R.id.location_search_input)
        val searchButton = findViewById<Button>(R.id.search_location_button)
        val latInput = findViewById<EditText>(R.id.lat_input)
        val lonInput = findViewById<EditText>(R.id.lon_input)
        val useCoordinatesButton = findViewById<Button>(R.id.use_coordinates_button)

        findViewById<TextView>(R.id.current_location_label).text =
            LocationUpdater.describeCurrentLocation(this)
        // Enrich with a reverse-geocoded place name once resolved (instant when cached).
        lifecycleScope.launch {
            val resolved = LocationUpdater.describeCurrentLocationResolved(this@ConfigActivity, sharedLocationResolver)
            findViewById<TextView>(R.id.current_location_label).text = resolved
        }

        // Widget-add: back saves a best-effort location so the pending widget survives.
        // Global (Settings) mode: leave without changes.
        findViewById<ImageButton>(R.id.config_back_button).setOnClickListener {
            if (isGlobalMode) {
                logConfig("BACK_TAP widget=$appWidgetId global=true")
                finish()
            } else {
                completeWidgetAddOnExit("back_tap")
            }
        }

        useGpsButton.setOnClickListener {
            // Confirm tap on an auto-filled fix: the coordinates are already resolved.
            prefetchedFix?.let { fix ->
                saveChosenLocation(fix.lat, fix.lon, null, LocationMode.FOLLOW_DEVICE)
                return@setOnClickListener
            }
            autoFillFlow = false
            checkAndRequestLocationPermissions()
        }

        searchButton.setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, getString(R.string.location_search_hint), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            searchButton.isEnabled = false
            lifecycleScope.launch {
                val results = sharedLocationResolver.searchText(query)
                searchButton.isEnabled = true
                if (results.isEmpty()) {
                    Toast.makeText(this@ConfigActivity, getString(R.string.location_search_no_results), Toast.LENGTH_SHORT).show()
                } else {
                    // A single match still goes through the dialog so the user confirms the label.
                    AlertDialog.Builder(this@ConfigActivity)
                        .setTitle(query)
                        .setItems(results.map { it.label }.toTypedArray()) { _, which ->
                            val chosen = results[which]
                            saveChosenLocation(chosen.lat, chosen.lon, chosen.label, LocationMode.FIXED)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }

        useCoordinatesButton.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull()
            val lon = lonInput.text.toString().toDoubleOrNull()
            if (lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0) {
                lifecycleScope.launch {
                    try {
                        val resolved = sharedLocationResolver.fromCoordinates(lat, lon)
                        Toast.makeText(this@ConfigActivity, getString(R.string.location_label_toast, resolved.label), Toast.LENGTH_LONG).show()
                        saveChosenLocation(lat, lon, resolved.label, LocationMode.FIXED)
                    } catch (e: Exception) {
                        Toast.makeText(this@ConfigActivity, getString(R.string.saving_coordinates_offline), Toast.LENGTH_SHORT).show()
                        saveChosenLocation(lat, lon, null, LocationMode.FIXED)
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.invalid_coordinates_range), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAndRequestLocationPermissions() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            checkAndRequestBackgroundLocation()
        }
    }

    private fun checkAndRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocationGranted) {
                showBackgroundLocationDisclosureDialog()
            } else {
                getCurrentLocation()
            }
        } else {
            getCurrentLocation()
        }
    }

    private fun showBackgroundLocationDisclosureDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_disclosure_title)
            .setMessage(R.string.background_location_disclosure_desc)
            .setPositiveButton(R.string.allow) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_PERMISSION_REQUEST
                )
            }
            .setNegativeButton(R.string.no_thanks) { _, _ ->
                getCurrentLocation() // Proceed with foreground only
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                logConfig("PERM_RESULT request=fine granted=$granted widget=$appWidgetId")
                if (granted) {
                    checkAndRequestBackgroundLocation()
                } else {
                    Toast.makeText(this, getString(R.string.location_permission_required), Toast.LENGTH_SHORT).show()
                }
            }
            BACKGROUND_LOCATION_PERMISSION_REQUEST -> {
                logConfig("PERM_RESULT request=background granted=$granted widget=$appWidgetId")
                getCurrentLocation() // Proceed regardless, system handles denied state
            }
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // The fix runs while the user is looking at this screen; the button itself must show
        // the in-flight state. A toast alone disappears in 2s while the fix can legitimately
        // take up to the LocationFixFlow timeouts, and a silently-disabled button reads as
        // broken — users back out, which cancels the widget-add handshake.
        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        useGpsButton.isEnabled = false
        useGpsButton.setText(R.string.getting_location)

        val stages = locationStagesForTesting ?: fusedLocationStages()
        val startMs = SystemClock.elapsedRealtime()

        val isAutoFill = autoFillFlow
        // Unowned log: the GPS_FIX outcome below is lifecycle-scoped and vanishes if the user
        // backs out mid-fix, so the flow's start must land independently.
        logConfig("FIX_START mode=${if (isAutoFill) "auto" else "manual"} widget=$appWidgetId")
        lifecycleScope.launch {
            val outcome = LocationFixFlow().resolve(
                activeFix = stages.activeFix,
                cachedFix = stages.cachedFix,
            )
            appLogDao.log(
                "CONFIG",
                "GPS_FIX outcome=${outcome.source} mode=${if (isAutoFill) "auto" else "manual"} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startMs} widget=$appWidgetId global=$isGlobalMode",
            )
            when (outcome) {
                is LocationFixFlow.Outcome.Fix ->
                    if (isAutoFill) {
                        offerPrefetchedFix(outcome.coordinates)
                    } else {
                        saveChosenLocation(outcome.coordinates.lat, outcome.coordinates.lon, null, LocationMode.FOLLOW_DEVICE)
                    }
                LocationFixFlow.Outcome.Default ->
                    if (isAutoFill) {
                        // Leave the screen open with all options; no location is saved.
                        useGpsButton.isEnabled = true
                        useGpsButton.setText(R.string.use_precise_location)
                        Toast.makeText(this@ConfigActivity, getString(R.string.location_fix_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ConfigActivity, getString(R.string.location_fix_failed_default), Toast.LENGTH_SHORT).show()
                        // Still FOLLOW_DEVICE — the auto-heal can later replace the placeholder
                        // with a real fix.
                        saveChosenLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, null, LocationMode.FOLLOW_DEVICE)
                    }
            }
        }
    }

    /**
     * The real fix stages, backed by the fused-location client.
     *
     * Active fix, deliberately: this is the ONE exception to the app's passive-lastLocation
     * rule. Samsung's "app got your precise location" notice targets background access; here
     * the user explicitly tapped "Use precise device location" in a foreground screen (or is
     * on the auto-filled setup screen for the widget they just added), so a fresh fix is
     * expected. Every background path must stay passive (see GpsResampler).
     */
    private fun fusedLocationStages(): LocationStages {
        val client = LocationServices.getFusedLocationProviderClient(this)
        return LocationStages(
            activeFix = { client.activeFixOrNull() },
            cachedFix = { client.lastLocation.awaitOrNull()?.toCoordinates() },
        )
    }

    /** Auto-fill resolved: surface the fix and wait for the user's confirm tap. */
    private fun offerPrefetchedFix(fix: LocationFixFlow.Coordinates) {
        prefetchedFix = fix
        val coordsText = String.format(Locale.US, "%.4f, %.4f", fix.lat, fix.lon)
        findViewById<TextView>(R.id.current_location_label).text =
            getString(R.string.location_found_label, coordsText)
        // Upgrade the coordinates to "Name (coords)" once the reverse geocode lands, unless a
        // newer fix has replaced this one in the meantime.
        lifecycleScope.launch {
            val name = FriendlyLocationName.resolve(this@ConfigActivity, sharedLocationResolver, fix.lat, fix.lon)
            if (name != null && prefetchedFix == fix) {
                findViewById<TextView>(R.id.current_location_label).text =
                    getString(R.string.location_found_label, "$name ($coordsText)")
            }
        }
        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        useGpsButton.isEnabled = true
        useGpsButton.setText(R.string.use_this_location)
    }

    private suspend fun com.google.android.gms.location.FusedLocationProviderClient.activeFixOrNull(): LocationFixFlow.Coordinates? {
        val cancellation = CancellationTokenSource()
        return try {
            getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .awaitOrNull()
                ?.toCoordinates()
        } finally {
            // Reached on LocationFixFlow timeout too: stop the GPS request instead of
            // leaving it running after we've moved on to the cached fix.
            cancellation.cancel()
        }
    }

    private fun android.location.Location.toCoordinates() =
        LocationFixFlow.Coordinates(latitude, longitude)

    /** Failure and cancellation both resolve to null; LocationFixFlow treats null as "next stage". */
    private suspend fun <T> Task<T>.awaitOrNull(): T? =
        suspendCancellableCoroutine { cont ->
            addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }

    /**
     * Single save sink for all four options. The application has one active location: its worker,
     * startup renderer, Settings surface, and GPS handoff all operate on one site. Coordinates are
     * replicated into the per-widget preference keys for compatibility, but every save synchronizes
     * all placed widgets rather than creating unsupported per-widget locations.
     */
    private fun saveChosenLocation(lat: Double, lon: Double, label: String?, mode: String) {
        if (isGlobalMode) {
            LocationMode.set(this, mode)
            if (label != null) {
                finishGlobalSave(lat, lon, label, mode)
            } else {
                lifecycleScope.launch {
                    val resolvedLabel = try {
                        sharedLocationResolver.fromCoordinates(lat, lon).label
                    } catch (e: Exception) {
                        String.format(Locale.US, "%.4f, %.4f", lat, lon)
                    }
                    finishGlobalSave(lat, lon, resolvedLabel, mode)
                }
            }
            return
        }

        if (saveInProgress || completedOk) {
            logConfig("SAVE_IGNORED widget=$appWidgetId inProgress=$saveInProgress completed=$completedOk")
            return
        }
        saveInProgress = true
        setSaveControlsEnabled(false)

        val widgetIds =
            (LocationUpdater.getWidgetIds(this).toList() + appWidgetId)
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .distinct()
                .toIntArray()
        saveJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val currentSources = widgetStateManager.getVisibleSourcesOrder()
            val selection = try {
                setupSourceSelectorForTesting?.invoke(currentSources, lat, lon)
                    ?: setupSourceSelector.select(currentSources, lat, lon)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SetupSourceSelection(
                    sources = currentSources,
                    nwsCoverage = SetupNwsCoverage.INCONCLUSIVE,
                    reason = "selector_${e.javaClass.simpleName}",
                )
            }
            if (!isActive) return@launch

            val sourceChanged = selection.sources != currentSources
            logSetupDecision(
                "widget=$appWidgetId lat=$lat lon=$lon " +
                    "result=${selection.nwsCoverage.name.lowercase(Locale.US)} " +
                    "weatherapi=${selection.weatherApiAvailability.name.lowercase(Locale.US)} " +
                    "reason=${selection.reason ?: "none"} " +
                    "sourceChange=${if (sourceChanged) "updated" else "none"} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )

            widgetStateManager.setVisibleSourcesOrderForSetup(selection.sources, widgetIds)
            LocationMode.set(this@ConfigActivity, mode)
            persistWidgetLocation(lat, lon, label, mode, widgetIds)
            finishWithSuccess()
        }.also { job ->
            job.invokeOnCompletion {
                if (!completedOk) {
                    runOnUiThread {
                        saveInProgress = false
                        setSaveControlsEnabled(true)
                    }
                }
            }
        }
    }

    private fun persistWidgetLocation(
        lat: Double,
        lon: Double,
        label: String?,
        mode: String,
        widgetIds: IntArray,
    ) {
        LocationUpdater.applyActiveLocationToAllWidgets(
            context = this,
            lat = lat,
            lon = lon,
            label = label,
            ids = widgetIds,
        )

        lifecycleScope.launch {
            appLogDao.log("CONFIG", "Widget $appWidgetId configured with lat=$lat, lon=$lon mode=$mode")
        }
    }

    private fun setSaveControlsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.use_gps_button)?.isEnabled = enabled
        findViewById<Button>(R.id.search_location_button)?.isEnabled = enabled
        findViewById<Button>(R.id.use_coordinates_button)?.isEnabled = enabled
        findViewById<EditText>(R.id.location_search_input)?.isEnabled = enabled
        findViewById<EditText>(R.id.lat_input)?.isEnabled = enabled
        findViewById<EditText>(R.id.lon_input)?.isEnabled = enabled
    }

    private fun finishGlobalSave(lat: Double, lon: Double, label: String, mode: String) {
        // applyToAllWidgets enqueues its own force-refresh worker.
        LocationUpdater.applyToAllWidgets(this, lat, lon, label)
        lifecycleScope.launch {
            appLogDao.log("CONFIG", "Global location set lat=$lat lon=$lon mode=$mode label=$label")
        }
        Toast.makeText(this, getString(R.string.location_saved_success), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun triggerWidgetUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>().build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    /**
     * Back during widget-add completes the handshake with the best location available instead
     * of cancelling (RESULT_CANCELED = launcher deletes the pending widget):
     * - the prefetched device fix, if the auto-fill flow resolved one;
     * - nothing, if the user has a pinned (FIXED) location — [ActiveLocationResolver] already
     *   covers the new widget from the other widgets' prefs, and writing FOLLOW_DEVICE here
     *   would unpin every widget;
     * - otherwise the FOLLOW_DEVICE default placeholder, which the GPS auto-heal later
     *   replaces with a real fix (same as the manual GPS-failure path).
     */
    private fun completeWidgetAddOnExit(trigger: String) {
        val cancelledPendingCheck = saveInProgress
        if (cancelledPendingCheck) {
            saveJob?.cancel()
            saveJob = null
            saveInProgress = false
            setSaveControlsEnabled(true)
            logConfig("BACK_SAVE_CANCELLED_CHECK trigger=$trigger widget=$appWidgetId")
        }
        val fix = prefetchedFix
        val pinned = LocationMode.get(this) == LocationMode.FIXED
        logConfig("BACK_SAVE trigger=$trigger widget=$appWidgetId usedFix=${fix != null} pinned=$pinned")
        when {
            fix != null && cancelledPendingCheck ->
                finishWidgetLocationWithoutSourceCheck(fix.lat, fix.lon, null, LocationMode.FOLLOW_DEVICE)
            fix != null -> saveChosenLocation(fix.lat, fix.lon, null, LocationMode.FOLLOW_DEVICE)
            pinned -> {
                triggerWidgetUpdate()
                finishWithSuccess()
            }
            cancelledPendingCheck -> finishWidgetLocationWithoutSourceCheck(
                WeatherWidgetWorker.DEFAULT_LAT,
                WeatherWidgetWorker.DEFAULT_LON,
                null,
                LocationMode.FOLLOW_DEVICE,
            )
            else -> saveChosenLocation(
                WeatherWidgetWorker.DEFAULT_LAT,
                WeatherWidgetWorker.DEFAULT_LON,
                null,
                LocationMode.FOLLOW_DEVICE,
            )
        }
    }

    private fun finishWidgetLocationWithoutSourceCheck(
        lat: Double,
        lon: Double,
        label: String?,
        mode: String,
    ) {
        val widgetIds =
            (LocationUpdater.getWidgetIds(this).toList() + appWidgetId)
                .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
                .distinct()
                .toIntArray()
        LocationMode.set(this, mode)
        persistWidgetLocation(lat, lon, label, mode, widgetIds)
        finishWithSuccess()
    }

    private fun finishWithSuccess() {
        completedOk = true
        logConfig("RESULT outcome=saved widget=$appWidgetId", "INFO")
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    /**
     * Catch-all for any exit that skipped [finishWithSuccess]. Back now saves-and-completes
     * via [completeWidgetAddOnExit], so this fires only for the leftover paths (task
     * swipe-away, launcher timeout) where RESULT_CANCELED still makes the launcher delete
     * the pending widget.
     */
    override fun onDestroy() {
        if (isFinishing && !isGlobalMode && !completedOk &&
            appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            logConfig(
                "RESULT outcome=cancelled widget=$appWidgetId prefetchedFix=${prefetchedFix != null} " +
                    "— launcher will delete the pending widget",
                "WARN",
            )
        }
        super.onDestroy()
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Fire-and-forget breadcrumb on an unowned scope: finish-path logs must outlive
     * lifecycleScope, which is cancelled at onDestroy before the DB insert runs.
     */
    private fun logConfig(message: String, level: String = "DEBUG") {
        CoroutineScope(Dispatchers.IO).launch {
            appLogDao.log("CONFIG", message, level)
        }
    }

    private fun logSetupDecision(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            appLogDao.log("NWS_SETUP_CHECK", message)
        }
    }

    /** The two [LocationFixFlow] stages, as a swappable pair. */
    internal class LocationStages(
        val activeFix: suspend () -> LocationFixFlow.Coordinates?,
        val cachedFix: suspend () -> LocationFixFlow.Coordinates?,
    )

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val BACKGROUND_LOCATION_PERMISSION_REQUEST = 1002
        const val PREFS_NAME = "weather_widget_prefs"
        const val KEY_LAT_PREFIX = "widget_lat_"
        const val KEY_LON_PREFIX = "widget_lon_"

        /** Launch extra: no widget id; saves apply to all widgets and no RESULT_OK is set. */
        const val EXTRA_GLOBAL_CONFIG = "extra_global_config"

        /**
         * Test seam for the fix stages. The auto-fill flow starts inside [onCreate], before a
         * test can reach the instance, so the override has to be static. Null leaves the real
         * fused-location client in place; tests must null it out again in teardown.
         */
        @VisibleForTesting
        internal var locationStagesForTesting: LocationStages? = null

        /**
         * Test seam for the setup-only source decision. Production always uses the injected
         * selector; tests replace network coverage and credential calls with deterministic data.
         */
        @VisibleForTesting
        internal var setupSourceSelectorForTesting:
            (suspend (List<com.weatherwidget.data.model.WeatherSource>, Double, Double) -> SetupSourceSelection)? = null
    }
}
