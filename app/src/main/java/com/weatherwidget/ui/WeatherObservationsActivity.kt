package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class WeatherObservationsActivity : AppCompatActivity() {
    private val TAG = "WeatherObservations"
    
    @Inject
    lateinit var weatherRepository: WeatherRepository
    
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var observationRepository: com.weatherwidget.data.repository.ObservationRepository

    @Inject
    lateinit var appLogDao: com.weatherwidget.data.local.AppLogDao

    @Inject
    lateinit var forecastDao: com.weatherwidget.data.local.ForecastDao

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ObservationAdapter
    private var currentSource: WeatherSource = WeatherSource.NWS
    private var appWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    private var activeLocation: Pair<Double, Double>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_observations)

        recyclerView = findViewById(R.id.observations_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ObservationAdapter { entity ->
            if (entity.stationId.contains("_HIST_")) {
                showRenameDialog(entity)
            }
        }
        recyclerView.adapter = adapter

        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        activeLocation = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.getWidgetLocation(appWidgetId)
        } else {
            null
        }
        currentSource = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.getCurrentDisplaySource(appWidgetId)
        } else {
            effectiveVisibleSources().firstOrNull() ?: WeatherSource.NWS
        }
        updateApiButton()

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).setOnClickListener { finish() }
        findViewById<Button>(R.id.close_button).setOnClickListener { finish() }

        findViewById<TextView>(R.id.api_source_button).setOnClickListener {
            cycleSource()
        }

        findViewById<View>(R.id.refresh_button).setOnClickListener {
            refreshData()
        }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadObservations()
        loadFetchLogs()
    }

    private fun refreshData() {
        lifecycleScope.launch {
            val location = activeLocation ?: withContext(Dispatchers.IO) {
                weatherRepository.getLatestLocation()
            }
            
            if (location == null) {
                android.widget.Toast.makeText(this@WeatherObservationsActivity, "No location available to refresh", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            findViewById<View>(R.id.refresh_button).isEnabled = false
            findViewById<View>(R.id.refresh_button).alpha = 0.5f
            
            withContext(Dispatchers.IO) {
                weatherRepository.refreshCurrentTemperature(
                    location.first,
                    location.second,
                    "Manual Refresh",
                    source = currentSource,
                    reason = "user_observations_screen",
                    forceRefresh = true
                )
            }
            
            loadObservations()
            loadFetchLogs()
            
            findViewById<View>(R.id.refresh_button).isEnabled = true
            findViewById<View>(R.id.refresh_button).alpha = 1.0f
            
            android.widget.Toast.makeText(this@WeatherObservationsActivity, "Refreshed ${currentSource.shortDisplayName}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateApiButton() {
        findViewById<TextView>(R.id.api_source_button).text = currentSource.shortDisplayName
    }

    private fun cycleSource() {
        val visibleSources = effectiveVisibleSources()
        if (visibleSources.isEmpty()) return
        
        val currentIndex = visibleSources.indexOf(currentSource)
        val nextIndex = (currentIndex + 1) % visibleSources.size
        currentSource = visibleSources[nextIndex]

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.setCurrentDisplaySource(appWidgetId, currentSource)
        }
        
        updateApiButton()
        loadObservations()
        loadFetchLogs()
    }

    private fun effectiveVisibleSources(): List<WeatherSource> {
        val location = activeLocation
        return if (location != null) {
            widgetStateManager.getEffectiveVisibleSourcesOrder(location.first, location.second)
        } else {
            widgetStateManager.getVisibleSourcesOrder()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // If we were launched from a widget and changed the source, trigger a UI update
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val refreshIntent = Intent(this, com.weatherwidget.widget.WeatherWidgetProvider::class.java).apply {
                action = com.weatherwidget.widget.WeatherWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.weatherwidget.widget.WeatherWidgetProvider.EXTRA_UI_ONLY, true)
            }
            sendBroadcast(refreshIntent)
        }
    }

    private fun showRenameDialog(entity: ObservationEntity) {
        val editText = android.widget.EditText(this).apply {
            setText(entity.stationName.replace("Meteo: Recent: ", "").replace("WAPI: Recent: ", ""))
            setSelectAllOnFocus(true)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Name Location")
            .setMessage("Give this location a custom name (e.g., Home, Work, School)")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    saveLocationAlias(entity.locationLat, entity.locationLon, newName)
                    loadObservations() // Refresh UI
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveLocationAlias(lat: Double, lon: Double, name: String) {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, "weather_widget_prefs")
        val key = String.format("alias_%.3f_%.3f", lat, lon)
        prefs.edit().putString(key, name).apply()
    }

    private fun loadObservations() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val observations = if (currentSource == WeatherSource.NWS) {
                    // Fetch detailed multi-station observations from the last 24 hours
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    observationRepository.getRecentObservations(sinceMs)
                        .filter { WeatherObservationsSupport.matchesObservationSource(it.stationId, currentSource) }
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }
                } else {
                    // For other sources, show POIs if they exist, or fallback to the latest single reading
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    val pois = observationRepository.getRecentObservations(sinceMs)
                        .filter { WeatherObservationsSupport.matchesObservationSource(it.stationId, currentSource) }
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }

                    if (pois.isNotEmpty()) {
                        pois
                    } else {
                        val latest = forecastDao.getLatestWeatherBySource(currentSource.id)
                        if (latest != null) {
                            val todayStartMs = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val mainObs = observationRepository.getMainObservationsWithComputedNwsBlend(
                                latest.locationLat,
                                latest.locationLon,
                                todayStartMs,
                            )
                            val sourceObs = mainObs.firstOrNull { com.weatherwidget.widget.ObservationResolver.inferSource(it.stationId) == currentSource.id }
                            if (sourceObs != null) {
                                listOf(sourceObs)
                            } else {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "loadObservations: currentSource=${currentSource.id} items=${observations.map { it.stationId }}")
                    adapter.submitList(observations)
                    val subtitleView = findViewById<TextView>(R.id.subtitle)
                    if (observations.isEmpty()) {
                        subtitleView.text = "No recent observations found for ${currentSource.displayName}."
                    } else if (currentSource == WeatherSource.NWS) {
                        subtitleView.text = "Real-time data from nearby stations"
                    } else {
                        subtitleView.text = "Latest reading from ${currentSource.displayName}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading observations", e)
            }
        }
    }

    private fun loadFetchLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val filteredLogs = appLogDao.getRecentLogs(200)
                    .filter { WeatherObservationsSupport.matchesFetchLog(it, currentSource) }
                    .take(30)

                val logText = filteredLogs.joinToString("\n") { log ->
                    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(log.timestamp))
                    "[$time] ${WeatherObservationsSupport.formatFetchLog(log, currentSource)}"
                }
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.fetch_logs).text = if (logText.isEmpty()) "No recent fetch logs for ${currentSource.shortDisplayName}." else logText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading fetch logs", e)
            }
        }
    }

    internal object WeatherObservationsSupport {
        private val runLevelTags = setOf("CURR_FETCH_DONE", "CURR_FETCH_EXCEPTION", "CURR_FETCH_SKIP")
        private val sourcePrefixes =
            mapOf(
                WeatherSource.OPEN_METEO to "OPEN_METEO_",
                WeatherSource.WEATHER_API to "WEATHER_API_",
                WeatherSource.SILURIAN to "SILURIAN_",
            )

        fun matchesObservationSource(stationId: String, source: WeatherSource): Boolean =
            when (source) {
                WeatherSource.NWS -> stationId != "NWS_MAIN" && sourcePrefixes.values.none { prefix -> stationId.startsWith(prefix) }
                else -> stationId.startsWith(sourcePrefixes[source] ?: return false)
            }

        fun matchesFetchLog(log: AppLogEntity, source: WeatherSource): Boolean =
            when (log.tag) {
                "CURR_FETCH_START" -> log.message.containsTargetSource(source)
                "CURR_FETCH_ERROR" -> log.message.contains("source=${source.id}")
                in runLevelTags -> true
                else -> false
            }

        fun formatFetchLog(log: AppLogEntity, source: WeatherSource): String {
            val message =
                when (log.tag) {
                    "CURR_FETCH_START" -> log.message
                    "CURR_FETCH_ERROR" -> log.message.removePrefix("source=${source.id} ")
                    else -> log.message
                }

            return when (log.tag) {
                "CURR_FETCH_START" -> "start $message"
                "CURR_FETCH_DONE" -> "done $message"
                "CURR_FETCH_SKIP" -> "skip $message"
                "CURR_FETCH_ERROR" -> "error $message"
                "CURR_FETCH_EXCEPTION" -> "exception $message"
                else -> message
            }
        }

        private fun String.containsTargetSource(source: WeatherSource): Boolean {
            val targets = substringAfter("targets=", missingDelimiterValue = "")
            if (targets.isEmpty()) return false
            return targets.split(",")
                .map { it.trim() }
                .any { it == source.id }
        }
    }

    internal class ObservationAdapter(private val onItemClick: (ObservationEntity) -> Unit) : RecyclerView.Adapter<ObservationAdapter.ViewHolder>() {
        internal var items: List<ObservationEntity> = emptyList()
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

        fun submitList(newList: List<ObservationEntity>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_weather_observation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.stationName.text = item.stationName
            val distanceStr = if (item.distanceKm > 0) String.format(" • %.1f mi", item.distanceKm * 0.621371f) else ""
            holder.stationIdTime.text = "${item.stationId}$distanceStr"
            
            holder.stationTypeBadge.text = "Station type: ${item.stationType}"
            if (item.stationType == "OFFICIAL") {
                holder.stationTypeBadge.setBackgroundResource(R.drawable.rounded_button_blue)
            } else {
                holder.stationTypeBadge.setBackgroundResource(R.drawable.rounded_button_gray)
            }

            holder.observationTime.text = "Station Reported: ${timeFormatter.format(Instant.ofEpochMilli(item.timestamp))}"
            holder.fetchTime.text = "App Fetched: ${timeFormatter.format(Instant.ofEpochMilli(item.fetchedAt))}"
            
            holder.temperature.text = String.format("%.1f°", item.temperature)
            holder.condition.text = item.condition
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val stationName: TextView = view.findViewById(R.id.station_name)
            val stationIdTime: TextView = view.findViewById(R.id.station_id_time)
            val stationTypeBadge: TextView = view.findViewById(R.id.station_type_badge)
            val observationTime: TextView = view.findViewById(R.id.observation_time)
            val fetchTime: TextView = view.findViewById(R.id.fetch_time)
            val temperature: TextView = view.findViewById(R.id.temperature)
            val condition: TextView = view.findViewById(R.id.condition)
        }
    }
}
