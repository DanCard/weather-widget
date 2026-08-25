package com.weatherwidget.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.appwidget.AppWidgetManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class BugReportActivity : AppCompatActivity() {

    @Inject
    lateinit var appLogDao: AppLogDao

    @Inject
    lateinit var forecastDao: ForecastDao

    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    private lateinit var descriptionInput: EditText
    private lateinit var includeLogsCheckbox: CheckBox
    private lateinit var includeMetadataCheckbox: CheckBox
    private lateinit var diagnosticsPreviewText: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bug_report)

        setupViews()
        loadPreview()
    }

    private fun setupViews() {
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        descriptionInput = findViewById(R.id.bug_report_description)
        includeLogsCheckbox = findViewById(R.id.bug_report_include_logs_checkbox)
        includeMetadataCheckbox = findViewById(R.id.bug_report_include_metadata_checkbox)
        diagnosticsPreviewText = findViewById(R.id.bug_report_diagnostics_preview_text)
        sendButton = findViewById(R.id.bug_report_send_button)

        includeLogsCheckbox.setOnCheckedChangeListener { _, _ -> loadPreview() }
        includeMetadataCheckbox.setOnCheckedChangeListener { _, _ -> loadPreview() }

        sendButton.setOnClickListener {
            sendBugReport()
        }
    }

    internal fun loadPreview() {
        lifecycleScope.launch(ioDispatcher) {
            val preview = generatePreviewText()
            withContext(Dispatchers.Main) {
                diagnosticsPreviewText.text = preview
            }
        }
    }

    private suspend fun generatePreviewText(): String = withContext(ioDispatcher) {
        val appWidgetManager = AppWidgetManager.getInstance(this@BugReportActivity)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(this@BugReportActivity, WeatherWidgetProvider::class.java)
        )

        val totalLogCount = appLogDao.getCount()
        val snapshotCount = forecastDao.getCount()
        val dbSizeMb = getDatabaseSizeMb()

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenInteractive = powerManager.isInteractive

        val hasGps = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        val locationMode = if (com.weatherwidget.util.DeviceUtils.isEmulator()) {
            "Simulated (Emulator)"
        } else if (hasGps) {
            "GPS Supported"
        } else {
            "No GPS Hardware"
        }

        buildString {
            append("--- DEVICE INFO ---\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND})\n")
            append("OS version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Locale: ${Locale.getDefault()}\n")
            append("GPS Mode: $locationMode\n\n")

            append("--- BATTERY & POWER ---\n")
            append("Battery Level: $batteryLevel%\n")
            append("Charging Status: ${if (isCharging) "Charging" else "On Battery"}\n")
            append("Screen State: ${if (isScreenInteractive) "Interactive" else "Screen Off"}\n\n")

            append("--- ACTIVE WIDGETS ---\n")
            append("Count: ${widgetIds.size}\n")
            if (includeMetadataCheckbox.isChecked) {
                for (id in widgetIds) {
                    val loc = widgetStateManager.getWidgetLocation(id)
                    val locStr = if (loc != null) "${String.format(Locale.US, "%.4f", loc.first)}, ${String.format(Locale.US, "%.4f", loc.second)}" else "Not Set"
                    val source = widgetStateManager.getCurrentDisplaySource(id).name
                    append("- Widget #$id: API=$source | Coordinates=$locStr\n")
                }
            } else {
                append("[Widget metadata excluded]\n")
            }
            append("\n")

            append("--- DATABASE STATS ---\n")
            append("DB size: ${String.format(Locale.US, "%.2f", dbSizeMb)} MB\n")
            append("Logs count: $totalLogCount\n")
            append("Forecast snapshots: $snapshotCount\n\n")

            append("--- COMPONENT DETAILS ---\n")
            if (includeMetadataCheckbox.isChecked) {
                val sourcesOrder = widgetStateManager.getVisibleSourcesOrder().joinToString(", ") { it.name }
                append("Sources order: $sourcesOrder\n")
                append("API Keys Configured:\n")
                val keySources = listOf(
                    WeatherSource.TOMORROW_IO,
                    WeatherSource.SILURIAN,
                    WeatherSource.WEATHER_API,
                    WeatherSource.VISUAL_CROSSING,
                    WeatherSource.OPEN_WEATHER_MAP
                )
                for (src in keySources) {
                    val key = widgetStateManager.getApiKey(src)
                    val status = if (!key.isNullOrBlank()) "Configured" else "Missing"
                    append("- ${src.displayName}: $status\n")
                }
            } else {
                append("[DB/Config metadata excluded]\n")
            }
            append("\n")

            append("--- INCLUDED DATA ---\n")
            append("System Logs (300 lines): ${if (includeLogsCheckbox.isChecked) "Included" else "Excluded"}\n")
            append("Widget Config & Metadata: ${if (includeMetadataCheckbox.isChecked) "Included" else "Excluded"}\n")
        }
    }

    private fun getDatabaseSizeMb(): Double {
        return try {
            val dbFile = getDatabasePath("weather_database")
            if (dbFile.exists()) {
                dbFile.length() / (1024.0 * 1024.0)
            } else {
                0.0
            }
        } catch (e: Exception) {
            Log.w("BugReportActivity", "Could not read database size", e)
            0.0
        }
    }

    private fun sendBugReport() {
        val description = descriptionInput.text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(this, R.string.bug_report_error_empty_description, Toast.LENGTH_SHORT).show()
            return
        }

        sendButton.isEnabled = false

        lifecycleScope.launch(ioDispatcher) {
            // Get App Version Details
            val appVersion = getAppVersionDetails()

            // Gather metadata preview
            val metadataText = generatePreviewText()

            // Gather recent app logs if checked
            val logsText = if (includeLogsCheckbox.isChecked) {
                val recentLogs = appLogDao.getRecentLogs(300)
                if (recentLogs.isEmpty()) {
                    getString(R.string.bug_report_no_logs)
                } else {
                    val full = recentLogs.joinToString("\n") { log ->
                        "${log.getFormattedTime()} ${log.level}/${log.tag}: ${log.message}"
                    }
                    if (full.length > 100_000) {
                        full.substring(0, 100_000) + "\n...(truncated for size)"
                    } else {
                        full
                    }
                }
            } else {
                getString(R.string.bug_report_logs_excluded)
            }

            // Build full Markdown report
            val fullReport = buildString {
                append("> **[TIP]** Please attach a screenshot of the widget if you are reporting a visual issue!\n\n")
                append("# Weather Widget Bug Report\n\n")
                append("## Description of the Bug\n")
                append(description)
                append("\n\n")

                append("## Application Version\n")
                append("- Version Name: ${appVersion.first}\n")
                append("- Version Code: ${appVersion.second}\n\n")

                append("## Diagnostics Metadata\n")
                append("```\n")
                append(metadataText)
                append("```\n\n")

                if (includeLogsCheckbox.isChecked) {
                    append("## Application Logs (Recent 300 Lines)\n")
                    append("```\n")
                    append(logsText)
                    append("\n```\n")
                }
            }

            withContext(Dispatchers.Main) {
                sendButton.isEnabled = true

                val sendIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("daniecarde55@gmail.com"))
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.bug_report_email_subject))
                    putExtra(Intent.EXTRA_TEXT, fullReport)
                }

                try {
                    startActivity(Intent.createChooser(sendIntent, getString(R.string.bug_report_send)))
                } catch (e: Exception) {
                    Toast.makeText(this@BugReportActivity, getString(R.string.bug_report_share_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getAppVersionDetails(): Pair<String, Long> {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            versionName to versionCode
        } catch (e: Exception) {
            Log.w("BugReportActivity", "Could not read app version info", e)
            "Unknown" to -1L
        }
    }
}
