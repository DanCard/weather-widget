package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.widget.WeatherWidgetWorker

/**
 * Trampoline activity launched when the user taps the failure watermark pill on the widget.
 * Opens the system's background data / unrestricted data usage settings screen for Weather Widget.
 * When the user returns from settings, if background data is now allowed or whitelisted,
 * it immediately triggers a worker refresh so the widget updates and clears the error pill.
 */
class BackgroundDataResolutionActivity : AppCompatActivity() {

    private var launchedSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openBackgroundDataSettings()
    }

    override fun onResume() {
        super.onResume()
        if (launchedSettings) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val status = cm?.restrictBackgroundStatus ?: ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
            if (status != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                Log.i(TAG, "Background data enabled/unrestricted. Triggering force refresh.")
                val workRequest = OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(applicationContext).enqueue(workRequest)
            }
            finish()
        }
    }

    private fun openBackgroundDataSettings() {
        launchedSettings = true
        val settingsIntent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (settingsIntent.resolveActivity(packageManager) != null) {
            startActivity(settingsIntent)
        } else {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open application details settings", e)
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "BgDataResolution"
    }
}
