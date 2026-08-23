package com.weatherwidget.ui

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
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
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.BlendTable
import com.weatherwidget.shared.actuals.BlendTableFormatter
import com.weatherwidget.shared.observations.ObservationOrigin
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.shared.observations.StaleObservationFallback
import com.weatherwidget.util.StationHistoryUrl
import com.weatherwidget.widget.GpsResampler
import com.weatherwidget.widget.StaleDisplayRefreshPolicy
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.GraphDataLoader
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

    /**
     * Passive GPS resample seam (test override point). Production wiring is the injected
     * [GpsResampler] — a cached-fix read, never an active GPS request.
     */
    internal var resampleLocation: suspend (Context, String) -> Unit = { ctx, trigger ->
        gpsResampler.resample(ctx, trigger)
    }

    /**
     * Force-refresh seam for the automatic stale-display repair (test override point). Production
     * wiring is the same call the manual refresh button makes, under a distinct reason token.
     */
    internal var forceRefreshDisplayedSource: suspend (Double, Double) -> Unit = { latitude, longitude ->
        weatherRepository.refreshCurrentTemperature(
            latitude,
            longitude,
            "Stale Observations Screen",
            source = currentSource,
            reason = "stale_observations_screen",
            forceRefresh = true,
        )
    }

    
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

    @Inject
    lateinit var hourlyForecastDao: com.weatherwidget.data.local.HourlyForecastDao

    @Inject
    lateinit var gpsResampler: GpsResampler

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ObservationAdapter
    private var currentSource: WeatherSource = WeatherSource.NWS
    private var appWidgetId: Int = android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    private var loadObservationsJob: Job? = null
    private var loadFetchLogsJob: Job? = null
    private var loadBlendTableJob: Job? = null
    private var selectedTab: Int = TAB_OBSERVATIONS
    private var widgetContentChanged = false

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
        currentSource = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.getCurrentDisplaySource(appWidgetId)
        } else {
            effectiveVisibleSources().firstOrNull() ?: WeatherSource.NWS
        }
        updateApiButton()

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).setOnClickListener { finish() }
        findViewById<Button>(R.id.close_button).setOnClickListener { finish() }

        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, PREFS_NAME)
        val defaultTab = prefs.getInt(PREF_KEY_LAST_TAB, TAB_OBSERVATIONS)
        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, defaultTab) ?: defaultTab
        widgetContentChanged = savedInstanceState?.getBoolean(STATE_WIDGET_CONTENT_CHANGED, false) ?: false
        findViewById<View>(R.id.blend_tab).setOnClickListener {
            showTab(TAB_BLEND)
        }
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
        outState.putBoolean(STATE_WIDGET_CONTENT_CHANGED, widgetContentChanged)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tab: Int) {
        selectedTab = tab
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, PREFS_NAME)
        prefs.edit().putInt(PREF_KEY_LAST_TAB, tab).apply()

        findViewById<View>(R.id.observations_content).visibility =
            if (tab == TAB_OBSERVATIONS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.blend_content).visibility =
            if (tab == TAB_BLEND) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fetch_logs_content).visibility =
            if (tab == TAB_FETCH_LOGS) View.VISIBLE else View.GONE

        updateTab(
            tabView = findViewById(R.id.observations_tab),
            indicatorView = findViewById(R.id.observations_tab_indicator),
            selected = tab == TAB_OBSERVATIONS,
        )
        updateTab(
            tabView = findViewById(R.id.blend_tab),
            indicatorView = findViewById(R.id.blend_tab_indicator),
            selected = tab == TAB_BLEND,
        )
        updateTab(
            tabView = findViewById(R.id.fetch_logs_tab),
            indicatorView = findViewById(R.id.fetch_logs_tab_indicator),
            selected = tab == TAB_FETCH_LOGS,
        )

        // Runs the blend, so only pay for it while the tab is on screen.
        if (tab == TAB_BLEND) loadBlendTable()
    }

    /**
     * Fills the Blend tab: for every recent blended point, which station contributed what.
     *
     * **Inputs must mirror the graph's.** The table exists to explain the observed dot, so it is
     * computed by the same shared function the widget renders from
     * ([ActualTemperatureSeriesBuilder.blendObservationSeries]) over the same location-scoped
     * observations, the same source, and the same personal-station weight. A table built from
     * different inputs would show numbers the dot never had.
     */
    @VisibleForTesting
    internal fun loadBlendTable() {
        loadBlendTableJob?.cancel()
        loadBlendTableJob = lifecycleScope.launch(ioDispatcher) {
            var error: String? = null
            val tables: List<BlendTable> = try {
                val location = resolveLocation()
                if (location == null) {
                    error = getString(R.string.blend_no_location)
                    emptyList()
                } else {
                    val (lat, lon) = location
                    val now = System.currentTimeMillis()
                    val sinceMs = now - (24 * 60 * 60 * 1000)
                    val observations = LocationMatch.selectNearestSite(
                        observationRepository.getRecentObservationsNear(sinceMs, lat, lon),
                        lat,
                        lon,
                        { it.locationLat },
                        { it.locationLon },
                    ).map { it.toReading() }
                    // unifyToNearestSite, not the raw rows: the proximity box spans ~7 miles, so a
                    // neighbouring site's fragment would otherwise feed forecasts the graph never used
                    // and the table would stop matching the dot it exists to explain.
                    val hourly = GraphDataLoader.unifyToNearestSite(
                        hourlyForecastDao.getHourlyForecastsBySource(
                            sinceMs,
                            now + (48 * 60 * 60 * 1000),
                            lat,
                            lon,
                            currentSource.id,
                        ),
                        lat,
                        lon,
                    ).map { it.toHourlyForecast() }

                    val result = ActualTemperatureSeriesBuilder.blendObservationSeries(
                        observations = observations,
                        hourlyForecasts = hourly,
                        displaySourceId = currentSource.id,
                        userLat = lat,
                        userLon = lon,
                        startMs = sinceMs,
                        endMs = now,
                        personalStationWeight = widgetStateManager.getPersonalStationWeight(),
                        captureBreakdowns = BLEND_TABLE_POINTS,
                    )
                    BlendTableFormatter.format(result.breakdowns, widgetStateManager.useCelsius())
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadBlendTable failed", e)
                error = getString(R.string.blend_load_failed, e.message ?: e::class.java.simpleName)
                emptyList()
            }
            withContext(Dispatchers.Main) { renderBlendTable(tables.firstOrNull(), error) }
        }
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

    /**
     * Resolved on **every** load, never cached in a field.
     *
     * It used to be read once in `onCreate`. An activity created during a GPS excursion then held
     * the abandoned coordinate for its whole lifetime, and since every read here is location-scoped,
     * all three consumers went wrong together: the stations list and the Blend tab scoped to a site
     * the user had left, and the refresh button *fetched* for it. The screen showed "No recent
     * observations found for NWS" through eleven automatic reloads while five NWS stations sat in
     * the DB, and only a back-and-relaunch cleared it (2026-08-15, Samsung Fold — see
     * `plans/260815-observations-empty-list-stale-location-scope-opus.md`).
     *
     * Note that `onResume` would not have been enough: those reloads were driven by the DB flow in
     * [observeCurrentObservationUpdates] with the activity foreground and resumed throughout.
     *
     * Both steps are cheap — a `SharedPreferences` read, then at most one indexed row — so there is
     * no reason to hold the result beyond the load that asked for it.
     */
    private suspend fun resolveLocation(): Pair<Double, Double>? =
        (
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                widgetStateManager.getWidgetLocation(appWidgetId)
            } else {
                null
            }
            ) ?: weatherRepository.getLatestLocation()

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
            val location = withContext(ioDispatcher) { resolveLocation() }

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
            widgetContentChanged = true
            
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
            widgetContentChanged = true
        }
        
        updateApiButton()
        loadObservations()
        loadFetchLogs()
    }

    private fun effectiveVisibleSources(): List<WeatherSource> {
        return widgetStateManager.getVisibleSourcesOrder()
    }

    override fun onDestroy() {
        super.onDestroy()
        // A simple inspection does not invalidate the widget. Repaint only the originating widget
        // after this activity actually changed its source or fetched fresh observations.
        if (WeatherObservationsSupport.shouldRefreshWidgetOnExit(appWidgetId, widgetContentChanged)) {
            val refreshIntent = Intent(this, com.weatherwidget.widget.WidgetActionReceiver::class.java).apply {
                action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
            }
            sendBroadcast(refreshIntent)
        }
    }


    /**
     * Paints the Blend tab: one table for the CURRENT blended point.
     *
     * Built as real per-column views rather than preformatted monospace text — a proportional font at
     * this size cannot hold fixed-width columns in alignment, and real rows also restore the
     * per-station tap target that opens the NWS time-series page (same affordance as the Observations
     * tab).
     */
    private fun renderBlendTable(table: BlendTable?, error: String?) {
        val summary = findViewById<TextView>(R.id.blend_summary)
        val rows = findViewById<TableLayout>(R.id.blend_rows)
        val legend = findViewById<TextView>(R.id.blend_legend)
        rows.removeAllViews()
        // TableLayout is a LinearLayout, so its own divider machinery draws the rules between rows —
        // no spacer views to keep in sync with the row list.
        //
        // GradientDrawable.setSize, NOT ColorDrawable: LinearLayout reads the divider height from the
        // drawable's INTRINSIC height, and ColorDrawable reports -1 (setBounds does not change that),
        // so a ColorDrawable divider draws at zero height and is silently invisible.
        val rulePx = (BLEND_RULE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        rows.dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(BLEND_RULE_COLOR)
            setSize(0, rulePx)
        }
        rows.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE or LinearLayout.SHOW_DIVIDER_BEGINNING

        if (table == null) {
            summary.text = error ?: getString(R.string.blend_no_points)
            legend.text = ""
            return
        }

        summary.text = "${table.timeLabel}  \u2192  ${table.blendedLabel}   ${table.stationCount} stations"
        summary.setTextColor(BLEND_COLOR_PRIMARY)

        rows.addView(
            blendRowView(
                cells = BlendTableFormatter.COLUMN_HEADERS,
                colors = List(BlendTableFormatter.COLUMN_HEADERS.size) { BLEND_COLOR_SECONDARY },
                textSizeSp = BLEND_HEADER_SP,
                bold = true,
            ),
        )

        table.rows.forEach { row ->
            val historyUrl = StationHistoryUrl.forStation(currentSource.id, row.station)
            val valueColor = if (row.isExtrapolated) BLEND_COLOR_DERIVED else BLEND_COLOR_PRIMARY
            val view = blendRowView(
                cells = listOf(
                    row.station,
                    row.type,
                    row.km,
                    row.lastRead,
                    row.age,
                    row.raw,
                    row.valueFedToBlend,
                    row.weightShare,
                ),
                colors = listOf(
                    if (historyUrl != null) BLEND_COLOR_LINK else BLEND_COLOR_PRIMARY,
                    if (row.type == "O") BLEND_COLOR_OFFICIAL else BLEND_COLOR_PERSONAL,
                    BLEND_COLOR_SECONDARY,
                    BLEND_COLOR_DERIVED,
                    BLEND_COLOR_SECONDARY,
                    BLEND_COLOR_PRIMARY,
                    valueColor,
                    BLEND_COLOR_SECONDARY,
                ),
                textSizeSp = BLEND_DATA_SP,
                bold = false,
            )
            if (historyUrl != null) {
                view.isClickable = true
                view.setOnClickListener { openStationHistory(historyUrl) }
            }
            rows.addView(view)
        }

        legend.text = BlendTableFormatter.LEGEND.joinToString("\n")
    }

    /** One [TableRow] of right-sized, right-coloured cells. Column 2/4/6 are numeric, so right-align. */
    private fun blendRowView(
        cells: List<String>,
        colors: List<Int>,
        textSizeSp: Float,
        bold: Boolean,
    ): TableRow {
        val row = TableRow(this)
        cells.forEachIndexed { index, text ->
            val cell = TextView(this)
            cell.text = text
            cell.setTextColor(colors[index])
            cell.textSize = textSizeSp
            cell.maxLines = 1
            if (bold) cell.setTypeface(cell.typeface, android.graphics.Typeface.BOLD)
            cell.gravity = if (index in BLEND_NUMERIC_COLUMNS) Gravity.END else Gravity.START
            // Generous vertical padding: the rows are the thing being compared line-for-line against
            // the Observations tab, and tightly packed large text is hard to track across seven columns.
            cell.setPadding(0, BLEND_ROW_PAD_PX, BLEND_CELL_GAP_PX, BLEND_ROW_PAD_PX)
            row.addView(cell)
        }
        return row
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
                val location = resolveLocation()
                val diagnostic = StringBuilder()

                fun isForCurrentSource(row: ObservationEntity) =
                    WeatherObservationsSupport.matchesObservationSource(row.stationId, currentSource)

                suspend fun stationsSince(sinceMs: Long): List<ObservationEntity> {
                    val rows = if (location != null) {
                        // The proximity box is ~7 miles wide, so it also admits rows fetched under a
                        // *nearby* site the device visited earlier (Los Gatos stations lingering under
                        // a Mountain View fix). Nothing refreshes those, but they stay in the 24h
                        // window long enough to show up as extra stations beyond the MAX_RETRIES
                        // stations actually polled, so collapse the box to the current site.
                        //
                        // ...but not to a site that has nothing for the displayed source: an excursion
                        // fragment holding only `<SOURCE>_MAIN` backfill rows would otherwise win on
                        // distance and empty the list outright. See LocationMatch.selectNearestSiteWith.
                        val box = observationRepository.getRecentObservationsNear(sinceMs, location.first, location.second)
                        val site = LocationMatch.selectNearestSiteWith(
                            box,
                            location.first,
                            location.second,
                            { it.locationLat },
                            { it.locationLon },
                            ::isForCurrentSource,
                        )
                        // Permanent, and load-bearing: `loc` alone would have identified the
                        // 2026-08-15 stale-location incident in a single grep of `adb logcat`.
                        diagnostic
                            .append(" loc=${location.first},${location.second}")
                            .append(" boxRows=${box.size}")
                            .append(" sites=${box.map { "${it.locationLat}/${it.locationLon}" }.distinct()}")
                            .append(" siteRows=${site.size}")
                        site
                    } else {
                        observationRepository.getRecentObservations(sinceMs).also {
                            diagnostic.append(" loc=none boxRows=${it.size}")
                        }
                    }
                    return rows
                        .filter(::isForCurrentSource)
                        .groupBy { it.stationId }
                        .map { it.value.first() }
                        .sortedBy { it.distanceKm }
                }

                val nowMs = System.currentTimeMillis()
                val recent = stationsSince(nowMs - StaleObservationFallback.RECENT_WINDOW_MS)
                // Reaching further back in the DB before giving up: free, and the age of what IS
                // stored is the diagnostic the screen exists to surface. Never a fetch trigger.
                val fallback = StaleObservationFallback.resolve(
                    recent = recent,
                    older = if (recent.isEmpty()) stationsSince(0L) else emptyList(),
                    nowMs = nowMs,
                    timestampOf = { it.timestamp },
                )
                val staleAgeMs = fallback.ageMs

                val observations = if (currentSource == WeatherSource.NWS) {
                    fallback.rows
                } else {
                    // For other sources, show POIs if they exist, or fallback to the latest single reading
                    val pois = fallback.rows

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
                    Log.d(
                        TAG,
                        "loadObservations: currentSource=${currentSource.id}$diagnostic" +
                            " staleAgeMs=${staleAgeMs ?: "none"} items=${observations.map { it.stationId }}",
                    )
                    adapter.submitList(observations)
                    val subtitleView = findViewById<TextView>(R.id.subtitle)
                    if (observations.isEmpty()) {
                        subtitleView.text = getString(R.string.obs_subtitle_none_found, currentSource.displayName)
                    } else if (staleAgeMs != null) {
                        // Everything shown is older than the 24h window; say how old rather than
                        // letting it pass for current.
                        subtitleView.text = getString(
                            R.string.obs_subtitle_stale,
                            currentSource.displayName,
                            StaleObservationFallback.formatAge(staleAgeMs),
                        )
                    } else if (currentSource == WeatherSource.NWS) {
                        subtitleView.text = getString(R.string.obs_subtitle_nearby_stations)
                    } else {
                        subtitleView.text = getString(R.string.obs_subtitle_latest_reading, currentSource.displayName)
                    }

                    maybeAutoRefreshStaleDisplay(location, observations)
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

    /**
     * Phase C of plans/260821-observations-stale-site-autorefresh.md: when what this screen is
     * showing is stale, repair it now instead of waiting for a periodic loop to happen to write
     * our key (the 2026-08-21 incident: 78 minutes of "Fetched 7:17 PM" while plugged in).
     *
     * Runs on every load's Main-thread render, but [StaleDisplayRefreshPolicy]'s debounce keeps
     * the actual resample+fetch to at most one per [StaleDisplayRefreshPolicy.TRIGGER_DEBOUNCE_MS]
     * even though the insert-flow observer reloads on every DB change.
     */
    private fun maybeAutoRefreshStaleDisplay(
        location: Pair<Double, Double>?,
        observations: List<ObservationEntity>,
    ) {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(this, PREFS_NAME)
        val decision = StaleDisplayRefreshPolicy.evaluate(
            nowMs = System.currentTimeMillis(),
            newestFetchedMs = observations.maxOfOrNull { it.fetchedAt },
            newestReportedMs = observations.maxOfOrNull { it.timestamp },
            lastTriggerMs = prefs.getLong(KEY_LAST_STALE_AUTO_REFRESH_MS, 0L),
        )
        // SKIP_FRESH is the common case and RECENT_TRIGGER is derivable from the preceding `fired`
        // row's timestamp — persisting either would make this the noisiest tag in app_logs.
        if (decision != StaleDisplayRefreshPolicy.Decision.SKIP_FRESH &&
            decision != StaleDisplayRefreshPolicy.Decision.RECENT_TRIGGER
        ) {
            Log.d(TAG, "stale display auto-refresh decision=${decision.name} rows=${observations.size}")
            lifecycleScope.launch(ioDispatcher) {
                appLogDao.log(
                    OBS_STALE_AUTO_REFRESH_TAG,
                    "source=${currentSource.id} outcome=${decision.outcomeToken}",
                )
            }
        }
        if (decision != StaleDisplayRefreshPolicy.Decision.FIRE || location == null) return

        prefs.edit().putLong(KEY_LAST_STALE_AUTO_REFRESH_MS, System.currentTimeMillis()).apply()
        lifecycleScope.launch {
            try {
                withContext(ioDispatcher) {
                    resampleLocation(applicationContext, TRIGGER_SOURCE)
                    // Re-resolve after the resample: if it detected a move, the anchor may have
                    // just changed, and fetching under the abandoned coordinate would write
                    // another orphan fragment.
                    val refreshedLocation = resolveLocation() ?: location
                    forceRefreshDisplayedSource(refreshedLocation.first, refreshedLocation.second)
                }
                widgetContentChanged = true
                loadObservations()
                loadFetchLogs()
            } catch (e: Exception) {
                Log.e(TAG, "Stale-display auto refresh failed", e)
                withContext(ioDispatcher) {
                    appLogDao.log(
                        OBS_STALE_AUTO_REFRESH_TAG,
                        "source=${currentSource.id} outcome=failed error=${e.message ?: e::class.java.simpleName}",
                        "WARN",
                    )
                }
            }
        }
    }

    internal object WeatherObservationsSupport {
        fun shouldRefreshWidgetOnExit(
            appWidgetId: Int,
            widgetContentChanged: Boolean,
        ): Boolean =
            appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && widgetContentChanged

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

            if (!item.rawMetar.isNullOrBlank()) {
                holder.rawMetar.text = item.rawMetar
                holder.rawMetar.visibility = View.VISIBLE
            } else {
                holder.rawMetar.visibility = View.GONE
            }
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
            val rawMetar: TextView = view.findViewById(R.id.raw_metar)
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
        private const val TAB_BLEND = 0
        private const val TAB_OBSERVATIONS = 1
        private const val TAB_FETCH_LOGS = 2
        private const val STATE_SELECTED_TAB = "selected_tab"
        private const val STATE_WIDGET_CONTENT_CHANGED = "widget_content_changed"
        private const val PREF_KEY_LAST_TAB = "last_selected_obs_tab"
        private const val PREFS_NAME = "weather_widget_prefs"

        @VisibleForTesting
        internal const val KEY_LAST_STALE_AUTO_REFRESH_MS = "last_stale_obs_auto_refresh_ms"

        /** GpsResampler trigger name and app_logs outcome prefix live on this token. */
        internal const val TRIGGER_SOURCE = "observations_screen"
        private const val OBS_STALE_AUTO_REFRESH_TAG = "OBS_STALE_AUTO_REFRESH"

        /**
         * The Blend tab shows only the CURRENT blended point — the tab answers "why does the dot read
         * what it reads right now", and a scrolling backlog of past timestamps buried that.
         */
        private const val BLEND_TABLE_POINTS = 1

        private const val BLEND_DATA_SP = 20f
        private const val BLEND_HEADER_SP = 16f
        private const val BLEND_ROW_PAD_PX = 14
        private const val BLEND_CELL_GAP_PX = 34
        private const val BLEND_RULE_DP = 1f

        /** km, age, raw and weight share read as numbers, so they right-align. */
        private val BLEND_NUMERIC_COLUMNS = BlendTableFormatter.NUMERIC_COLUMNS

        /**
         * Hairline rules between rows. Barely-there on purpose: the table is scanned column-wise (raw
         * vs fed-to-blend), so the rules only need to stop the eye drifting a row — anything stronger
         * competes with the amber/white value colouring that carries the meaning.
         */
        private val BLEND_RULE_COLOR = Color.parseColor("#2A2A2E")

        private val BLEND_COLOR_PRIMARY = Color.parseColor("#FFFFFF")
        private val BLEND_COLOR_SECONDARY = Color.parseColor("#AAAAAA")
        private val BLEND_COLOR_LINK = Color.parseColor("#4FC3F7")
        private val BLEND_COLOR_OFFICIAL = Color.parseColor("#2BFF88")
        private val BLEND_COLOR_PERSONAL = Color.parseColor("#B0B0B8")

        /** Amber: this number was derived from the forecast, not measured. */
        private val BLEND_COLOR_DERIVED = Color.parseColor("#E8A24E")
        @VisibleForTesting
        internal var autoRefreshDebounceMs: Long = 500L
    }
}
