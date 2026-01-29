package com.weatherwidget.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.weatherwidget.R
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.widget.AccuracyDisplayMode
import com.weatherwidget.widget.ApiPreference
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var apiLogger: ApiLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    private fun setupViews() {
        // Get the main ScrollView
        val settingsScrollView = findViewById<ScrollView>(R.id.settings_scroll_view)

        // Accuracy Display RadioGroup
        val accuracyGroup = findViewById<RadioGroup>(R.id.accuracy_display_group)

        // Set current selection
        val currentMode = widgetStateManager.getAccuracyDisplayMode()
        val selectedId = when (currentMode) {
            AccuracyDisplayMode.NONE -> R.id.radio_none
            AccuracyDisplayMode.ACCURACY_DOT -> R.id.radio_accuracy_dot
            AccuracyDisplayMode.SIDE_BY_SIDE -> R.id.radio_side_by_side
            AccuracyDisplayMode.DIFFERENCE -> R.id.radio_difference
        }
        accuracyGroup.check(selectedId)

        // Listen for changes
        accuracyGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radio_none -> AccuracyDisplayMode.NONE
                R.id.radio_accuracy_dot -> AccuracyDisplayMode.ACCURACY_DOT
                R.id.radio_side_by_side -> AccuracyDisplayMode.SIDE_BY_SIDE
                R.id.radio_difference -> AccuracyDisplayMode.DIFFERENCE
                else -> AccuracyDisplayMode.ACCURACY_DOT
            }
            widgetStateManager.setAccuracyDisplayMode(mode)
        }

        // API Preference RadioGroup
        val apiGroup = findViewById<RadioGroup>(R.id.api_preference_group)

        // Set current selection
        val currentApiPref = widgetStateManager.getApiPreference()
        val selectedApiId = when (currentApiPref) {
            ApiPreference.ALTERNATE -> R.id.radio_api_alternate
            ApiPreference.PREFER_NWS -> R.id.radio_api_nws
            ApiPreference.PREFER_OPENMETEO -> R.id.radio_api_openmeteo
        }
        apiGroup.check(selectedApiId)

        // Listen for changes
        apiGroup.setOnCheckedChangeListener { _, checkedId ->
            val preference = when (checkedId) {
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

        val logText = buildString {
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
}
