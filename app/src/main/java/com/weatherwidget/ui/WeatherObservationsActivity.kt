package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.observations.ObservationOrigin
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.util.StationHistoryUrl
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class WeatherObservationsActivity : AppCompatActivity() {
    private val TAG = "WeatherObservations"

    /**
     * Dispatcher used for background operations.
     * Can be overridden in tests to provide synchronous execution.
     */
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    
    @Inject
    lateinit var weatherRepository: WeatherRepository
    
    @Inject
    lateinit var widgetStateManager: WidgetStateManager

    @Inject
    lateinit var observationRepository: com.weatherwidget.data.repository.ObservationRepository

    @Inject
    lateinit var appLogDao: com.weatherwidget.data.local.AppLogDao

    @Inject
    lateinit var observationDao: ObservationDao

    @Inject
    lateinit var forecastDao: com.weatherwidget.data.local.ForecastDao

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ObservationAdapter
    private var currentSource: WeatherSource = WeatherSource.NWS
    private var appWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    private var activeLocation: Pair<Double, Double>? = null
    private var loadObservationsJob: Job? = null
    private var loadFetchLogsJob: Job? = null
    private var selectedTab: Int = TAB_OBSERVATIONS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_observations)

        recyclerView = findViewById(R.id.observations_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ObservationAdapter(widgetStateManager.useCelsius()) { entity ->
            if (entity.stationId.contains("_HIST_")) {
                showRenameDialog(entity)
            } else {
                // NWS stations link to their public web history page; other sources have none.
                StationHistoryUrl.forStation(currentSource.id, entity.stationId)
                    ?.let { openStationHistory(it) }
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

        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, TAB_OBSERVATIONS) ?: TAB_OBSERVATIONS
        findViewById<View>(R.id.observations_tab).setOnClickListener {
            showTab(TAB_OBSERVATIONS)
        }
        findViewById<View>(R.id.fetch_logs_tab).setOnClickListener {
            showTab(TAB_FETCH_LOGS)
        }
        showTab(selectedTab)

        // android:scrollbars only draws the scrollbar track; the movement method is what lets the
        // user drag-scroll through the full-height Fetch Logs tab.
        findViewById<TextView>(R.id.fetch_logs).movementMethod = ScrollingMovementMethod.getInstance()

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
        observeCurrentObservationUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tab: Int) {
        selectedTab = tab
        val observationsSelected = tab == TAB_OBSERVATIONS

        findViewById<View>(R.id.observations_content).visibility =
            if (observationsSelected) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fetch_logs_content).visibility =
            if (observationsSelected) View.GONE else View.VISIBLE

        updateTab(
            tabView = findViewById(R.id.observations_tab),
            indicatorView = findViewById(R.id.observations_tab_indicator),
            selected = observationsSelected,
        )
        updateTab(
            tabView = findViewById(R.id.fetch_logs_tab),
            indicatorView = findViewById(R.id.fetch_logs_tab_indicator),
            selected = !observationsSelected,
        )
    }

    private fun updateTab(
        tabView: TextView,
        indicatorView: View,
        selected: Boolean,
    ) {
        tabView.isSelected = selected
        tabView.setTextColor(Color.parseColor(if (selected) "#4FC3F7" else "#AAAAAA"))
        indicatorView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeCurrentObservationUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    observationDao.observeLatestFetchedAt(),
                    appLogDao.observeLatestCurrentObservationFetchLogAt(),
                ) { latestObservationFetchAt, latestFetchLogAt ->
                    latestObservationFetchAt to latestFetchLogAt
                }
                    .distinctUntilChanged()
                    .debounce(autoRefreshDebounceMs)
                    .collect {
                        loadObservations()
                        loadFetchLogs()
                    }
            }
        }
    }

    private fun refreshData() {
        lifecycleScope.launch {
            val location = activeLocation ?: withContext(ioDispatcher) {
                weatherRepository.getLatestLocation()
            }
            
            if (location == null) {
                android.widget.Toast.makeText(this@WeatherObservationsActivity, getString(R.string.obs_no_location_to_refresh), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            findViewById<View>(R.id.refresh_button).isEnabled = false
            findViewById<View>(R.id.refresh_button).alpha = 0.5f
            
            withContext(ioDispatcher) {
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
            
            android.widget.Toast.makeText(this@WeatherObservationsActivity, getString(R.string.obs_refreshed_source, currentSource.shortDisplayName), android.widget.Toast.LENGTH_SHORT).show()
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
            val refreshIntent = Intent(this, com.weatherwidget.widget.WidgetActionReceiver::class.java).apply {
                action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
            }
            sendBroadcast(refreshIntent)
        }
    }

    private fun openStationHistory(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No browser to open $url", e)
        }
    }

    private fun showRenameDialog(entity: ObservationEntity) {
        val editText = android.widget.EditText(this).apply {
            setText(
                entity.stationName
                    .replace("Meteo: Recent: ", "")
                    .replace("VisCr: Recent: ", "")
                    .replace("WAPI: Recent: ", ""),
            )
            setSelectAllOnFocus(true)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.name_location_title)
            .setMessage(R.string.name_location_message)
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    saveLocationAlias(entity.locationLat, entity.locationLon, newName)
                    loadObservations() // Refresh UI
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveLocationAlias(lat: Double, lon: Double, name: String) {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, "weather_widget_prefs")
        val key = String.format("alias_%.3f_%.3f", lat, lon)
        prefs.edit().putString(key, name).apply()
    }

    @VisibleForTesting
    internal fun loadObservations() {
        loadObservationsJob?.cancel()
        loadObservationsJob = lifecycleScope.launch(ioDispatcher) {
            try {
                // Each observation row records the device location it was fetched under, and the 24h
                // window can straddle a move between locations (e.g. Austin → Bay Area), so scope the
                // list to the current location. Fall back to the unscoped query only if no location is
                // resolvable at all.
                val location = activeLocation ?: weatherRepository.getLatestLocation()
                suspend fun recentObservations(sinceMs: Long): List<ObservationEntity> =
                    if (location != null) {
                        // The proximity box is ~7 miles wide, so it also admits rows fetched under a
                        // *nearby* site the device visited earlier (Los Gatos stations lingering under
                        // a Mountain View fix). Nothing refreshes those, but they stay in the 24h
                        // window long enough to show up as extra stations beyond the MAX_RETRIES
                        // stations actually polled, so collapse the box to the current site.
                        LocationMatch.selectNearestSite(
                            observationRepository.getRecentObservationsNear(sinceMs, location.first, location.second),
                            location.first,
                            location.second,
                            { it.locationLat },
                            { it.locationLon },
                        )
                    } else {
                        observationRepository.getRecentObservations(sinceMs)
                    }

                val observations = if (currentSource == WeatherSource.NWS) {
                    // Fetch detailed multi-station observations from the last 24 hours
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    recentObservations(sinceMs)
                        .filter { WeatherObservationsSupport.matchesObservationSource(it.stationId, currentSource) }
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }
                } else {
                    // For other sources, show POIs if they exist, or fallback to the latest single reading
                    val sinceMs = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    val pois = recentObservations(sinceMs)
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
                            val sourceObs = mainObs.firstOrNull { it.api == currentSource.id }
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
                        subtitleView.text = getString(R.string.obs_subtitle_none_found, currentSource.displayName)
                    } else if (currentSource == WeatherSource.NWS) {
                        subtitleView.text = getString(R.string.obs_subtitle_nearby_stations)
                    } else {
                        subtitleView.text = getString(R.string.obs_subtitle_latest_reading, currentSource.displayName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading observations", e)
            }
        }
    }

    @VisibleForTesting
    internal fun loadFetchLogs() {
        loadFetchLogsJob?.cancel()
        loadFetchLogsJob = lifecycleScope.launch(ioDispatcher) {
            try {
                val filteredLogs = appLogDao.getCurrentObservationFetchLogs(200)
                    .filter { WeatherObservationsSupport.matchesFetchLog(it, currentSource) }

                val logText = filteredLogs.joinToString("\n") { log ->
                    val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(log.timestamp))
                    "[$time] ${WeatherObservationsSupport.formatFetchLog(log, currentSource)}"
                }
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.fetch_logs).text = if (logText.isEmpty()) "No current observation fetch logs found for ${currentSource.shortDisplayName}." else logText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading fetch logs", e)
            }
        }
    }

    internal object WeatherObservationsSupport {
        // Delegates to the shared matcher so Android and the desktop stations list filter synthetic
        // rows (NWS_BLEND, the NWS history backfill) identically. See ObservationSourceMatcher.
        fun matchesObservationSource(stationId: String, source: WeatherSource): Boolean =
            ObservationSourceMatcher.matchesObservationSource(stationId, source)

        fun matchesFetchLog(log: AppLogEntity, source: WeatherSource): Boolean =
            when (log.tag) {
                "CURR_FETCH_START",
                "CURR_FETCH_DONE",
                "CURR_FETCH_SKIP",
                -> log.message.containsTargetSource(source)
                "CURR_FETCH_ERROR",
                "CURR_FETCH_SOURCE_RESULT",
                "OBS_CURRENT_INSERT",
                -> log.message.containsSource(source)
                "OBS_HOURLY_BACKFILL_SKIP",
                "OBS_HOURLY_BACKFILL_REQ",
                -> log.message.containsSource(source)
                "OBS_HOURLY_BACKFILL_START",
                "OBS_HOURLY_BACKFILL_FAIL",
                "OBS_HOURLY_BACKFILL_STATION",
                "OBS_HOURLY_BACKFILL_STATION_FAIL",
                "OBS_HOURLY_BACKFILL_DONE",
                -> source == WeatherSource.NWS
                "CURR_FETCH_EXCEPTION",
                "CURR_FETCH_FAIL",
                "CURR_FETCH_CANCELLED",
                "CURR_FETCH_FRESH_SKIP",
                "CURR_FETCH_WORK_ENQUEUED",
                "CURR_FETCH_WORK_REQUESTED",
                "CURR_FETCH_WORK_STATE",
                "CURR_FETCH_WORK_RECOVERED",
                "CURR_FETCH_WORK_START",
                "CURR_FETCH_WORK_RESULT",
                "CURR_FETCH_WORK_CANCELLED",
                "CURR_FETCH_LOOP_STOP",
                -> true
                else -> false
            }

        fun formatFetchLog(log: AppLogEntity, source: WeatherSource): String {
            val message =
                when (log.tag) {
                    "CURR_FETCH_START", "CURR_FETCH_DONE" -> log.message
                    "CURR_FETCH_ERROR" -> log.message.removePrefix("source=${source.id} ")
                    else -> log.message
                }

            return when (log.tag) {
                "CURR_FETCH_START" -> "start $message"
                "CURR_FETCH_DONE" -> "done $message"
                "CURR_FETCH_SKIP" -> "skip $message"
                "CURR_FETCH_ERROR" -> "error $message"
                "CURR_FETCH_SOURCE_RESULT" -> "source $message"
                "OBS_CURRENT_INSERT" -> "insert $message"
                "OBS_HOURLY_BACKFILL_START" -> "hourly start $message"
                "OBS_HOURLY_BACKFILL_SKIP" -> "hourly skip $message"
                "OBS_HOURLY_BACKFILL_REQ" -> "hourly request $message"
                "OBS_HOURLY_BACKFILL_FAIL" -> "hourly fail $message"
                "OBS_HOURLY_BACKFILL_STATION" -> "hourly station $message"
                "OBS_HOURLY_BACKFILL_STATION_FAIL" -> "hourly station fail $message"
                "OBS_HOURLY_BACKFILL_DONE" -> "hourly done $message"
                "CURR_FETCH_EXCEPTION" -> "exception $message"
                "CURR_FETCH_FAIL" -> "fail $message"
                "CURR_FETCH_CANCELLED" -> "cancelled $message"
                "CURR_FETCH_FRESH_SKIP" -> "fresh skip $message"
                "CURR_FETCH_WORK_ENQUEUED" -> "enqueued $message"
                "CURR_FETCH_WORK_REQUESTED" -> "requested $message"
                "CURR_FETCH_WORK_STATE" -> "work state $message"
                "CURR_FETCH_WORK_RECOVERED" -> "recovered $message"
                "CURR_FETCH_WORK_START" -> "work start $message"
                "CURR_FETCH_WORK_RESULT" -> "work result $message"
                "CURR_FETCH_WORK_CANCELLED" -> "work cancelled $message"
                "CURR_FETCH_LOOP_STOP" -> "loop stop $message"
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

        private fun String.containsSource(source: WeatherSource): Boolean =
            contains("source=${source.id}")
    }

    internal class ObservationAdapter(
        private val useCelsius: Boolean,
        @get:VisibleForTesting internal val onItemClick: (ObservationEntity) -> Unit,
    ) : RecyclerView.Adapter<ObservationAdapter.ViewHolder>() {
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
            holder.stationIdTime.text = "${item.stationId}$distanceStr • "

            val context = holder.itemView.context
            val origin = ObservationOrigin.of(
                timestampMs = item.timestamp,
                qcFailed = item.qcFailed,
                isWebFallback = item.isWebFallback,
                nowMs = System.currentTimeMillis(),
            )
            val originStr = context.getString(
                when (origin) {
                    ObservationOrigin.Kind.QC_FAILED -> R.string.station_origin_qc_failed
                    ObservationOrigin.Kind.STALE -> R.string.station_origin_stale
                    ObservationOrigin.Kind.WEB -> R.string.station_origin_web
                    ObservationOrigin.Kind.API -> R.string.station_origin_api
                }
            )
            holder.stationTypeBadge.text = context.getString(R.string.station_type_origin_format, item.stationType, originStr)
            holder.stationTypeBadge.setTextColor(
                when {
                    // Both error states mean "this reading is not in the blend" — say so in red.
                    origin == ObservationOrigin.Kind.QC_FAILED || origin == ObservationOrigin.Kind.STALE -> COLOR_ERROR
                    item.stationType == "OFFICIAL" -> COLOR_TYPE_OFFICIAL
                    else -> COLOR_TYPE_PERSONAL
                }
            )

            holder.observationFetchTimes.text = buildTimesLine(
                context,
                timeFormatter.format(Instant.ofEpochMilli(item.timestamp)),
                timeFormatter.format(Instant.ofEpochMilli(item.fetchedAt))
            )

            if (origin == ObservationOrigin.Kind.QC_FAILED || origin == ObservationOrigin.Kind.STALE) {
                // Rejected by upstream QC, or too old to carry weight — either way the value is not
                // part of the blend, so showing it invites comparing it against a temp it never fed.
                holder.temperature.text = "—"
                holder.temperature.setTextColor(COLOR_TEXT_SECONDARY)
            } else {
                val displayTemp = if (useCelsius) com.weatherwidget.shared.util.TempUtils.fahrenheitToCelsius(item.temperature) else item.temperature
                holder.temperature.text = String.format("%.1f°", displayTemp)
                holder.temperature.setTextColor(obsTempToColor(item.temperature))
            }
            holder.condition.text = item.condition
        }

        override fun getItemCount() = items.size

        // "Reported"/"Fetched" captions stay small and grey; the time values are the scan
        // target, so they render half again larger with the amber/blue staleness hues.
        private fun buildTimesLine(context: android.content.Context, reported: String, fetched: String): CharSequence {
            val builder = SpannableStringBuilder()
            fun appendSpan(text: String, color: Int, sizeSp: Int?) {
                val start = builder.length
                builder.append(text)
                builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (sizeSp != null) {
                    builder.setSpan(AbsoluteSizeSpan(sizeSp, true), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            appendSpan(context.getString(R.string.obs_reported_prefix), COLOR_TEXT_SECONDARY, null)
            appendSpan(reported, COLOR_TIME_REPORTED, TIME_VALUE_SP)
            appendSpan(context.getString(R.string.obs_fetched_separator), COLOR_TEXT_SECONDARY, null)
            appendSpan(fetched, COLOR_TIME_FETCHED, TIME_VALUE_SP)
            return builder
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val stationName: TextView = view.findViewById(R.id.station_name)
            val stationIdTime: TextView = view.findViewById(R.id.station_id_time)
            val stationTypeBadge: TextView = view.findViewById(R.id.station_type_badge)
            val observationFetchTimes: TextView = view.findViewById(R.id.observation_fetch_times)
            val temperature: TextView = view.findViewById(R.id.temperature)
            val condition: TextView = view.findViewById(R.id.condition)
        }

        companion object {
            private const val TIME_VALUE_SP = 21
            private val COLOR_TEXT_SECONDARY = Color.parseColor("#AAAAAA")
            private val COLOR_TIME_REPORTED = Color.parseColor("#E8A24E")
            private val COLOR_TIME_FETCHED = Color.parseColor("#4FC3F7")
            private val COLOR_TYPE_OFFICIAL = Color.parseColor("#2BFF88")
            private val COLOR_TYPE_PERSONAL = Color.parseColor("#B0B0B8")
            // Matches the desktop staleness/error accent (#FF3366): QC-rejected and stale readings.
            private val COLOR_ERROR = Color.parseColor("#FF3366")

            private val COLOR_TEMP_COLD = Color.parseColor("#007AFF")
            private val COLOR_TEMP_MILD = Color.parseColor("#E8A24E")
            private val COLOR_TEMP_HOT = Color.parseColor("#FF3B30")

            /**
             * Temp→color tuned for text on near-black cards — mirrors the desktop app's
             * trayTempToColor (deeper blue than TemperatureGraphStyle's gradient, which
             * washes out at text sizes on dark backgrounds).
             */
            internal fun obsTempToColor(temp: Float): Int {
                fun blend(c1: Int, c2: Int, fraction: Float): Int {
                    val f = fraction.coerceIn(0f, 1f)
                    return Color.rgb(
                        (Color.red(c1) * (1 - f) + Color.red(c2) * f).toInt(),
                        (Color.green(c1) * (1 - f) + Color.green(c2) * f).toInt(),
                        (Color.blue(c1) * (1 - f) + Color.blue(c2) * f).toInt()
                    )
                }
                return when {
                    temp <= 50f -> COLOR_TEMP_COLD
                    temp >= 90f -> COLOR_TEMP_HOT
                    temp <= 70f -> blend(COLOR_TEMP_COLD, COLOR_TEMP_MILD, (temp - 50f) / 20f)
                    else -> blend(COLOR_TEMP_MILD, COLOR_TEMP_HOT, (temp - 70f) / 20f)
                }
            }
        }
    }

    internal companion object {
        private const val TAB_OBSERVATIONS = 0
        private const val TAB_FETCH_LOGS = 1
        private const val STATE_SELECTED_TAB = "selected_tab"

        @VisibleForTesting
        internal var autoRefreshDebounceMs: Long = 500L
    }
}
