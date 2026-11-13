package com.weatherwidget.ui

import com.weatherwidget.shared.util.WeatherSourceOrdering
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import com.weatherwidget.shared.graph.DaySourceComparison
import com.weatherwidget.shared.graph.ForecastEvolutionCutoff
import com.weatherwidget.shared.graph.ForecastHistoryHeader
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.weatherwidget.R
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.remote.QuotaScope
import com.weatherwidget.shared.util.RefreshOutcome
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.ButtonMode
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.GraphMode
import com.weatherwidget.widget.handlers.GraphDataLoader
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
import com.weatherwidget.shared.util.UserRefreshThrottle
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.widget.EvolutionPoint
import com.weatherwidget.widget.ForecastEvolutionRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetActionReceiver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetWorkScheduler
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.DayClickHelper
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

@AndroidEntryPoint
class ForecastHistoryActivity : AppCompatActivity() {
    @Inject
    lateinit var forecastDao: ForecastDao

    @Inject
    lateinit var userRefreshRunner: com.weatherwidget.shared.util.UserRefreshRunner

    @Inject
    lateinit var dailyHistoryDao: DailyHistoryDao

    @Inject
    lateinit var hourlyForecastDao: com.weatherwidget.data.local.HourlyForecastDao

    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    @Inject
    lateinit var weatherRepository: WeatherRepository

    @Inject
    lateinit var appLogDao: AppLogDao

    /** A refresh is running; further taps are logged and ignored rather than swallowed unseen. */
    private var refreshInFlight = false

