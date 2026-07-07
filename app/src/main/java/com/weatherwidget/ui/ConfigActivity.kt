package com.weatherwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.util.LocationMode
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

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
 */
@AndroidEntryPoint
class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isGlobalMode = false

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
    }

    private fun setupViews() {
        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        val searchInput = findViewById<EditText>(R.id.location_search_input)
        val searchButton = findViewById<Button>(R.id.search_location_button)
        val latInput = findViewById<EditText>(R.id.lat_input)
        val lonInput = findViewById<EditText>(R.id.lon_input)
        val useCoordinatesButton = findViewById<Button>(R.id.use_coordinates_button)

        useGpsButton.setOnClickListener {
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

        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        useGpsButton.isEnabled = false
        Toast.makeText(this, getString(R.string.getting_location), Toast.LENGTH_SHORT).show()

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Active fix, deliberately: this is the ONE exception to the app's passive-lastLocation
        // rule. Samsung's "app got your precise location" notice targets background access; here
        // the user explicitly tapped "Use precise device location" in a foreground screen, so a
        // fresh fix is expected. Every background path must stay passive (see GpsResampler).
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    saveChosenLocation(location.latitude, location.longitude, null, LocationMode.FOLLOW_DEVICE)
                } else {
                    fallBackToLastLocation(fusedLocationClient)
                }
            }
            .addOnFailureListener {
                fallBackToLastLocation(fusedLocationClient)
            }
    }

    /**
     * Last-resort resolution when the active fix yields nothing: cached fix, then hard default.
     * Still FOLLOW_DEVICE — the auto-heal can later replace the placeholder with a real fix.
     */
    private fun fallBackToLastLocation(
        fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
    ) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            saveChosenLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, null, LocationMode.FOLLOW_DEVICE)
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { cached ->
                if (cached != null) {
                    saveChosenLocation(cached.latitude, cached.longitude, null, LocationMode.FOLLOW_DEVICE)
                } else {
                    Toast.makeText(this, "Could not get current location. Using default.", Toast.LENGTH_SHORT).show()
                    saveChosenLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, null, LocationMode.FOLLOW_DEVICE)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not get current location. Using default.", Toast.LENGTH_SHORT).show()
                saveChosenLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON, null, LocationMode.FOLLOW_DEVICE)
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
