package com.weatherwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
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
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.DeviceUtils
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setupViews()
    }

    private fun setupViews() {
        val zipCodeInput = findViewById<EditText>(R.id.zip_code_input)
        val useGpsButton = findViewById<Button>(R.id.use_gps_button)
        val useZipButton = findViewById<Button>(R.id.use_zip_button)
        val sourceSpinner = findViewById<Spinner>(R.id.source_spinner)
        val latInput = findViewById<EditText>(R.id.lat_input)
        val lonInput = findViewById<EditText>(R.id.lon_input)
        val useCoordinatesButton = findViewById<Button>(R.id.use_coordinates_button)
        val coordinatesSection = findViewById<View>(R.id.coordinates_section)

        // Hide coordinates section if it's a device that reports standard GPS
        if (DeviceUtils.reportsStandardGps(this)) {
            coordinatesSection.visibility = View.GONE
        }

        // Setup Source Spinner
        val sources = WeatherSource.entries.filter { it != WeatherSource.GENERIC_GAP && it != WeatherSource.OPEN_WEATHER_MAP }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sources.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceSpinner.adapter = adapter

        useGpsButton.setOnClickListener {
            checkAndRequestLocationPermissions()
        }

        useZipButton.setOnClickListener {
            val zipCode = zipCodeInput.text.toString()
            if (zipCode.length == 5) {
                saveSelectedSource()
                saveZipCodeLocation(zipCode)
            } else {
                Toast.makeText(this, "Please enter a valid 5-digit ZIP code", Toast.LENGTH_SHORT).show()
            }
        }

        useCoordinatesButton.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull()
            val lon = lonInput.text.toString().toDoubleOrNull()
            if (lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0) {
                saveSelectedSource()
                lifecycleScope.launch {
                    try {
                        val resolved = sharedLocationResolver.fromCoordinates(lat, lon)
                        Toast.makeText(this@ConfigActivity, "Location: ${resolved.label}", Toast.LENGTH_LONG).show()
                        saveLocation(lat, lon)
                    } catch (e: Exception) {
                        Toast.makeText(this@ConfigActivity, "Saving coordinates (Label lookup offline)", Toast.LENGTH_SHORT).show()
                        saveLocation(lat, lon)
                    }
                }
            } else {
                Toast.makeText(this, "Please enter valid coordinates (-90 to 90 lat, -180 to 180 lon)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSelectedSource() {
        val sourceSpinner = findViewById<Spinner>(R.id.source_spinner)
        val sources = WeatherSource.entries.filter { it != WeatherSource.GENERIC_GAP && it != WeatherSource.OPEN_WEATHER_MAP }
        val selectedSource = sources[sourceSpinner.selectedItemPosition]
        widgetStateManager.setCurrentDisplaySource(appWidgetId, selectedSource)
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

        saveSelectedSource()
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Actively request a fresh fix. `lastLocation` returns only a cached value that is
        // frequently null (after reboot, or when no app has requested location recently), which
        // previously caused a silent fallback to the default coordinates (Google HQ). That is why
        // widgets ended up "stuck" at the default even with location permission granted.
        // `getCurrentLocation` computes a new fix from GPS/network, falling back to the cached
        // `lastLocation` and only then to the hard default.
        val cancellationToken = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    saveLocation(location.latitude, location.longitude)
                } else {
                    fallBackToLastLocation(fusedLocationClient)
                }
            }
            .addOnFailureListener {
                fallBackToLastLocation(fusedLocationClient)
            }
    }

    /**
     * Last-resort location resolution: try the cached fix, then the hard default. Only reached when
     * an active [FusedLocationProviderClient.getCurrentLocation] request yields nothing.
     */
    private fun fallBackToLastLocation(
        fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient,
    ) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            saveLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON)
            return
        }
        fusedLocationClient.lastLocation
            .addOnSuccessListener { cached ->
                if (cached != null) {
                    saveLocation(cached.latitude, cached.longitude)
                } else {
                    Toast.makeText(this, "Could not get current location. Using default.", Toast.LENGTH_SHORT).show()
                    saveLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not get current location. Using default.", Toast.LENGTH_SHORT).show()
                saveLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON)
            }
    }

    private fun saveZipCodeLocation(zipCode: String) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())

            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName(zipCode, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                saveLocation(address.latitude, address.longitude)
            } else {
                Toast.makeText(this, "Could not find location for ZIP code", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error looking up ZIP code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLocation(
        lat: Double,
        lon: Double,
    ) {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, PREFS_NAME)
        prefs.edit()
            .putFloat("${KEY_LAT_PREFIX}$appWidgetId", lat.toFloat())
            .putFloat("${KEY_LON_PREFIX}$appWidgetId", lon.toFloat())
            .apply()

        lifecycleScope.launch {
            appLogDao.log("CONFIG", "Widget $appWidgetId configured with lat=$lat, lon=$lon")
        }

        triggerWidgetUpdate()
        finishWithSuccess()
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
    }
}