    companion object {
        /** Repaints the widget that opened this screen; application context only, so a detached fetch can call it. */
        private fun sendUiOnlyWidgetRefresh(appContext: android.content.Context, widgetId: Int) {
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            appContext.sendBroadcast(
                Intent(appContext, WidgetActionReceiver::class.java).apply {
                    action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
                },
            )
        }

        const val EXTRA_TARGET_DATE = "target_date"
        const val EXTRA_LAT = "latitude"
        const val EXTRA_LON = "longitude"
        const val EXTRA_SOURCE = "source"
        private const val TAG = "ForecastHistoryActivity"

        /**
         * The localized [RefreshOutcome.text]: "Google refreshed · Hourly forecast quota used · resets
         * 12 AM", from existing strings only (plans/261016-history-refresh-outcome-shared-desktop-parity.md).
         */
        fun refreshOutcomeText(context: android.content.Context, outcome: RefreshOutcome, sourceName: String): String {
            if (outcome is RefreshOutcome.Failed) {
                return RefreshOutcome.failedText(
                    outcome,
                    sourceName,
                    couldNotRefresh = { context.getString(R.string.forecast_history_refresh_failed, it) },
                    errorCodeText = { com.weatherwidget.widget.GraphFailureWatermarkRenderer.localizedErrorCodeText(context, it) },
                    retrying = {
                        context.getString(
                            R.string.refresh_retrying_at,
                            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)),
                        )
                    },
                )
            }
            val refreshed = context.getString(R.string.obs_refreshed_source, sourceName)
            val refused = when (outcome) {
                is RefreshOutcome.Refused -> {
                    val reset = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(outcome.untilMs))
                    context.getString(
                        when (outcome.scope) {
                            QuotaScope.SOURCE -> R.string.watermark_quota_daily
                            QuotaScope.HOURLY_FORECAST -> R.string.watermark_quota_hourly_forecast
                            QuotaScope.DAILY_FORECAST -> R.string.watermark_quota_daily_forecast
                        },
                        reset,
                    )
                }
                RefreshOutcome.HourlyIncomplete -> context.getString(R.string.watermark_rate_limited_partial)
                else -> return refreshed
            }
            return "$refreshed · $refused"
        }

        fun hasRequiredHistoryExtras(
            targetDate: String?,
            hasLatExtra: Boolean,
            hasLonExtra: Boolean,
        ): Boolean = targetDate != null && hasLatExtra && hasLonExtra

        fun resolveForecastFetchTime(
            sourceSuccessMs: Long,
            lastFullFetchMs: Long,
        ): Long = if (sourceSuccessMs > 0L) sourceSuccessMs else lastFullFetchMs

        internal fun selectLatestCompleteActualFromForecasts(forecasts: List<ForecastEntity>): ForecastEntity? =
            forecasts
                .asSequence()
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
    }

    private var graphMode = GraphMode.EVOLUTION
    private var cachedSnapshots: List<ForecastEntity> = emptyList()
    private var cachedApiActualRow: DailyHistoryEntity? = null
    private var cachedLocationActuals = ForecastHistoryViewLogic.LocationActuals.NONE
    private var cachedDate: LocalDate? = null
    private var cachedRequestedSource: WeatherSource? = null
    /** The last "All sources" rows, re-sorted in place on a header tap without reloading. */
    private var sourceCompareRows: Pair<LocalDate, List<DaySourceComparison.Row>>? = null
    private lateinit var targetDate: String
    private var targetLat: Double = 0.0
    private var targetLon: Double = 0.0
    private lateinit var targetLocalDate: LocalDate
    private lateinit var widgetStateManager: WidgetStateManager

    /** The stored-data version [loadData] last read at, so a return from Settings → Refresh data can reload. */
    private val dataSeen = com.weatherwidget.shared.util.StoredDataSeen()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_history)

        val intentTargetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val requestedSource = ForecastHistoryViewLogic.normalizeSource(intent.getStringExtra(EXTRA_SOURCE))

        if (!hasRequiredHistoryExtras(intentTargetDate, intent.hasExtra(EXTRA_LAT), intent.hasExtra(EXTRA_LON))) {
            Log.e(TAG, "Missing required extras")
            finish()
            return
        }
        widgetStateManager = WidgetStateManager(this)
        targetDate = checkNotNull(intentTargetDate)
        targetLat = lat
        targetLon = lon
        targetLocalDate = LocalDate.parse(targetDate)

        Log.d(TAG, "Loading forecast history for $targetDate at $lat, $lon (source=$requestedSource)")

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.title).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.prev_day_button).setOnClickListener {
            navigateToDay(targetLocalDate.minusDays(1))
        }
        findViewById<ImageButton>(R.id.next_day_button).setOnClickListener {
            navigateToDay(targetLocalDate.plusDays(1))
        }
        findViewById<View>(R.id.api_source_button).setOnClickListener {
            cycleApiSource()
        }
        findViewById<View>(R.id.refresh_button).setOnClickListener {
            refreshViewedSource()
        }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        applyDataScreenStyle()
        findViewById<Button>(R.id.detailed_stats_button).setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }
        findViewById<Button>(R.id.months_view_button).setOnClickListener {
            startActivity(MonthsViewActivity.intent(this, cachedRequestedSource, targetLat, targetLon))
        }

        val visibleSources = effectiveVisibleSources()
        cachedRequestedSource = requestedSource?.takeIf { it in visibleSources } ?: visibleSources.firstOrNull()
        updateApiSourceButton()

        val graphModeButton = findViewById<Button>(R.id.graph_mode_button)
        graphModeButton.setOnClickListener {
            val date = cachedDate
            val hasActualValues = cachedApiActualRow?.apiHighTemp != null && cachedApiActualRow?.apiLowTemp != null
            val showTemperatureButton = ForecastHistoryViewLogic.shouldShowTemperatureButton(date, hasActualValues)
            if (ForecastHistoryViewLogic.shouldLaunchTemperature(date != null, showTemperatureButton)) {
                launchWidgetTemperatureMode(date!!)
                return@setOnClickListener
            }

            graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
            updateModeUi()
            if (cachedDate != null) {
                displayData(
                    cachedSnapshots, cachedApiActualRow, cachedLocationActuals, cachedDate!!, cachedRequestedSource,
                )
            }
        }
        updateModeUi()

        updateTitle()
        updatePrevButtonEnabled()

        loadData(
            targetDate = targetDate,
            lat = targetLat,
            lon = targetLon,
            date = targetLocalDate,
            requestedSource = checkNotNull(cachedRequestedSource),
        )
        loadAccuracySummary(targetLat, targetLon)
        if (savedInstanceState == null) {
            val source = checkNotNull(cachedRequestedSource)
            lifecycleScope.launch {
                com.weatherwidget.widget.ViewingForecastRefresher.onScreenOpen(this@ForecastHistoryActivity, source, targetLat, targetLon, "history_open") {
                    fetchViewedSource(source, "history_open", userRefresh = false)
                }
            }
        }
    }

    /**
     * Back from another screen: a fetch that ran meanwhile (Settings → Refresh data starts on top of this
     * screen) stored rows this one never read, so its Age column and graphs showed the pre-refresh data.
     * Reloads only when stored data changed since the last load (plans/261113-android-data-screens-reload-after-settings-refresh.md).
     */
    override fun onResume() {
        super.onResume()
        if (!::targetLocalDate.isInitialized || !dataSeen.changedSinceLoad()) return
        lifecycleScope.launch {
            appLogDao.log(
                com.weatherwidget.shared.util.StoredDataSeen.LOG_TAG,
                com.weatherwidget.shared.util.StoredDataSeen.logMessage("history"),
                "INFO",
            )
        }
        loadData(targetDate, targetLat, targetLon, targetLocalDate, cachedRequestedSource)
        loadAccuracySummary(targetLat, targetLon)
    }

    private fun navigateToDay(newDate: LocalDate) {
        targetLocalDate = newDate
        targetDate = newDate.toString()
        updateTitle()
        updatePrevButtonEnabled()
        loadData(
            targetDate = targetDate,
            lat = targetLat,
            lon = targetLon,
            date = targetLocalDate,
            requestedSource = cachedRequestedSource,
        )
    }

    private fun updateTitle() {
        val dateText = ForecastHistoryHeader.dateLabel(targetLocalDate)
        findViewById<TextView>(R.id.title).text =
            getString(R.string.forecast_history_title_format, dateText)
    }

    private fun updatePrevButtonEnabled() {
        val prevButton = findViewById<ImageButton>(R.id.prev_day_button)
        val canGoBack = ForecastHistoryHeader.canGoBack(targetLocalDate)
        prevButton.isEnabled = canGoBack
        prevButton.alpha = if (canGoBack) 1.0f else 0.3f
    }

    private fun loadData(
        targetDate: String,
        lat: Double,
        lon: Double,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        val targetDateEpoch = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        dataSeen.markLoaded() // before any read: a write landing mid-load must still read as a change
        loadSourceComparison(date, lat, lon)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allSnapshots = forecastDao.getForecastEvolution(targetDateEpoch, lat, lon)
                val snapshots =
                    if (requestedSource != null) {
                        allSnapshots.filter { it.source == requestedSource.id }
                    } else {
                        allSnapshots
                    }
                Log.d(TAG, "Found ${snapshots.size} snapshots for $targetDate")

                val isPastDate = date.isBefore(LocalDate.now())
                val apiActualRow: DailyHistoryEntity? = if (isPastDate && requestedSource != null) {
                    resolveApiActual(targetDateEpoch, lat, lon, requestedSource)
                } else {
                    null
                }

                val appActuals = dailyHistoryDao.getExtremesInRange(targetDateEpoch, targetDateEpoch, lat, lon)
                val zone = java.time.ZoneId.systemDefault()
                val todayHourly =
                    if (requestedSource != null && ForecastHistoryViewLogic.needsTodayHourly(date)) {
                        // Raw box read collapsed to one site: a stale GPS-fragment's hours must not
                        // drive the settle look-ahead (HourlyProximityQueryAllowlistTest).
                        GraphDataLoader.unifyToNearestSite(
                            hourlyForecastDao.getHourlyForecastsForSources(
                                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                                lat, lon, listOf(requestedSource.id, WeatherSource.GENERIC_GAP.id),
                            ),
                            lat, lon,
                        )
                    } else {
                        emptyList()
                    }
                val locationActuals = ForecastHistoryViewLogic.locationActuals(
                    date = date,
                    now = java.time.LocalDateTime.now(zone),
                    zone = zone,
                    lat = lat,
                    lon = lon,
                    sourceId = requestedSource?.id,
                    rows = appActuals.map {
                        ForecastHistoryViewLogic.LocationActualRow(
                            it.source, it.locationLat, it.locationLon,
                            it.computedHighTemp, it.computedLowTemp, it.computedHighAt, it.computedLowAt,
                        )
                    },
                    todayHourly = todayHourly,
                    epochMs = { it.dateTime },
                    temperature = { it.temperature },
                )

                withContext(Dispatchers.Main) {
                    displayData(snapshots, apiActualRow, locationActuals, date, requestedSource)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading forecast history", e)
            }
        }
    }

    /**
     * Finds the API-reported actual for [requestedSource] by looking in daily_history rows
     * that have non-null [DailyHistoryEntity.apiHighTemp]/[DailyHistoryEntity.apiLowTemp].
     *
     * Multiple same-site fragments can exist for one source/date (legacy un-quantized location
     * keys alongside the quantized grid). [ApiActualPicker.pickNearestComplete] picks the
     * NEAREST fragment with complete api values — not just the nearest fragment — so a partial
     * row (e.g. apiLowTemp null after a truncated NWS gridpoint response) never shadows a
     * complete one sitting a few metres away. Mirrors the desktop pick in ForecastHistoryWindow.
     */
    private suspend fun resolveApiActual(
        targetDateEpoch: Long,
        lat: Double,
        lon: Double,
        requestedSource: WeatherSource,
    ): DailyHistoryEntity? {
        val allRows = dailyHistoryDao.getExtremesInRange(targetDateEpoch, targetDateEpoch, lat, lon)

        val row = com.weatherwidget.shared.actuals.ApiActualPicker.pickNearestComplete(
            rows = allRows,
            lat = lat,
            lon = lon,
            sourceId = requestedSource.id,
            source = { it.source },
            locationLat = { it.locationLat },
            locationLon = { it.locationLon },
            apiHigh = { it.apiHighTemp },
            apiLow = { it.apiLowTemp },
        )
        if (row != null) {
            Log.d(TAG, "API actual for ${requestedSource.id}: apiHigh=${row.apiHighTemp} apiLow=${row.apiLowTemp}")
            return row
        }

        val sourceRows = allRows.filter { it.source == requestedSource.id }
        Log.d(
            TAG,
            "No API actual available for ${requestedSource.id} " +
                "(fragments=${sourceRows.size}, " +
                "partial=${sourceRows.count { it.apiHighTemp != null || it.apiLowTemp != null }})",
        )
        return null
    }

    private fun displayData(
        snapshots: List<ForecastEntity>,
        apiActualRow: DailyHistoryEntity?,
        locationActuals: ForecastHistoryViewLogic.LocationActuals,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        cachedSnapshots = snapshots
        cachedApiActualRow = apiActualRow
        cachedLocationActuals = locationActuals
        cachedDate = date
        cachedRequestedSource = requestedSource
        updateApiSourceButton()

        // Hindcasts (fetched after the extreme was reached) are not forecasts: drop them per side.
        // Today's extreme times are only the running max/min, so today uses the fixed cutoffs alone —
        // except a settled extreme, whose time is final (ForecastHistoryViewLogic.locationActuals).
        val evolutionPoints = ForecastEvolutionCutoff.points(
            rows = snapshots.map {
                ForecastEvolutionCutoff.Row(it.dateOfPrediction, it.fetchedAt, it.highTemp, it.lowTemp, it.source, it.lastConfirmedAt)
            },
            targetDate = date,
            highReachedAt = locationActuals.highReachedAt,
            lowReachedAt = locationActuals.lowReachedAt,
        )

        val snapshotSummaryView = findViewById<TextView>(R.id.snapshot_summary_text)
        // The view always shows a single selected API at a time (snapshots are pre-filtered to the
        // requested source in loadData), drawn as one forecast series in one color. The count is just
        // the snapshots for that API.
        val summaryCount = evolutionPoints.size
        val sourceLabelForSummary = requestedSource?.displayName
            ?: getString(R.string.forecast_history_api_fallback_label)
        snapshotSummaryView.text =
            getString(R.string.forecast_history_summary_single, summaryCount, sourceLabelForSummary)
        if (summaryCount == 0) {
            snapshotSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            snapshotSummaryView.setTextColor(resources.getColor(R.color.widget_text_primary, theme))
            snapshotSummaryView.setTypeface(snapshotSummaryView.typeface, android.graphics.Typeface.BOLD)
        } else {
            snapshotSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            snapshotSummaryView.setTextColor(resources.getColor(R.color.widget_text_secondary, theme))
            snapshotSummaryView.setTypeface(snapshotSummaryView.typeface, android.graphics.Typeface.NORMAL)
        }

        updateFreshnessCard()

        val isPastDate = date.isBefore(LocalDate.now())
        // Single forecast legend (one color for every API), relabeled to the selected API.
        findViewById<TextView>(R.id.legend_forecast_text).text =
            requestedSource?.shortDisplayName ?: getString(R.string.forecast_history_api_fallback_label)

        val apiHigh = if (isPastDate) apiActualRow?.apiHighTemp else null
        val apiLow = if (isPastDate) apiActualRow?.apiLowTemp else null
        val computedHighTemp = locationActuals.high
        val computedLowTemp = locationActuals.low
        val actualsDisplay = ForecastHistoryViewLogic.actualsDisplay(isPastDate, apiHigh, apiLow, locationActuals)

        findViewById<View>(R.id.legend_actual_group).visibility =
            if (actualsDisplay.showApiActualLegend) View.VISIBLE else View.GONE
        findViewById<View>(R.id.legend_app_actual_group).visibility =
            if (actualsDisplay.showLocationActualLegend) View.VISIBLE else View.GONE

        // Single forecast legend (one color for every API), relabeled to the selected API.
        findViewById<TextView>(R.id.legend_forecast_text).text =
            requestedSource?.shortDisplayName ?: getString(R.string.forecast_history_api_fallback_label)

        val actualsLegendCard = findViewById<View>(R.id.actuals_legend_card)
        if (actualsDisplay.showFooterCard) {
            actualsLegendCard.visibility = View.VISIBLE

            val apiActualGroup = findViewById<View>(R.id.footer_api_actual_group)
            val apiActualText = findViewById<TextView>(R.id.footer_api_actual_text)
            val apiFooter = actualsDisplay.apiFooter
            if (apiFooter != null) {
                apiActualGroup.visibility = View.VISIBLE
                val sourceLabel = requestedSource?.displayName ?: getString(R.string.forecast_history_api_fallback_label)
                // Provenance: a station value derived from our retained observations, because the
                // endpoint could no longer serve that day, is not the same claim as a live pull.
                val provenance =
                    if (DailyActualsSource.fromStored(apiActualRow?.actualsSource) ==
                        DailyActualsSource.CACHED_OBSERVATIONS
                    ) {
                        " (" + getString(R.string.forecast_history_actual_from_cache) + ")"
                    } else {
                        ""
                    }
                apiActualText.text = getString(
                    R.string.forecast_history_api_actual,
                    sourceLabel,
                    formatTemp(apiFooter.high),
                    formatTemp(apiFooter.low),
                ) + provenance
            } else {
                apiActualGroup.visibility = View.GONE
            }

            val locationActualGroup = findViewById<View>(R.id.footer_location_actual_group)
            val locationActualText = findViewById<TextView>(R.id.footer_location_actual_text)
            val locationFooter = actualsDisplay.locationFooter
            if (locationFooter != null) {
                locationActualGroup.visibility = View.VISIBLE
                locationActualText.text = getString(
                    R.string.forecast_history_location_actual,
                    formatTemp(locationFooter.high),
                    formatTemp(locationFooter.low),
                )
            } else {
                locationActualGroup.visibility = View.GONE
            }
        } else {
            actualsLegendCard.visibility = View.GONE
        }

        val highGraphView = findViewById<ImageView>(R.id.high_temp_graph)
        val lowGraphView = findViewById<ImageView>(R.id.low_temp_graph)
        val highCard = findViewById<View>(R.id.high_graph_card)
        val lowCard = findViewById<View>(R.id.low_graph_card)
        val highTitle = findViewById<TextView>(R.id.high_graph_title)
        val lowTitle = findViewById<TextView>(R.id.low_graph_title)
        val noDataTextView = findViewById<TextView>(R.id.no_data_text)
        val isErrorMode = graphMode == GraphMode.ERROR

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels - dpToPx(44)
        val height = dpToPx(220)

        highTitle.text = if (isErrorMode) getString(R.string.forecast_error_high_title) else getString(R.string.forecast_evolution_high_title)
        lowTitle.text = if (isErrorMode) getString(R.string.forecast_error_low_title) else getString(R.string.forecast_evolution_low_title)

        if (evolutionPoints.isNotEmpty()) {
            if (isErrorMode && !actualsDisplay.errorGraphAvailable) {
                noDataTextView.text = getString(R.string.forecast_error_requires_actuals)
                noDataTextView.visibility = View.VISIBLE
                highCard.visibility = View.GONE
                lowCard.visibility = View.GONE
                return
            }

            noDataTextView.visibility = View.GONE
            highCard.visibility = View.VISIBLE
            lowCard.visibility = View.VISIBLE

            val useCelsius = widgetStateManager.useCelsius()
            fun render(actual: Float?, appActual: Float?, isHigh: Boolean) =
                if (isErrorMode) {
                    if (isHigh) ForecastEvolutionRenderer.renderHighErrorGraph(
                        this, evolutionPoints, actual, appActual, width, height, useCelsius = useCelsius,
                    ) else ForecastEvolutionRenderer.renderLowErrorGraph(
                        this, evolutionPoints, actual, appActual, width, height, useCelsius = useCelsius,
                    )
                } else {
                    if (isHigh) ForecastEvolutionRenderer.renderHighGraph(
                        this, evolutionPoints, actual, appActual, width, height, useCelsius = useCelsius,
                    ) else ForecastEvolutionRenderer.renderLowGraph(
                        this, evolutionPoints, actual, appActual, width, height, useCelsius = useCelsius,
                    )
                }

            highGraphView.setImageBitmap(render(apiHigh, computedHighTemp, isHigh = true))
            lowGraphView.setImageBitmap(render(apiLow, computedLowTemp, isHigh = false))
        } else {
            val sourceLabel = requestedSource?.displayName ?: getString(R.string.forecast_history_no_data_fallback_source)
            noDataTextView.text = getString(R.string.forecast_history_no_data_for_source, sourceLabel)
            noDataTextView.visibility = View.VISIBLE
            highCard.visibility = View.GONE
            lowCard.visibility = View.GONE
        }
        updateModeUi()
    }

    private fun formatTemp(value: Float): String {
        return com.weatherwidget.shared.util.TempUtils.formatTemp(value, widgetStateManager.useCelsius()) ?: ""
    }

    /**
     * Recomputes daily_history from already-stored observations before falling back to a
     * background refresh for dates that still have no recoverable actuals.
     */
    private suspend fun backfillDailyExtremesIfNeeded(lat: Double, lon: Double) {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(2)
        val startEpoch = startDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, startDate, endDate, force = true)
        val existingHistory = dailyHistoryDao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
        val actualsSources = widgetStateManager.getVisibleSourcesOrder().filter { it.supportsTemperatureActuals }

        for (source in actualsSources) {
            val existingDates = existingHistory.filter { it.source == source.id }.map { it.date }.toSet()
            val forecastDates =
                forecastDao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source.id)
                    .map { it.targetDate }
                    .toSet()

            val missingDates = forecastDates - existingDates
            if (missingDates.isNotEmpty()) {
                Log.d(TAG, "Still missing ${source.id} daily_history after local recompute for ${missingDates.size} date(s): $missingDates")
                // Opening history surfaces gaps in stored actuals; trigger a widget refresh so the
                // background fetch backfills them before the user looks at another day.
                WidgetWorkScheduler.enqueueRedundantImmediateSync(
                    context = this,
                    forceRefresh = true,
                    reason = "history_missing_extremes_${source.id}",
                )
                break
            }
        }
    }

    private fun loadAccuracySummary(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                backfillDailyExtremesIfNeeded(lat, lon)
                val comparison = accuracyCalculator.calculateComparison(lat, lon, 30)
                val enabledSources = widgetStateManager.getVisibleSourcesOrder().toSet()

                val sourcesToShow = WeatherSourceOrdering.ALL_CONFIGURABLE
                    .filter { it in enabledSources }
                    .map { it to comparison.statsFor(it) }
                val hasAnyData = sourcesToShow.any { (_, stats) -> (stats?.totalForecasts ?: 0) > 0 }

                val summary =
                    if (!hasAnyData) {
                        getString(R.string.forecast_history_no_history_yet)
                    } else {
                        buildString {

                            val useCelsius = widgetStateManager.useCelsius()
                            sourcesToShow.forEachIndexed { index, (source, stats) ->
                                if (stats != null && stats.totalForecasts > 0) {
                                    append("${source.displayName}\n")
                                    val highErr = com.weatherwidget.shared.util.TempUtils.displayDelta(stats.avgHighError, useCelsius)
                                    val lowErr = com.weatherwidget.shared.util.TempUtils.displayDelta(stats.avgLowError, useCelsius)
                                    append(getString(
                                        R.string.accuracy_high_low_line,
                                        "%.1f°".format(highErr) + formatBias(stats.highBias, useCelsius),
                                        "%.1f°".format(lowErr) + formatBias(stats.lowBias, useCelsius),
                                    ))
                                    append("\n")
                                    val limitDeg = if (useCelsius) 1.7 else 3.0
                                    append(getString(
                                        R.string.accuracy_within_line,
                                        "%.1f°".format(limitDeg),
                                        "%.0f%%".format(stats.percentWithin3Degrees),
                                        stats.totalForecasts,
                                    ))
                                } else {
                                    append(getString(R.string.stats_source_no_data, source.displayName))
                                }
                                if (index < sourcesToShow.size - 1) {
                                    append("\n\n")
                                }
                            }
                        }
                    }
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.accuracy_summary_text).text = summary
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading forecast accuracy summary", e)
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.accuracy_summary_text).text =
                        getString(R.string.error_loading_accuracy_summary)
                }
            }
        }
    }

    private fun formatBias(bias: Double, useCelsius: Boolean): String =
        ForecastHistoryViewLogic.formatBias(bias, useCelsius) { v, low ->
            getString(if (low) R.string.bias_low_suffix else R.string.bias_high_suffix, v)
        }

    private fun updateModeUi() {
        val modeButton = findViewById<Button>(R.id.graph_mode_button)
        val actualLegendText = findViewById<TextView>(R.id.legend_actual_text)
        val hasActualValues = cachedApiActualRow?.apiHighTemp != null && cachedApiActualRow?.apiLowTemp != null
        val showTemperatureButton = ForecastHistoryViewLogic.shouldShowTemperatureButton(cachedDate, hasActualValues)
        when (ForecastHistoryViewLogic.resolveButtonMode(showTemperatureButton, graphMode)) {
            ButtonMode.EVOLUTION -> {
                modeButton.text = getString(R.string.forecast_mode_evolution)
                actualLegendText.text = getString(R.string.legend_actual)
            }
            ButtonMode.ERROR -> {
                modeButton.text = getString(R.string.forecast_mode_error)
                actualLegendText.text = getString(R.string.legend_zero_error)
            }
            ButtonMode.TEMPERATURE -> {
                modeButton.text = getString(R.string.forecast_show_hourly)
                actualLegendText.text = getString(R.string.legend_actual)
            }
        }
    }

    private fun launchWidgetTemperatureMode(targetDay: LocalDate) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TAG, "Cannot switch widget to hourly mode: missing appWidgetId")
            return
        }

        val offset = DayClickHelper.calculatePrecipitationOffset(LocalDateTime.now(), targetDay)
        val startMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "launchWidgetTemperatureMode: start widget=$appWidgetId targetDay=$targetDay offset=$offset")
        lifecycleScope.launch(Dispatchers.IO) {
            WidgetIntentRouter.handleSetView(
                context = this@ForecastHistoryActivity,
                appWidgetId = appWidgetId,
                targetMode = ViewMode.TEMPERATURE,
                targetOffset = offset,
            )
            val afterSetViewMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "launchWidgetTemperatureMode: handleSetView complete in ${afterSetViewMs - startMs}ms")
            withContext(Dispatchers.Main) {
                val beforeFinishMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "launchWidgetTemperatureMode: finishing activity at +${beforeFinishMs - startMs}ms")
                finish()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * "All sources for <day>": every visible source's forecast for [date], sorted by the remembered
     * column (shared [DaySourceComparison]; desktop draws the same rows in `DaySourceComparisonTable`).
     */
    private fun loadSourceComparison(date: LocalDate, lat: Double, lon: Double) {
        val sources = effectiveVisibleSources()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rows = DaySourceComparison.load(
                    host = RoomDaySourceComparisonHost(
                        forecastDao, dailyHistoryDao, hourlyForecastDao, sources,
                        RoomDaySourceComparisonHost.accuracyBias(accuracyCalculator),
                    ),
                    date = date,
                    lat = lat,
                    lon = lon,
                    now = LocalDateTime.now(),
                )
                withContext(Dispatchers.Main) {
                    // A slower load for a day the user has since left must not paint over the new day.
                    if (date == targetLocalDate) renderSourceComparison(date, rows)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading all-sources comparison for $date", e)
            }
        }
    }

    private fun renderSourceComparison(date: LocalDate, rows: List<DaySourceComparison.Row>) {
        val card = findViewById<View>(R.id.source_comparison_card)
        val table = findViewById<android.widget.LinearLayout>(R.id.source_comparison_table)
        table.removeAllViews()
        if (rows.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        sourceCompareRows = date to rows
        val sort = DaySourceComparison.Sort.decode(widgetStateManager.sourceCompareSort())
        val sorted = DaySourceComparison.sort(rows, sort)
        val locale = java.util.Locale.getDefault()
        val dayText = "${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)} " +
            "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, locale)} ${date.dayOfMonth}"
        findViewById<TextView>(R.id.source_comparison_title).text = getString(R.string.source_compare_title, dayText)

        val useCelsius = widgetStateManager.useCelsius()
        val nowMs = System.currentTimeMillis()
        val cells = sorted.map { DaySourceComparison.cells(it, nowMs, useCelsius) }
        // Card margins (12 + 12) and padding (12 + 12) around the table.
        val tableWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 48f
        val showAmount = DaySourceComparison.showAmountColumn(tableWidthDp, cells)
        val primary = DataScreenStyler.color(com.weatherwidget.shared.graph.DataScreenStyle.TEXT_PRIMARY)
        val secondary = DataScreenStyler.color(com.weatherwidget.shared.graph.DataScreenStyle.TEXT_SECONDARY)
        val names = listOf(
            R.string.source_compare_col_source, R.string.source_compare_col_high, R.string.source_compare_col_low,
            R.string.source_compare_col_rain, R.string.source_compare_col_peak, R.string.source_compare_col_night,
            R.string.source_compare_col_amount,
            R.string.source_compare_col_cloud, R.string.source_compare_col_bias, R.string.source_compare_col_age,
        ).map { getString(it) }
        val header = names.mapIndexed { i, name -> DaySourceComparison.headerLabel(name, DaySourceComparison.SortColumn.entries[i], sort) }
        table.addView(
            comparisonRow(header, showAmount, secondary, bold = false, staleAge = false) { i ->
                onSourceCompareHeaderTap(DaySourceComparison.SortColumn.entries[i])
            },
        )
        cells.forEachIndexed { i, c ->
            table.addView(comparisonRow(c.values(), showAmount, primary, bold = sorted[i].source == cachedRequestedSource, staleAge = c.ageIsStale))
        }
        findViewById<TextView>(R.id.source_comparison_legend).text = sourceComparisonLegend(names, showAmount)
    }

    /** One line per column, "Rain highest hourly chance, 8am–8pm": shared column choice, bold names, localized notes. */
    private fun sourceComparisonLegend(names: List<String>, showAmount: Boolean): CharSequence {
        val notes = mapOf(
            DaySourceComparison.SortColumn.DAY_RAIN to R.string.source_compare_legend_rain,
            DaySourceComparison.SortColumn.PEAK to R.string.source_compare_legend_peak,
            DaySourceComparison.SortColumn.NIGHT_RAIN to R.string.source_compare_legend_night,
            DaySourceComparison.SortColumn.AMOUNT to R.string.source_compare_legend_amount,
            DaySourceComparison.SortColumn.CLOUD to R.string.source_compare_legend_cloud,
            DaySourceComparison.SortColumn.BIAS to R.string.source_compare_legend_bias,
            DaySourceComparison.SortColumn.AGE to R.string.source_compare_legend_age,
        )
        val text = android.text.SpannableStringBuilder()
        DaySourceComparison.legendColumns(showAmount).forEachIndexed { i, column ->
            if (i > 0) text.append("\n")
            text.append(names[column.ordinal], android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(" ").append(getString(notes.getValue(column)))
        }
        return text
    }

    /** A header tap: flip or switch the sort, remember it, and redraw the loaded rows. */
    private fun onSourceCompareHeaderTap(column: DaySourceComparison.SortColumn) {
        val (date, rows) = sourceCompareRows ?: return
        val next = DaySourceComparison.Sort.decode(widgetStateManager.sourceCompareSort()).tapped(column)
        widgetStateManager.setSourceCompareSort(next.encode())
        DaySourceComparison.logSortTap(date, next, DaySourceComparison.sort(rows, next))
        renderSourceComparison(date, rows)
    }

    private fun comparisonRow(
        values: List<String>,
        showAmount: Boolean,
        color: Int,
        bold: Boolean,
        staleAge: Boolean,
        onCellClick: ((Int) -> Unit)? = null,
    ): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
        val secondary = DataScreenStyler.color(com.weatherwidget.shared.graph.DataScreenStyle.TEXT_SECONDARY)
        values.forEachIndexed { i, v ->
            if (i == DaySourceComparison.AMOUNT_COLUMN && !showAmount) return@forEachIndexed
            val isAge = i == values.lastIndex
            row.addView(
                TextView(this).apply {
                    text = v
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (isAge && staleAge) secondary else color)
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = if (i == 0) android.view.Gravity.START else android.view.Gravity.END
                    maxLines = 1
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, DaySourceComparison.COLUMN_WEIGHTS[i])
                    if (onCellClick != null) {
                        setOnClickListener { onCellClick(i) }
                        val ripple = TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                        setBackgroundResource(ripple.resourceId)
                    }
                },
            )
        }
        return row
    }

    /** The Current Observations look, from the palette desktop shares ([DataScreenStyler]). */
    private fun applyDataScreenStyle() {
        DataScreenStyler.background(findViewById(R.id.history_root))
        listOf(
            R.id.summary_card,
            R.id.no_data_text,
            R.id.high_graph_card,
            R.id.low_graph_card,
            R.id.actuals_legend_card,
            R.id.source_comparison_card,
            R.id.accuracy_card,
            R.id.freshness_card,
        ).forEach { DataScreenStyler.card(findViewById(it)) }
        listOf(R.id.api_source_button, R.id.graph_mode_button, R.id.detailed_stats_button, R.id.months_view_button)
            .forEach { DataScreenStyler.pillButton(findViewById(it)) }
    }

    /**
     * Refetches the viewed source's forecast only (user, 2026-10-09), through the same single-source
     * path a day tap uses, then reloads the graphs and repaints the widget that opened this screen.
     */
    private fun refreshViewedSource() {
        val source = cachedRequestedSource
        val tapContext = "source=${source?.id} date=$targetLocalDate lat=$targetLat lon=$targetLon"
        if (source == null || refreshInFlight) {
            val outcome = if (source == null) "ignored_no_source" else "ignored_in_flight"
            lifecycleScope.launch {
                appLogDao.log("HISTORY_REFRESH_TAP", "outcome=$outcome $tapContext", "INFO")
                if (refreshInFlight) {
                    com.weatherwidget.widget.RefreshToast.show(this@ForecastHistoryActivity, "history_refresh", com.weatherwidget.shared.util.RefreshNotice.NotQueued(com.weatherwidget.shared.util.RefreshNotice.Reason.IN_PROGRESS))
                }
            }
            return
        }
        val nowMs = System.currentTimeMillis()
        val lastRefresh = FetchMetadata.getLastUserRefreshTime(this, source.id)
        when (val decision = UserRefreshThrottle.check(lastRefresh, nowMs)) {
            is UserRefreshThrottle.Decision.Throttled -> {
                val notice = com.weatherwidget.shared.util.RefreshNotice.NotQueued(
                    com.weatherwidget.shared.util.RefreshNotice.Reason.RATE_LIMITED,
                    sourceName = source.name,
                    elapsedMinutes = decision.elapsedMinutes,
                    remainingMinutes = decision.remainingMinutes,
                )
                lifecycleScope.launch {
                    appLogDao.log("HISTORY_REFRESH_TAP", "outcome=throttled elapsedMin=${decision.elapsedMinutes} waitMin=${decision.remainingMinutes} $tapContext", "INFO")
                    com.weatherwidget.widget.RefreshToast.show(this@ForecastHistoryActivity, "history_refresh", notice)
                }
                return
            }
            is UserRefreshThrottle.Decision.Allowed -> {
                FetchMetadata.setLastUserRefreshTime(this, nowMs, source.id)
            }
        }
        lifecycleScope.launch {
            appLogDao.log("HISTORY_REFRESH_TAP", "outcome=fetch_started $tapContext", "INFO")
            fetchViewedSource(source, "history_refresh", userRefresh = true)
        }
    }

    /** The viewed source's fetch, from the Refresh button or the check on open; says how it went and reloads. */
    private suspend fun fetchViewedSource(source: WeatherSource, origin: String, userRefresh: Boolean) {
        val tapContext = "source=${source.id} date=$targetLocalDate lat=$targetLat lon=$targetLon"
        refreshInFlight = true
        val refreshButton = findViewById<View>(R.id.refresh_button)
        // Stays enabled (dimmed) so a second tap reaches the listener and is logged as ignored.
        refreshButton.alpha = 0.5f
        com.weatherwidget.widget.RefreshToast.show(this@ForecastHistoryActivity, origin, com.weatherwidget.shared.util.RefreshNotice.Queued(com.weatherwidget.shared.util.RefreshNotice.Kind.FORECAST, source.shortDisplayName))
        val startMs = android.os.SystemClock.elapsedRealtime()
        val startedAtMs = System.currentTimeMillis()
        val lat = targetLat
        val lon = targetLon
        val repository = weatherRepository
        val appContext = applicationContext
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val ok = try {
            // App scope, not lifecycleScope: leaving History mid-fetch must not discard it (the
            // fetch is billed whether or not the result is kept).
            userRefreshRunner.start(
                origin,
                afterDetached = { fetched -> if (fetched) sendUiOnlyWidgetRefresh(appContext, widgetId) },
            ) {
                repository.fetchSourceOnDemand(
                    lat,
                    lon,
                    source,
                    request = null,
                    userRefresh = userRefresh,
                )
            }.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "History refresh failed for ${source.id}", e)
            false
        }
        refreshInFlight = false
        refreshButton.alpha = 1.0f
        val outcome = com.weatherwidget.widget.SyncDoneNotice.outcome(widgetStateManager, source, ok, startedAtMs)
        appLogDao.log(
            RefreshOutcome.DONE_TAG,
            RefreshOutcome.doneLine(ok, tapContext, android.os.SystemClock.elapsedRealtime() - startMs, outcome),
            RefreshOutcome.doneLevel(ok),
        )
        com.weatherwidget.widget.RefreshToast.showOutcome(
            this@ForecastHistoryActivity,
            origin,
            outcome,
            refreshOutcomeText(this@ForecastHistoryActivity, outcome, source.shortDisplayName),
        )
        if (!ok) return
        loadData(targetDate, targetLat, targetLon, targetLocalDate, source)
        sendUiOnlyWidgetRefresh(appContext, widgetId)
    }

    private fun cycleApiSource() {
        val visibleSources = effectiveVisibleSources()
        if (visibleSources.isEmpty()) {
            return
        }

        val currentSource = cachedRequestedSource
        val currentIndex = visibleSources.indexOf(currentSource)
        val nextSource =
            if (currentIndex == -1) {
                visibleSources.first()
            } else {
                visibleSources[(currentIndex + 1) % visibleSources.size]
            }

        cachedRequestedSource = nextSource
        updateApiSourceButton()
        
        // Sync back to widget state if appWidgetId is available
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            widgetStateManager.setCurrentDisplaySource(appWidgetId, nextSource)
            
            // Trigger UI update to reflect the new source
            val updateIntent = Intent(this, WidgetActionReceiver::class.java).apply {
                action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
            }
            sendBroadcast(updateIntent)
        }

        loadData(
            targetDate = targetDate,
            lat = targetLat,
            lon = targetLon,
            date = targetLocalDate,
            requestedSource = nextSource,
        )
    }

    private fun updateApiSourceButton() {
        val source = cachedRequestedSource ?: firstVisibleSource() ?: WeatherSource.NWS
        findViewById<TextView>(R.id.api_source_button).text = source.shortDisplayName
    }

    private fun firstVisibleSource(): WeatherSource? {
        return effectiveVisibleSources().firstOrNull()
    }

    private fun effectiveVisibleSources(): List<WeatherSource> {
        return widgetStateManager.getVisibleSourcesOrder()
    }

    private fun updateFreshnessCard() {
        val forecastFetchView = findViewById<TextView>(R.id.freshness_forecast_fetch)
        val displayedDataView = findViewById<TextView>(R.id.freshness_displayed_data)

        val nowMs = System.currentTimeMillis()
        val source = cachedRequestedSource ?: firstVisibleSource() ?: WeatherSource.NWS
        val sourceSuccessMs = FetchMetadata.getLastForecastSourceSuccessTime(this, source.id, targetLat, targetLon)
        val lastFetchMs = resolveForecastFetchTime(sourceSuccessMs, FetchMetadata.getLastFullFetchTime(this))

        // Last forecast fetch
        forecastFetchView.text = if (lastFetchMs > 0L) {
            getString(R.string.freshness_forecast_fetch_ago, formatFetchAge(nowMs - lastFetchMs))
        } else {
            getString(R.string.freshness_forecast_fetch_never)
        }

        // Displayed data fetch age (from the actual weather entity being shown)
        val displayedFetchedAt = cachedApiActualRow?.updatedAt
        if (displayedFetchedAt != null && displayedFetchedAt > 0L) {
            val sourceName = cachedRequestedSource?.shortDisplayName
                ?: cachedApiActualRow?.source
                ?: getString(R.string.freshness_unknown_source)
            displayedDataView.text = getString(
                R.string.freshness_displayed_data,
                sourceName,
                formatFetchAge(nowMs - displayedFetchedAt),
            )
            displayedDataView.visibility = View.VISIBLE
        } else {
            displayedDataView.visibility = View.GONE
        }
    }

    /** Same compact duration as desktop's Forecast History ("15m", "1h 20m"). */
    private fun formatFetchAge(durationMs: Long): String =
        com.weatherwidget.shared.util.AgeFormatter.formatDuration(durationMs, getString(R.string.freshness_just_now))
}
