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
import com.weatherwidget.util.LocationMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
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
 */
@AndroidEntryPoint
class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isGlobalMode = false

    /** True while the fix flow was started by onCreate rather than a user tap. */
    private var autoFillFlow = false

    /** Device fix resolved by the auto-fill flow, awaiting the user's confirm tap. */
    private var prefetchedFix: LocationFixFlow.Coordinates? = null

    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var sharedLocationResolver: com.weatherwidget.data.repository.SharedLocationResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        setResult(RESULT_CANCELED)

        isGlobalMode = intent?.getBooleanExtra(EXTRA_GLOBAL_CONFIG, false) ?: false
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !isGlobalMode) {
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

        // Leave without changes; the widget-add handshake keeps the RESULT_CANCELED set in onCreate.
        findViewById<ImageButton>(R.id.config_back_button).setOnClickListener {
            finish()
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
                        Toast.makeText(this@ConfigActivity, "Location: ${resolved.label}", Toast.LENGTH_LONG).show()
                        saveChosenLocation(lat, lon, resolved.label, LocationMode.FIXED)
                    } catch (e: Exception) {
                        Toast.makeText(this@ConfigActivity, "Saving coordinates (Label lookup offline)", Toast.LENGTH_SHORT).show()
                        saveChosenLocation(lat, lon, null, LocationMode.FIXED)
                    }
                }
            } else {
                Toast.makeText(this, "Please enter valid coordinates (-90 to 90 lat, -180 to 180 lon)", Toast.LENGTH_SHORT).show()
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
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkAndRequestBackgroundLocation()
                } else {
                    Toast.makeText(this, "Location permission required for GPS", Toast.LENGTH_SHORT).show()
                }
            }
            BACKGROUND_LOCATION_PERMISSION_REQUEST -> {
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

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val startMs = SystemClock.elapsedRealtime()

        val isAutoFill = autoFillFlow
        lifecycleScope.launch {
            val outcome = LocationFixFlow().resolve(
                // Active fix, deliberately: this is the ONE exception to the app's passive-
                // lastLocation rule. Samsung's "app got your precise location" notice targets
                // background access; here the user explicitly tapped "Use precise device
                // location" in a foreground screen (or is on the auto-filled setup screen for
                // the widget they just added), so a fresh fix is expected. Every background
                // path must stay passive (see GpsResampler).
                activeFix = { fusedLocationClient.activeFixOrNull() },
                cachedFix = { fusedLocationClient.lastLocation.awaitOrNull()?.toCoordinates() },
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
                        Toast.makeText(this@ConfigActivity, "Could not get current location. Using default.", Toast.LENGTH_SHORT).show()
                        // Still FOLLOW_DEVICE — the auto-heal can later replace the placeholder
                        // with a real fix.
                        saveChosenLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, null, LocationMode.FOLLOW_DEVICE)
                    }
            }
        }
    }

    /** Auto-fill resolved: surface the fix and wait for the user's confirm tap. */
    private fun offerPrefetchedFix(fix: LocationFixFlow.Coordinates) {
        prefetchedFix = fix
        findViewById<TextView>(R.id.current_location_label).text = getString(
            R.string.location_found_label,
            String.format(Locale.US, "%.4f, %.4f", fix.lat, fix.lon),
        )
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
     * Single save sink for all four options. Records the location mode, then routes to the
     * per-widget prefs (widget config) or to every widget (Settings entry, [isGlobalMode]).
     */
    private fun saveChosenLocation(lat: Double, lon: Double, label: String?, mode: String) {
        LocationMode.set(this, mode)

        if (isGlobalMode) {
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

        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, PREFS_NAME)
        prefs.edit()
            .putFloat("${KEY_LAT_PREFIX}$appWidgetId", lat.toFloat())
            .putFloat("${KEY_LON_PREFIX}$appWidgetId", lon.toFloat())
            .apply()

        lifecycleScope.launch {
            appLogDao.log("CONFIG", "Widget $appWidgetId configured with lat=$lat, lon=$lon mode=$mode")
        }

        triggerWidgetUpdate()
        finishWithSuccess()
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

    private fun finishWithSuccess() {
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val BACKGROUND_LOCATION_PERMISSION_REQUEST = 1002
        const val PREFS_NAME = "weather_widget_prefs"
        const val KEY_LAT_PREFIX = "widget_lat_"
        const val KEY_LON_PREFIX = "widget_lon_"

        /** Launch extra: no widget id; saves apply to all widgets and no RESULT_OK is set. */
        const val EXTRA_GLOBAL_CONFIG = "extra_global_config"
    }
}
