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
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.WeatherObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class WeatherObservationsActivity : AppCompatActivity() {
    private val TAG = "WeatherObservations"
    private val database: WeatherDatabase by lazy { WeatherDatabase.getDatabase(applicationContext) }
    private val widgetStateManager: WidgetStateManager by lazy { WidgetStateManager(applicationContext, database.appLogDao()) }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ObservationAdapter
    private var currentSource: WeatherSource = WeatherSource.NWS
    private var appWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

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
        currentSource = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.getCurrentDisplaySource(appWidgetId)
        } else {
            widgetStateManager.getVisibleSourcesOrder().firstOrNull() ?: WeatherSource.NWS
        }
        updateApiButton()

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.close_button).setOnClickListener { finish() }

        findViewById<TextView>(R.id.api_source_button).setOnClickListener {
            cycleSource()
        }

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadObservations()
        loadFetchLogs()
    }

    private fun updateApiButton() {
        findViewById<TextView>(R.id.api_source_button).text = currentSource.shortDisplayName
    }

    private fun cycleSource() {
        val visibleSources = widgetStateManager.getVisibleSourcesOrder()
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

    private fun showRenameDialog(entity: WeatherObservationEntity) {
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
        val prefs = getSharedPreferences("weather_widget_prefs", android.content.Context.MODE_PRIVATE)
        val key = String.format("alias_%.3f_%.3f", lat, lon)
        prefs.edit().putString(key, name).apply()
    }

    private fun loadObservations() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val observations = if (currentSource == WeatherSource.NWS) {
                    // Fetch detailed multi-station observations from the last 24 hours
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    database.weatherObservationDao().getRecentObservations(sinceMs)
                        .filter { !it.stationId.contains("OPEN_METEO_") && !it.stationId.contains("WEATHER_API_") }
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }
                } else {
                    // For other sources, show POIs if they exist, or fallback to the latest single reading
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    val poiPrefix = "${currentSource.id}_"
                    val pois = database.weatherObservationDao().getRecentObservations(sinceMs)
                        .filter { it.stationId.startsWith(poiPrefix) }
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }

                    if (pois.isNotEmpty()) {
                        pois
                    } else {
                        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        val latest = database.weatherDao().getLatestWeatherBySource(currentSource.id)
                        val currentTemp = if (latest != null) {
                            database.currentTempDao().getCurrentTemp(todayStr, currentSource.id, latest.locationLat, latest.locationLon)
                        } else null
                        if (latest != null && currentTemp != null) {
                            listOf(WeatherObservationEntity(
                                stationId = latest.stationId ?: currentSource.shortDisplayName,
                                stationName = latest.locationName,
                                timestamp = currentTemp.observedAt,
                                temperature = currentTemp.temperature,
                                condition = currentTemp.condition ?: latest.condition,
                                locationLat = latest.locationLat,
                                locationLon = latest.locationLon,
                                distanceKm = 0f,
                                stationType = "OFFICIAL",
                                fetchedAt = currentTemp.fetchedAt
                            ))
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
                val tag = if (currentSource == WeatherSource.NWS) "CURR_FETCH_STATION" else "CURR_FETCH_POI"
                val allLogs = database.appLogDao().getLogsByTag(tag, 100)
                
                // Filter logs that belong to the current source
                val filteredLogs = allLogs.filter { it.message.contains("source=${currentSource.id}") }
                    .take(30)

                val logText = filteredLogs.joinToString("\n") { log ->
                    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(log.timestamp))
                    "[$time] ${log.message.replace("source=${currentSource.id} ", "")}"
                }
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.fetch_logs).text = if (logText.isEmpty()) "No recent fetch logs for ${currentSource.shortDisplayName}." else logText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading fetch logs", e)
            }
        }
    }

    internal class ObservationAdapter(private val onItemClick: (WeatherObservationEntity) -> Unit) : RecyclerView.Adapter<ObservationAdapter.ViewHolder>() {
        internal var items: List<WeatherObservationEntity> = emptyList()
        private val timeFormatter = DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault())

        fun submitList(newList: List<WeatherObservationEntity>) {
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
            
            holder.stationTypeBadge.text = item.stationType
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
