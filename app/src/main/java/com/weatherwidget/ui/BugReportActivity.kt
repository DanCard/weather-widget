package com.weatherwidget.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.logException
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.HeaderFitPersistLog
import com.weatherwidget.shared.util.BugReportFormat
import com.weatherwidget.shared.util.BugReportLogs
import com.weatherwidget.shared.util.BugReportZip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class BugReportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BugReportActivity"
        internal const val BUG_REPORT_RECIPIENT = "daniecarde55@gmail.com"
        internal const val BUG_REPORT_DIR = "bug_reports"
    }

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
            append("${BugReportFormat.timezoneLine()}\n")
            append("${BugReportDeviceDiagnostics.launcherLine(this@BugReportActivity)}\n")
            append("GPS Mode: $locationMode\n\n")

            append("--- DISPLAY ---\n")
            BugReportDeviceDiagnostics.displayLines(this@BugReportActivity).forEach { append("$it\n") }
            append("\n")

            append("--- BATTERY & POWER ---\n")
            append("Battery Level: $batteryLevel%\n")
            append("Charging Status: ${if (isCharging) "Charging" else "On Battery"}\n")
            append("Screen State: ${if (isScreenInteractive) "Interactive" else "Screen Off"}\n")
            BugReportDeviceDiagnostics.powerAndPermissionLines(this@BugReportActivity).forEach { append("$it\n") }
            append("\n")

            append("--- ACTIVE WIDGETS ---\n")
            append("Count: ${widgetIds.size}\n")
            if (includeMetadataCheckbox.isChecked) {
                for (id in widgetIds) {
                    val loc = widgetStateManager.getWidgetLocation(id)
                    val locStr = if (loc != null) "${String.format(Locale.US, "%.4f", loc.first)}, ${String.format(Locale.US, "%.4f", loc.second)}" else "Not Set"
                    val source = widgetStateManager.getCurrentDisplaySource(id).name
                    val view = widgetStateManager.getViewMode(id).name
                    append("- Widget #$id: API=$source | View=$view | Coordinates=$locStr\n")
                    BugReportDeviceDiagnostics.widgetSizeLine(this@BugReportActivity, appWidgetManager, id)
                        .lines().forEach { append("  $it\n") }
                    val headerFits = HeaderFitPersistLog.latestShortLines(id)
                    if (headerFits.isEmpty()) {
                        append("  header: not rendered since app process start\n")
                    } else {
                        headerFits.values.forEach { append("  header: $it\n") }
                    }
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
                val keySources = WeatherSource.entries.filter { it.requiresApiKey }
                for (src in keySources) {
                    val key = widgetStateManager.getApiKey(src)
                    val status = if (!key.isNullOrBlank()) "Configured" else "Missing"
                    append("- ${src.displayName}: $status\n")
                }
            } else {
                append("[DB/Config metadata excluded]\n")
            }
            append("\n")

            append("--- ATTACHED ZIP ---\n")
            append("${BugReportZip.SUMMARY}: this summary + full header lines\n")
            if (includeLogsCheckbox.isChecked) {
                append("${BugReportZip.LOGS}: all logs 2 h + key events 72 h\n")
                append("widget-<id>.png: each widget as last sent\n")
            } else {
                append("Logs and widget images: excluded\n")
            }
            if (includeMetadataCheckbox.isChecked) {
                append("${BugReportZip.DATABASE}: database copy (locations, history)\n")
            } else {
                append("Database copy: excluded\n")
            }
        }
    }

    /** Rows for `logs.tsv` and the "Recent taps" lines ([BugReportLogs.select]). */
    private suspend fun loadReportLogs(nowMs: Long): List<BugReportLogs.Selected> {
        val rows = appLogDao.getBugReportLogs(
            recentSince = nowMs - BugReportLogs.RECENT_WINDOW_MS,
            keySince = nowMs - BugReportLogs.KEY_EVENT_WINDOW_MS,
            keyLevels = BugReportLogs.KEY_EVENT_LEVELS,
            keyTags = BugReportLogs.KEY_EVENT_TAGS,
        ).map { BugReportLogs.Row(it.timestamp, it.level, it.tag, it.message) }
        return BugReportLogs.select(rows, nowMs)
    }

    /** The email body / `summary.txt` head: description, version, recent taps, then [generatePreviewText]. */
    private fun summaryText(
        description: String,
        version: Pair<String, Long>,
        taps: List<String>,
        metadata: String,
    ): String = buildString {
        append("Weather Widget bug report\n\n")
        append("--- DESCRIPTION ---\n").append(description).append("\n\n")
        append("--- VERSION ---\n")
        append("${version.first} (code ${version.second})\n\n")
        append("--- RECENT TAPS ---\n")
        if (taps.isEmpty()) append("none in the last 2 h\n") else taps.forEach { append("$it\n") }
        append("\n")
        append(metadata)
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
        val includeLogs = includeLogsCheckbox.isChecked
        val includeMetadata = includeMetadataCheckbox.isChecked

        lifecycleScope.launch(ioDispatcher) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val nowMs = now.toInstant().toEpochMilli()
            val appVersion = getAppVersionDetails()
            val metadataText = generatePreviewText()

            val logs = if (includeLogs) loadReportLogs(nowMs) else emptyList()
            val taps = BugReportLogs.tapLines(logs, zone)
            val summary = summaryText(description, appVersion, taps, metadataText)

            val appWidgetManager = AppWidgetManager.getInstance(this@BugReportActivity)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(this@BugReportActivity, WeatherWidgetProvider::class.java),
            )
            // View inflation for the images must happen on the main thread.
            val images = if (includeLogs) {
                withContext(Dispatchers.Main) {
                    widgetIds.associateWith { WidgetImageCapture.capture(this@BugReportActivity, appWidgetManager, it) }
                }
            } else {
                emptyMap()
            }
            val headerDetail = buildString {
                widgetIds.forEach { id ->
                    HeaderFitPersistLog.latestLines(id).values.forEach { append("widget=$id $it\n") }
                    images[id]?.let { append("widget=$id ${it.note}\n") }
                }
            }

            val reportDir = File(cacheDir, BUG_REPORT_DIR).apply { mkdirs() }
            reportDir.listFiles()?.forEach { it.delete() }
            val dbCopy = if (includeMetadata) {
                BugReportDatabaseCopy.write(WeatherDatabase.getDatabase(this@BugReportActivity), File(reportDir, "db-copy.tmp"))
            } else {
                null
            }
            val entries = buildList {
                add(BugReportZip.Entry.Bytes(BugReportZip.SUMMARY, (summary + "\n--- HEADER DETAIL ---\n" + headerDetail).toByteArray()))
                if (includeLogs) {
                    add(BugReportZip.Entry.Bytes(BugReportZip.LOGS, BugReportLogs.tsv(logs, zone).toByteArray()))
                    images.forEach { (id, result) ->
                        result.png?.let { add(BugReportZip.Entry.Bytes(BugReportZip.widgetImageName(id), it)) }
                    }
                }
                dbCopy?.let { add(BugReportZip.Entry.FromFile(BugReportZip.DATABASE, it)) }
            }
            val zipFile = File(reportDir, BugReportZip.fileName(now))
            val written = try {
                zipFile.outputStream().use { BugReportZip.write(it, entries) }
            } catch (e: Exception) {
                appLogDao.logException(TAG, "Could not write bug report zip", e)
                null
            } finally {
                dbCopy?.delete()
            }
            appLogDao.log(
                BugReportLogs.BUILT_TAG,
                BugReportLogs.builtLine(
                    zipBytes = if (written != null) zipFile.length() else 0,
                    logs = logs,
                    images = images.values.count { it.png != null },
                    db = dbCopy != null,
                    entries = written,
                ) + " widgets=${widgetIds.size}",
                "INFO",
            )

            // A zip that can't be shared still leaves a text-only report, never a crash.
            val zipUri = written?.let {
                try {
                    FileProvider.getUriForFile(this@BugReportActivity, "$packageName.bugreports", zipFile)
                } catch (e: IllegalArgumentException) {
                    appLogDao.logException(TAG, "Could not share bug report zip ${zipFile.path}", e)
                    null
                }
            }

            withContext(Dispatchers.Main) {
                sendButton.isEnabled = true
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(BUG_REPORT_RECIPIENT))
                    putExtra(Intent.EXTRA_SUBJECT, BugReportFormat.subject(description, appVersion.second.toString()))
                    putExtra(Intent.EXTRA_TEXT, summary)
                    if (zipUri != null) {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, zipUri)
                        clipData = ClipData.newRawUri(zipFile.name, zipUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        type = "text/plain"
                    }
                }
                try {
                    startActivity(
                        Intent.createChooser(sendIntent, getString(R.string.bug_report_send))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start bug report share", e)
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
