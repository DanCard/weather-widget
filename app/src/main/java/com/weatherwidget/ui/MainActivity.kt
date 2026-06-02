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

import androidx.appcompat.app.AlertDialog

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
            startPermissionFlow()
        }

        findViewById<Button>(R.id.view_privacy_policy_button).setOnClickListener {
            showPrivacyPolicyDialog()
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

    private fun startPermissionFlow() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) {
            requestForegroundLocation()
        } else {
            checkAndRequestBackgroundLocation()
        }
    }

    private fun requestForegroundLocation() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(this, permissions, 1001)
    }

    private fun checkAndRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocationGranted) {
                showBackgroundLocationDisclosureDialog()
            }
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
                    1002
                )
            }
            .setNegativeButton(R.string.no_thanks, null)
            .show()
    }

    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_policy_title)
            .setMessage(R.string.privacy_policy_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        getResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, getResults)
        when (requestCode) {
            1001 -> {
                val fineLocationGranted = getResults.getOrNull(
                    permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
                ) == PackageManager.PERMISSION_GRANTED

                if (fineLocationGranted) {
                    checkAndRequestBackgroundLocation()
                }
                updatePermissionVisibility()
            }
            1002 -> {
                updatePermissionVisibility()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionVisibility()
    }
}
