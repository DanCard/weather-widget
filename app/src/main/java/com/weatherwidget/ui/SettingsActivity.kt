package com.weatherwidget.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.flexbox.FlexboxLayout
import dagger.hilt.android.AndroidEntryPoint

import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.WidgetStateManager
import java.util.UUID

import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    private fun setupViews() {
        // API Sources ordered checkable list
        setupApiSourcesList()

        // API Keys section
        setupApiKeysList()

        // Personal weather station discount slider
        setupPersonalStationDiscount()

        // Location Settings
        setupLocationSettings()

        // Refresh data button
        val refreshDataButton = findViewById<Button>(R.id.refresh_data_button)
        refreshDataButton.setOnClickListener {
            val forecastRefreshWork =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .build(),
                    )
                    .build()
            val currentRefreshWork =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, "settings_manual_refresh")
                            .build(),
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()

            val workManager = WorkManager.getInstance(this)
            // APPEND_OR_REPLACE (not REPLACE): a manual "Refresh now" must never cancel a running
            // worker — that segfaults ART on debuggable builds ([[samsung_widget_dead_native_sigsegv]]).
            // The fresh fetch still runs, queued after any in-flight one.
            workManager.enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                forecastRefreshWork,
            )
            workManager.enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                currentRefreshWork,
            )

            Toast.makeText(this, getString(R.string.refresh_now_enqueued_toast), Toast.LENGTH_SHORT).show()
            refreshDataButton.isEnabled = false

            observeRefreshCompletion(
                workManager = workManager,
                refreshButton = refreshDataButton,
                forecastWorkId = forecastRefreshWork.id,
                currentWorkId = currentRefreshWork.id,
            )
        }

        val viewAppLogsButton = findViewById<Button>(R.id.view_app_logs_button)
        viewAppLogsButton.setOnClickListener {
            val intent = Intent(this, AppLogsActivity::class.java)
            startActivity(intent)
        }

        val submitBugReportButton = findViewById<Button>(R.id.submit_bug_report_button)
        submitBugReportButton.setOnClickListener {
            val intent = Intent(this, BugReportActivity::class.java)
            startActivity(intent)
        }

        val viewIconGalleryButton = findViewById<Button>(R.id.view_icon_gallery_button)
        viewIconGalleryButton.setOnClickListener {
            val intent = Intent(this, IconGalleryActivity::class.java)
            startActivity(intent)
        }

        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.settings_title).setOnClickListener {
            finish()
        }
    }

    /** All configurable weather sources (excludes GENERIC_GAP). */
    private val allSources =
        listOf(
            WeatherSource.NWS,
            WeatherSource.TOMORROW_IO,
            WeatherSource.OPEN_METEO,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.VISUAL_CROSSING,
        )

    private val sourcesRequiringKeys =
        listOf(
            WeatherSource.TOMORROW_IO,
            WeatherSource.SILURIAN,
            WeatherSource.WEATHER_API,
            WeatherSource.VISUAL_CROSSING,
            WeatherSource.OPEN_WEATHER_MAP,
        )

    private fun setupApiKeysList() {
        val container = findViewById<LinearLayout>(R.id.api_keys_container)
        container.removeAllViews()

        for (source in sourcesRequiringKeys) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_api_key, container, false)
            val nameView = row.findViewById<TextView>(R.id.source_name)
            val inputView = row.findViewById<EditText>(R.id.api_key_input)

            nameView.text = source.displayName
            inputView.setText(widgetStateManager.getApiKey(source))

            inputView.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val key = s?.toString()?.trim()
                    widgetStateManager.setApiKey(source, if (key.isNullOrBlank()) null else key)
                }
            })

            container.addView(row)
        }
    }

    private fun sourceDescription(source: WeatherSource): String = when (source) {
        WeatherSource.SILURIAN -> getString(R.string.api_source_silurian_desc)
        WeatherSource.NWS -> getString(R.string.api_source_nws_desc)
        WeatherSource.TOMORROW_IO -> getString(R.string.api_source_tomorrowio_desc)
        WeatherSource.VISUAL_CROSSING -> getString(R.string.api_source_visualcrossing_desc)
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
        val availableSources = allSources
        val hiddenSources = availableSources.filter { it !in visibleSources }
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
                    Log.d("SOURCE_ORDER", "Checkbox: enabled ${source.name}, new list=$current")
                } else {
                    if (current.size <= 1) {
                        // Prevent unchecking the last source
                        checkbox.isChecked = true
                        Toast.makeText(this, getString(R.string.must_keep_one_source), Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }
                    current.remove(source)
                    Log.d("SOURCE_ORDER", "Checkbox: disabled ${source.name}, new list=$current")
                }
                widgetStateManager.setVisibleSourcesOrder(current)
                rebuildSourceRows(container)
            }

            upButton.setOnClickListener {
                val current = widgetStateManager.getVisibleSourcesOrder().toMutableList()
                val pos = current.indexOf(source)
                if (pos > 0) {
                    Log.d("SOURCE_ORDER", "Move up: ${source.name} from pos $pos to ${pos - 1}")
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
                    Log.d("SOURCE_ORDER", "Move down: ${source.name} from pos $pos to ${pos + 1}")
                    current[pos] = current[pos + 1]
                    current[pos + 1] = source
                    widgetStateManager.setVisibleSourcesOrder(current)
                    rebuildSourceRows(container)
                }
            }

            container.addView(row)
        }
    }

    private fun observeRefreshCompletion(
        workManager: WorkManager,
        refreshButton: Button,
        forecastWorkId: UUID,
        currentWorkId: UUID,
    ) {
        var forecastFinished = false
        var currentFinished = false
        var refreshSucceeded = false
        var handled = false

        fun onFinished(workInfo: WorkInfo?, isForecastWork: Boolean) {
            if (workInfo == null || !workInfo.state.isFinished || handled) return

            if (isForecastWork) {
                forecastFinished = true
            } else {
                currentFinished = true
            }
            refreshSucceeded = refreshSucceeded || workInfo.state == WorkInfo.State.SUCCEEDED

            if (forecastFinished && currentFinished) {
                handled = true
                refreshButton.isEnabled = true
            }
        }

        workManager.getWorkInfoByIdLiveData(forecastWorkId).observe(this) { workInfo ->
            onFinished(workInfo, isForecastWork = true)
        }
        workManager.getWorkInfoByIdLiveData(currentWorkId).observe(this) { workInfo ->
            onFinished(workInfo, isForecastWork = false)
        }
    }

    private fun setupPersonalStationDiscount() {
        val seekBar = findViewById<SeekBar>(R.id.personal_station_discount_seekbar)
        val valueLabel = findViewById<TextView>(R.id.personal_station_discount_value)

        fun labelFor(percent: Int): String = when (percent) {
            0 -> "0% — no discount (counts the same as official)"
            100 -> "100% — personal stations ignored"
            else -> "$percent% discount"
        }

        val initial = widgetStateManager.getPersonalStationDiscountPercent()
        seekBar.progress = initial
        valueLabel.text = labelFor(initial)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = labelFor(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val percent = sb?.progress ?: return
                widgetStateManager.setPersonalStationDiscountPercent(percent)
                Log.d("SETTINGS", "Personal station discount set to $percent%")
            }
        })
    }

    private fun setupLocationSettings() {
        findViewById<Button>(R.id.set_location_button).setOnClickListener {
            startActivity(
                Intent(this, ConfigActivity::class.java)
                    .putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
            )
        }
        refreshLocationLabel()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from the location setup screen.
        refreshLocationLabel()
    }

    private fun refreshLocationLabel() {
        findViewById<TextView>(R.id.current_location_label).text =
            LocationUpdater.describeCurrentLocation(this)
    }
}
