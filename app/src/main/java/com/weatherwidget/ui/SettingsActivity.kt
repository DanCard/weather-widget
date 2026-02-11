package com.weatherwidget.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weatherwidget.R
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var apiLogger: ApiLogger

    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    @Inject
    lateinit var weatherRepository: WeatherRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        // Trigger background refresh so data is fresh when user returns to widget
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val database = WeatherDatabase.getDatabase(this@SettingsActivity)
                val latestWeather = database.weatherDao().getLatestWeather()
                if (latestWeather != null) {
                    android.util.Log.d("SettingsActivity", "Triggering background weather refresh")
                    weatherRepository.getWeatherData(
                        latestWeather.locationLat,
                        latestWeather.locationLon,
                        latestWeather.locationName,
                        forceRefresh = true,
                        networkAllowed = true,
                    )
                    android.util.Log.d("SettingsActivity", "Background weather refresh complete")
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Background refresh failed: ${e.message}")
            }
        }
    }

    private fun setupViews() {
        // Get the main ScrollView
        val settingsScrollView = findViewById<ScrollView>(R.id.settings_scroll_view)

        // Accuracy Display RadioGroup
        val accuracyGroup = findViewById<RadioGroup>(R.id.accuracy_display_group)

        // Set current selection
        val currentMode = widgetStateManager.getAccuracyDisplayMode()
        val selectedId =
            when (currentMode) {
                AccuracyDisplayMode.NONE -> R.id.radio_none
                AccuracyDisplayMode.ACCURACY_DOT -> R.id.radio_accuracy_dot
                AccuracyDisplayMode.FORECAST_BAR -> R.id.radio_forecast_bar
                AccuracyDisplayMode.SIDE_BY_SIDE -> R.id.radio_side_by_side
                AccuracyDisplayMode.DIFFERENCE -> R.id.radio_difference
            }
        accuracyGroup.check(selectedId)

        // Listen for changes
        accuracyGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode =
                when (checkedId) {
                    R.id.radio_none -> AccuracyDisplayMode.NONE
                    R.id.radio_accuracy_dot -> AccuracyDisplayMode.ACCURACY_DOT
                    R.id.radio_forecast_bar -> AccuracyDisplayMode.FORECAST_BAR
                    R.id.radio_side_by_side -> AccuracyDisplayMode.SIDE_BY_SIDE
                    R.id.radio_difference -> AccuracyDisplayMode.DIFFERENCE
                    else -> AccuracyDisplayMode.FORECAST_BAR
                }
            widgetStateManager.setAccuracyDisplayMode(mode)
        }

        // API Preference RadioGroup
        val apiGroup = findViewById<RadioGroup>(R.id.api_preference_group)

        // Set current selection
        val currentApiPref = widgetStateManager.getApiPreference()
        val selectedApiId =
            when (currentApiPref) {
                ApiPreference.ALTERNATE -> R.id.radio_api_alternate
                ApiPreference.PREFER_NWS -> R.id.radio_api_nws
                ApiPreference.PREFER_OPENMETEO -> R.id.radio_api_openmeteo
            }
        apiGroup.check(selectedApiId)

        // Listen for changes
        apiGroup.setOnCheckedChangeListener { _, checkedId ->
            val preference =
                when (checkedId) {
                    R.id.radio_api_alternate -> ApiPreference.ALTERNATE
                    R.id.radio_api_nws -> ApiPreference.PREFER_NWS
                    R.id.radio_api_openmeteo -> ApiPreference.PREFER_OPENMETEO
                    else -> ApiPreference.ALTERNATE
                }
            widgetStateManager.setApiPreference(preference)
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
                        (comparison.meteoStats?.totalForecasts ?: 0) > 0

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
                                append("  • Forecasts: %d\n".format(stats.totalForecasts))
                            } else {
                                append("Open-Meteo: No data yet")
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
