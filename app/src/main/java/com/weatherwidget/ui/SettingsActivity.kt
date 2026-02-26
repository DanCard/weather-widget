package com.weatherwidget.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.weatherwidget.R
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager

import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var apiLogger: ApiLogger

    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    private fun setupViews() {
        // Get the main ScrollView
        val settingsScrollView = findViewById<ScrollView>(R.id.settings_scroll_view)

        // API Sources ordered checkable list
        setupApiSourcesList()

        // Manual refresh
        val refreshNowButton = findViewById<Button>(R.id.refresh_now_button)
        val refreshStatusText = findViewById<TextView>(R.id.refresh_status_text)
        refreshStatusText.text = getString(R.string.refresh_status_ready)
        refreshNowButton.setOnClickListener {
            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .build(),
                    )
                    .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
            refreshStatusText.text = getString(R.string.refresh_status_enqueued)
            Toast.makeText(this, getString(R.string.refresh_now_enqueued_toast), Toast.LENGTH_SHORT).show()
        }

        // API Diagnostics
        val viewLogButton = findViewById<Button>(R.id.view_api_log_button)
        val clearLogButton = findViewById<Button>(R.id.clear_api_log_button)
        val logContainer = findViewById<ScrollView>(R.id.api_log_container)
        val logText = findViewById<TextView>(R.id.api_log_text)
        val logStatus = findViewById<TextView>(R.id.api_log_status)

        // Update status to show number of entries
        val entryCount = apiLogger.getLogEntries().size
        logStatus.text = "Log: $entryCount API calls recorded"

        viewLogButton.setOnClickListener {
            android.util.Log.d("SettingsActivity", "View API Log button clicked")

            if (logContainer.visibility == View.VISIBLE) {
                logContainer.visibility = View.GONE
                viewLogButton.text = getString(R.string.view_api_log)
            } else {
                android.util.Log.d("SettingsActivity", "Displaying API log...")
                displayApiLog(logText)
                logContainer.visibility = View.VISIBLE
                viewLogButton.text = "Hide API Log"

                // Auto-scroll to show the log after it's laid out
                logContainer.post {
                    settingsScrollView.smoothScrollTo(0, logContainer.top - 50)
                }

                val entries = apiLogger.getLogEntries()
                Toast.makeText(this, "Showing ${entries.size} log entries", Toast.LENGTH_SHORT).show()
            }
        }

        clearLogButton.setOnClickListener {
            apiLogger.clearLog()
            displayApiLog(logText)
            logStatus.text = "Log: 0 API calls recorded"
        }

        // Accuracy Statistics
        val statsText = findViewById<TextView>(R.id.accuracy_stats_text)
        val viewStatsButton = findViewById<Button>(R.id.view_detailed_stats_button)

        // Load and display accuracy statistics
        loadAccuracyStatistics(statsText)

        viewStatsButton.setOnClickListener {
            val intent = Intent(this, StatisticsActivity::class.java)
            startActivity(intent)
        }

        // Feature Tour button
        val featureTourButton = findViewById<Button>(R.id.view_feature_tour_button)
        featureTourButton.setOnClickListener {
            val intent = Intent(this, FeatureTourActivity::class.java)
            startActivity(intent)
        }

        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
    }

    /** All configurable weather sources (excludes GENERIC_GAP). */
    private val allSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)

    private fun sourceDescription(source: WeatherSource): String = when (source) {
        WeatherSource.NWS -> getString(R.string.api_source_nws_desc)
        WeatherSource.OPEN_METEO -> getString(R.string.api_source_openmeteo_desc)
        WeatherSource.WEATHER_API -> getString(R.string.api_source_weatherapi_desc)
        else -> ""
    }

    /**
     * Builds the ordered, checkable API source list in the container.
     * Each row has a checkbox (enable/disable), source name + description, and up/down arrows.
     */
    private fun setupApiSourcesList() {
        val container = findViewById<LinearLayout>(R.id.api_sources_container)
        rebuildSourceRows(container)
    }

    private fun rebuildSourceRows(container: LinearLayout) {
        container.removeAllViews()
        val visibleSources = widgetStateManager.getVisibleSourcesOrder()

        // Build full ordered list: visible sources first (in order), then hidden sources
        val hiddenSources = allSources.filter { it !in visibleSources }
        val orderedSources = visibleSources + hiddenSources

        for ((index, source) in orderedSources.withIndex()) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_api_source, container, false)

            val checkbox = row.findViewById<CheckBox>(R.id.source_checkbox)
            val nameView = row.findViewById<TextView>(R.id.source_name)
            val descView = row.findViewById<TextView>(R.id.source_description)
            val upButton = row.findViewById<ImageButton>(R.id.move_up_button)
            val downButton = row.findViewById<ImageButton>(R.id.move_down_button)

            val isVisible = source in visibleSources
            checkbox.isChecked = isVisible
            nameView.text = source.displayName
            descView.text = sourceDescription(source)

            // Dim hidden sources
            row.alpha = if (isVisible) 1.0f else 0.5f

            // Up/down only meaningful for visible sources
            upButton.visibility = if (isVisible && visibleSources.indexOf(source) > 0) View.VISIBLE else View.INVISIBLE
            downButton.visibility = if (isVisible && visibleSources.indexOf(source) < visibleSources.size - 1) View.VISIBLE else View.INVISIBLE

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                val current = widgetStateManager.getVisibleSourcesOrder().toMutableList()
                if (isChecked) {
                    if (source !in current) current.add(source)
                } else {
                    if (current.size <= 1) {
                        // Prevent unchecking the last source
                        checkbox.isChecked = true
                        Toast.makeText(this, getString(R.string.must_keep_one_source), Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                    current.remove(source)
                }
                widgetStateManager.setVisibleSourcesOrder(current)
                rebuildSourceRows(container)
            }

            upButton.setOnClickListener {
                val current = widgetStateManager.getVisibleSourcesOrder().toMutableList()
                val pos = current.indexOf(source)
                if (pos > 0) {
                    current[pos] = current[pos - 1]
                    current[pos - 1] = source
                    widgetStateManager.setVisibleSourcesOrder(current)
                    rebuildSourceRows(container)
                }
            }

            downButton.setOnClickListener {
                val current = widgetStateManager.getVisibleSourcesOrder().toMutableList()
                val pos = current.indexOf(source)
                if (pos < current.size - 1) {
                    current[pos] = current[pos + 1]
                    current[pos + 1] = source
                    widgetStateManager.setVisibleSourcesOrder(current)
                    rebuildSourceRows(container)
                }
            }

            container.addView(row)
        }
    }

    private fun displayApiLog(textView: TextView) {
        val entries = apiLogger.getLogEntries()
        android.util.Log.d("SettingsActivity", "displayApiLog: Found ${entries.size} log entries")
        if (entries.isEmpty()) {
            textView.text = "No API calls logged yet."
            return
        }

        val logText =
            buildString {
                append("API LOG (${entries.size} entries) - Scroll down/right to see all\n")
                entries.forEach { entry ->
                    val status = if (entry.success) "✓" else "✗"
                    val error = if (!entry.success && entry.errorMessage != null) " ERR:${entry.errorMessage}" else ""
                    val loc = if (entry.location.isNotEmpty()) " ${entry.location}" else ""

                    append("${entry.getFormattedTime()} ${entry.apiName.padEnd(11)} $status ${entry.durationMs}ms$loc$error\n")
                }
            }

        textView.text = logText
    }

    private fun loadAccuracyStatistics(textView: TextView) {
        lifecycleScope.launch {
            try {
                // Get location from latest weather data
                val database = WeatherDatabase.getDatabase(this@SettingsActivity)
                val latestWeather = database.weatherDao().getLatestWeather()

                if (latestWeather == null) {
                    textView.text = "No weather data available yet.\nFetch weather data to see statistics."
                    return@launch
                }

                val lat = latestWeather.locationLat
                val lon = latestWeather.locationLon

                // Calculate comparison statistics
                val comparison = accuracyCalculator.calculateComparison(lat, lon, 30)

                // Check if we have any data at all
                val hasAnyData =
                    (comparison.nwsStats?.totalForecasts ?: 0) > 0 ||
                        (comparison.meteoStats?.totalForecasts ?: 0) > 0 ||
                        (comparison.weatherApiStats?.totalForecasts ?: 0) > 0

                val statsText =
                    if (!hasAnyData) {
                        buildString {
                            append("No historical forecast data available yet.\n\n")
                            append("Forecast snapshots are being saved daily.\n")
                            append("Check back tomorrow to see your first accuracy data point!\n\n")
                            append("Timeline:\n")
                            append("  • Tomorrow: First accuracy comparison\n")
                            append("  • 7 days: Meaningful statistics\n")
                            append("  • 30 days: Full statistics available")
                        }
                    } else {
                        buildString {
                            append("Last 30 Days Accuracy:\n\n")

                            if (comparison.nwsStats != null && comparison.nwsStats.totalForecasts > 0) {
                                val stats = comparison.nwsStats
                                append("NWS:\n")
                                append("  • High: ±%.1f°%s\n".format(stats.avgHighError, formatBias(stats.highBias)))
                                append("  • Low: ±%.1f°%s\n".format(stats.avgLowError, formatBias(stats.lowBias)))
                                append("  • Within 3°: %.0f%%\n".format(stats.percentWithin3Degrees))
                                append("  • Forecasts: %d\n\n".format(stats.totalForecasts))
                            } else {
                                append("NWS: No data yet\n\n")
                            }

                            if (comparison.meteoStats != null && comparison.meteoStats.totalForecasts > 0) {
                                val stats = comparison.meteoStats
                                append("Open-Meteo:\n")
                                append("  • High: ±%.1f°%s\n".format(stats.avgHighError, formatBias(stats.highBias)))
                                append("  • Low: ±%.1f°%s\n".format(stats.avgLowError, formatBias(stats.lowBias)))
                                append("  • Within 3°: %.0f%%\n".format(stats.percentWithin3Degrees))
                                append("  • Forecasts: %d\n\n".format(stats.totalForecasts))
                            } else {
                                append("Open-Meteo: No data yet\n\n")
                            }

                            if (comparison.weatherApiStats != null && comparison.weatherApiStats.totalForecasts > 0) {
                                val stats = comparison.weatherApiStats
                                append("WeatherAPI:\n")
                                append("  • High: ±%.1f°%s\n".format(stats.avgHighError, formatBias(stats.highBias)))
                                append("  • Low: ±%.1f°%s\n".format(stats.avgLowError, formatBias(stats.lowBias)))
                                append("  • Within 3°: %.0f%%\n".format(stats.percentWithin3Degrees))
                                append("  • Forecasts: %d\n".format(stats.totalForecasts))
                            } else {
                                append("WeatherAPI: No data yet")
                            }
                        }
                    }

                textView.text = statsText
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading accuracy statistics", e)
                textView.text = "Error loading statistics:\n${e.message}"
            }
        }
    }

    private fun formatBias(bias: Double): String {
        val absBias = kotlin.math.abs(bias)
        return when {
            absBias < 0.5 -> "" // Don't show negligible bias
            bias > 0 -> " (forecasts %.1f° low)".format(absBias)
            else -> " (forecasts %.1f° high)".format(absBias)
        }
    }
}
