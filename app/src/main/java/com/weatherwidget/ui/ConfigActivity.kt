package com.weatherwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.weatherwidget.R
import com.weatherwidget.widget.WeatherWidgetWorker
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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

        useGpsButton.setOnClickListener {
            requestLocationPermission()
        }

        useZipButton.setOnClickListener {
            val zipCode = zipCodeInput.text.toString()
            if (zipCode.length == 5) {
                saveZipCodeLocation(zipCode)
            } else {
                Toast.makeText(this, "Please enter a valid 5-digit ZIP code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
        } else {
            getCurrentLocation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
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

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                saveLocation(location.latitude, location.longitude)
            } else {
                Toast.makeText(this, "Could not get location. Using default.", Toast.LENGTH_SHORT).show()
                saveLocation(WeatherWidgetWorker.DEFAULT_LAT, WeatherWidgetWorker.DEFAULT_LON)
            }
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putFloat("${KEY_LAT_PREFIX}$appWidgetId", lat.toFloat())
            .putFloat("${KEY_LON_PREFIX}$appWidgetId", lon.toFloat())
            .apply()

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
        const val PREFS_NAME = "weather_widget_prefs"
        const val KEY_LAT_PREFIX = "widget_lat_"
        const val KEY_LON_PREFIX = "widget_lon_"
    }
}
