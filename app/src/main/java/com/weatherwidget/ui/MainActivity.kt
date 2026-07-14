package com.weatherwidget.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.GpsResampler
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

import androidx.appcompat.app.AlertDialog

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var gpsResampler: GpsResampler

    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostic: MainActivity should normally only open from the launcher icon. On Samsung,
        // tapping a widget "dead zone" (no PendingIntent) sometimes makes One UI Home fall back to
        // launching this LAUNCHER activity. The tap never reaches our widget code, so the only place
        // to observe it is here. Log launch provenance so the next stray launch can be confirmed by
        // correlating MAIN_LAUNCH against the widget CLICK_* rows in app_logs.
        logLaunchProvenance("onCreate", savedInstanceState == null)
        setContentView(R.layout.activity_main)

        setupViews()
        updatePermissionVisibility()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logLaunchProvenance("onNewIntent", freshCreate = false)
    }

    private fun logLaunchProvenance(reason: String, freshCreate: Boolean) {
        val launchIntent = intent
        val message = buildString {
            append("reason=$reason")
            append(" referrer=${referrer?.toString()}")
            append(" action=${launchIntent?.action}")
            append(" categories=${launchIntent?.categories}")
            append(" flags=0x${Integer.toHexString(launchIntent?.flags ?: 0)}")
            append(" component=${launchIntent?.component?.flattenToShortString()}")
            append(" extras=${launchIntent?.extras?.keySet()}")
            append(" freshCreate=$freshCreate taskId=$taskId")
            append(" views=${widgetViewModeSnapshot()}")
        }
        lifecycleScope.launch {
            appLogDao.log("MAIN_LAUNCH", message, "INFO")
        }
    }

    /**
     * Each widget's stored [com.weatherwidget.widget.ViewMode] at launch time, as "id:MODE" pairs.
     * A launcher-fallback launch means the tap never reached our code, so this is the only record of
     * what the widget was showing when the touch was hijacked — the mode determines which zones were
     * VISIBLE (graph modes bind the home icon; DAILY hides it via DailyVisibilityManager).
     * Only runs on MainActivity launches, which are rare, so it cannot swamp app_logs.
     */
    private fun widgetViewModeSnapshot(): String =
        try {
            AppWidgetManager.getInstance(this)
                .getAppWidgetIds(ComponentName(this, WeatherWidgetProvider::class.java))
                .joinToString(",") { "$it:${widgetStateManager.getViewMode(it)}" }
                .ifEmpty { "none" }
        } catch (e: Exception) {
            "error=${e.javaClass.simpleName}"
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
                    maybeAutoHealLocationFromGps()
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
        maybeAutoHealLocationFromGps()
    }

    /**
     * Self-heals widgets whose stored location has drifted from where the device actually is.
     * Reads only the cached Fused last-known location — never an active GPS fix, which would
     * trigger Samsung's "app got your precise location" notice. If the cache is empty this
     * no-ops, and [GpsResampler.healIfNeeded] skips when the user pinned a location
     * ([LocationMode.FIXED][com.weatherwidget.util.LocationMode]) so deliberate choices are
     * never overwritten.
     */
    private fun maybeAutoHealLocationFromGps() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) return

        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { location ->
            if (location == null) return@addOnSuccessListener
            val lat = location.latitude
            val lon = location.longitude
            lifecycleScope.launch {
                if (gpsResampler.healIfNeeded(this@MainActivity, lat, lon, trigger = "foreground")) {
                    Toast.makeText(this@MainActivity, getString(R.string.location_updated_from_gps), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
