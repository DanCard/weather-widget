package com.weatherwidget.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import dagger.hilt.android.AndroidEntryPoint

import com.weatherwidget.R
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.WidgetStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var weatherRepository: WeatherRepository

    private var latestLocation: Pair<Double, Double>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupViews()
    }

    private fun setupViews() {
        // API Sources ordered checkable list
        setupApiSourcesList()

        // Feature Tour button
        val featureTourButton = findViewById<Button>(R.id.view_feature_tour_button)
        featureTourButton.setOnClickListener {
            val intent = Intent(this, FeatureTourActivity::class.java)
            startActivity(intent)
        }

        val viewAppLogsButton = findViewById<Button>(R.id.view_app_logs_button)
        viewAppLogsButton.setOnClickListener {
            val intent = Intent(this, AppLogsActivity::class.java)
            startActivity(intent)
        }

        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
    }

    /** All configurable weather sources (excludes GENERIC_GAP). */
    private val allSources = listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO)

    private fun sourceDescription(source: WeatherSource): String = when (source) {
        WeatherSource.SILURIAN -> getString(R.string.api_source_silurian_desc)
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
        lifecycleScope.launch(Dispatchers.IO) {
            latestLocation = weatherRepository.getLatestLocation()
            withContext(Dispatchers.Main) {
                rebuildSourceRows(container)
            }
        }
    }

    private fun rebuildSourceRows(container: LinearLayout) {
        container.removeAllViews()
        val location = latestLocation
        val visibleSources = if (location != null) {
            widgetStateManager.getEffectiveVisibleSourcesOrder(location.first, location.second)
        } else {
            widgetStateManager.getVisibleSourcesOrder()
        }

        // Build full ordered list: visible sources first (in order), then hidden sources
        val availableSources = if (location != null && visibleSources.none { it == WeatherSource.OPEN_METEO }) {
            allSources.filter { it != WeatherSource.OPEN_METEO }
        } else {
            allSources
        }
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
}
