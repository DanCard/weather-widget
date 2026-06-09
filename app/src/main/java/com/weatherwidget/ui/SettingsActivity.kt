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
import android.appwidget.AppWidgetManager
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

import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.util.DeviceUtils

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var sharedLocationResolver: SharedLocationResolver

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

        // Icon Gallery
        setupIconGallery()

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
            workManager.enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                forecastRefreshWork,
            )
            workManager.enqueueUniqueWork(
                WeatherWidgetProvider.WORK_NAME_CURRENT_TEMP,
                ExistingWorkPolicy.REPLACE,
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

        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.settings_title).setOnClickListener {
            finish()
        }
    }

    private data class GalleryIcon(val drawableRes: Int, val stringRes: Int)

    private val allGalleryIcons = listOf(
        // Clear / Sunny
        GalleryIcon(R.drawable.ic_weather_clear, R.string.gallery_icon_clear),
        GalleryIcon(R.drawable.ic_weather_mostly_clear, R.string.gallery_icon_mostly_clear),
        GalleryIcon(R.drawable.ic_weather_horizon_sun, R.string.gallery_icon_horizon_sun),

        // Cloudy
        GalleryIcon(R.drawable.ic_weather_partly_cloudy, R.string.gallery_icon_partly_cloudy),
        GalleryIcon(R.drawable.ic_weather_mostly_cloudy, R.string.gallery_icon_mostly_cloudy),
        GalleryIcon(R.drawable.ic_weather_cloudy, R.string.gallery_icon_cloudy),

        // Night
        GalleryIcon(R.drawable.ic_weather_night, R.string.gallery_icon_night),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_night, R.string.gallery_icon_partly_cloudy_night),
        GalleryIcon(R.drawable.ic_weather_mostly_cloudy_night, R.string.gallery_icon_mostly_cloudy_night),

        // Rain
        GalleryIcon(R.drawable.ic_weather_rain, R.string.gallery_icon_rain),
        GalleryIcon(R.drawable.ic_weather_cloudy_chance_rain, R.string.gallery_icon_chance_rain),
        GalleryIcon(R.drawable.ic_weather_cloudy_slight_chance_rain, R.string.gallery_icon_slight_rain),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_chance_rain, R.string.gallery_icon_partly_cloudy_chance_rain),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, R.string.gallery_icon_partly_cloudy_slight_rain),

        // Rain (Night)
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_chance_rain_night, R.string.gallery_icon_partly_cloudy_chance_rain_night),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night, R.string.gallery_icon_partly_cloudy_slight_rain_night),

        // Fog
        GalleryIcon(R.drawable.ic_weather_fog, R.string.gallery_icon_fog),
        GalleryIcon(R.drawable.ic_weather_fog_sunny, R.string.gallery_icon_fog_sunny),
        GalleryIcon(R.drawable.ic_weather_fog_light, R.string.gallery_icon_fog_light),
        GalleryIcon(R.drawable.ic_weather_fog_dense, R.string.gallery_icon_fog_dense),
        GalleryIcon(R.drawable.ic_weather_fog_cloudy, R.string.gallery_icon_fog_cloudy),
        GalleryIcon(R.drawable.ic_weather_fog_night, R.string.gallery_icon_fog_night),
        GalleryIcon(R.drawable.ic_weather_fog_light_night, R.string.gallery_icon_fog_light_night),

        // Others
        GalleryIcon(R.drawable.ic_weather_snow, R.string.gallery_icon_snow),
        GalleryIcon(R.drawable.ic_weather_storm, R.string.gallery_icon_storm),
        GalleryIcon(R.drawable.ic_weather_wind, R.string.gallery_icon_wind),
    )

    private fun setupIconGallery() {
        val container = findViewById<FlexboxLayout>(R.id.icon_gallery_container)
        container.removeAllViews()

        for (icon in allGalleryIcons) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_gallery_icon, container, false)
            val imageView = itemView.findViewById<ImageView>(R.id.gallery_icon_image)
            val textView = itemView.findViewById<TextView>(R.id.gallery_icon_name)

            imageView.setImageResource(icon.drawableRes)
            textView.setText(icon.stringRes)

            container.addView(itemView)
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

    private fun setupLocationSettings() {
        val locationSection = findViewById<View>(R.id.location_settings_section)
        
        // Hide location settings if it's a device that reports standard GPS
        if (DeviceUtils.reportsStandardGps(this)) {
            locationSection.visibility = View.GONE
            return
        }

        locationSection.visibility = View.VISIBLE
        val latInput = findViewById<EditText>(R.id.lat_input)
        val lonInput = findViewById<EditText>(R.id.lon_input)
        val saveButton = findViewById<Button>(R.id.save_location_button)
        val locationLabel = findViewById<TextView>(R.id.current_location_label)

        // Get list of active widget IDs
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(this, WeatherWidgetProvider::class.java)
        )

        // Find current location: try first active widget, then fallback to default POI
        var currentLat: Double? = null
        var currentLon: Double? = null
        var labelText = "No location set"

        if (widgetIds.isNotEmpty()) {
            val firstWidgetId = widgetIds[0]
            val widgetLocation = widgetStateManager.getWidgetLocation(firstWidgetId)
            if (widgetLocation != null) {
                currentLat = widgetLocation.first
                currentLon = widgetLocation.second
                labelText = "Widget Location: ${String.format("%.4f", currentLat)}, ${String.format("%.4f", currentLon)}"
            }
        }

        if (currentLat == null || currentLon == null) {
            // Fallback to historical_pois default POI
            val weatherPrefs = SharedPreferencesUtil.getPrefs(this, "weather_prefs")
            val historicalPois = weatherPrefs.getString("historical_pois", null)
            val lastPoi = historicalPois
                ?.split(";")
                ?.lastOrNull()
                ?.split("|")
                ?.takeLast(3)
                ?.let { parts ->
                    if (parts.size == 3) {
                        parts[1].toDoubleOrNull()?.let { lat -> parts[2].toDoubleOrNull()?.let { lon -> lat to lon } }
                    } else {
                        null
                    }
                }
            if (lastPoi != null) {
                currentLat = lastPoi.first
                currentLon = lastPoi.second
                labelText = "Default Location: ${String.format("%.4f", currentLat)}, ${String.format("%.4f", currentLon)}"
            }
        }

        if (currentLat == null || currentLon == null) {
            // Ultimate fallback to Mountain View
            currentLat = WeatherWidgetWorker.DEFAULT_LAT
            currentLon = WeatherWidgetWorker.DEFAULT_LON
            labelText = "Default Location: ${String.format("%.4f", currentLat)}, ${String.format("%.4f", currentLon)}"
        }

        latInput.setText(currentLat.toString())
        lonInput.setText(currentLon.toString())
        locationLabel.text = labelText

        saveButton.setOnClickListener {
            val lat = latInput.text.toString().toDoubleOrNull()
            val lon = lonInput.text.toString().toDoubleOrNull()
            if (lat != null && lat in -90.0..90.0 && lon != null && lon in -180.0..180.0) {
                lifecycleScope.launch {
                    try {
                        val resolved = sharedLocationResolver.fromCoordinates(lat, lon)
                        locationLabel.text = "Saved Location: ${resolved.label}"
                        saveLocationGlobally(lat, lon, resolved.label, widgetIds)
                    } catch (e: Exception) {
                        locationLabel.text = "Saved Location: ${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                        saveLocationGlobally(lat, lon, "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}", widgetIds)
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.invalid_coordinates_range), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveLocationGlobally(lat: Double, lon: Double, label: String, widgetIds: IntArray) {
        LocationUpdater.applyToAllWidgets(this, lat, lon, label)
        Toast.makeText(this, getString(R.string.location_saved_success), Toast.LENGTH_SHORT).show()
    }
}
