package com.weatherwidget.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.weatherwidget.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupViews()
        updatePermissionVisibility()
    }

    private fun setupViews() {
        findViewById<Button>(R.id.grant_permission_button).setOnClickListener {
            requestLocationPermissions()
        }

        findViewById<Button>(R.id.open_settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updatePermissionVisibility() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        findViewById<View>(R.id.location_disclosure_card).visibility =
            if (fineLocationGranted && backgroundLocationGranted) View.GONE else View.VISIBLE
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Note: For Android 11+ (API 30+), background location must be requested separately 
        // after foreground location is granted. For simplicity in this onboarding, 
        // we'll request foreground first.
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        getResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, getResults)
        if (requestCode == 1001) {
            val fineLocationGranted = getResults.getOrNull(
                permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
            ) == PackageManager.PERMISSION_GRANTED

            if (fineLocationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Request background location after foreground is granted
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    1002
                )
            }
            updatePermissionVisibility()
        } else if (requestCode == 1002) {
            updatePermissionVisibility()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionVisibility()
    }
}
