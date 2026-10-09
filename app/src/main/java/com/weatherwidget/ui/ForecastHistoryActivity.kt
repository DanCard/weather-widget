package com.weatherwidget.ui

import com.weatherwidget.shared.util.WeatherSourceOrdering
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
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
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ForecastHistoryViewLogic
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.data.repository.FetchMetadata
import com.weatherwidget.data.repository.WeatherRepository
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
    lateinit var dailyHistoryDao: DailyHistoryDao

    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    @Inject
    lateinit var weatherRepository: WeatherRepository

    companion object {
        const val EXTRA_TARGET_DATE = "target_date"
        const val EXTRA_LAT = "latitude"
        const val EXTRA_LON = "longitude"
        const val EXTRA_SOURCE = "source"
        private const val TAG = "ForecastHistoryActivity"


        /**
         * Determines whether clicking the mode button should launch hourly view
         * (true) or toggle graph mode (false).
         */
        fun shouldLaunchTemperature(hasDate: Boolean, showTemperatureButton: Boolean): Boolean =
            hasDate && showTemperatureButton

        /**
         * Shows hourly button when viewing today/future without actuals.
         */
        fun shouldShowTemperatureButton(
            date: LocalDate?,
            hasActualValues: Boolean,
            today: LocalDate = LocalDate.now(),
        ): Boolean = date != null && !date.isBefore(today) && !hasActualValues

        /**
         * Determines the button label mode: HOURLY when hourly button is active,
         * or the current graph mode (EVOLUTION/ERROR) otherwise.
         */
        fun resolveButtonMode(showTemperatureButton: Boolean, graphMode: GraphMode): ButtonMode =
            if (showTemperatureButton) ButtonMode.TEMPERATURE
            else if (graphMode == GraphMode.EVOLUTION) ButtonMode.EVOLUTION
            else ButtonMode.ERROR

        fun hasRequiredHistoryExtras(
            targetDate: String?,
            hasLatExtra: Boolean,
            hasLonExtra: Boolean,
        ): Boolean = targetDate != null && hasLatExtra && hasLonExtra

        internal fun normalizeSource(rawSource: String?): WeatherSource? =
            WeatherSource.fromDisplaySourceOrNull(rawSource)

        fun resolveActualLookupMode(
            date: LocalDate,
            requestedSource: WeatherSource?,
            today: LocalDate = LocalDate.now(),
        ): ActualLookupMode =
            if (!date.isBefore(today)) {
                ActualLookupMode.NONE
            } else if (requestedSource != null) {
                ActualLookupMode.SOURCE_SPECIFIC
            } else {
                ActualLookupMode.ANY_SOURCE
            }

        internal fun selectLatestCompleteActualFromForecasts(forecasts: List<ForecastEntity>): ForecastEntity? =
            forecasts
                .asSequence()
                .filter { it.highTemp != null && it.lowTemp != null }
                .maxByOrNull { it.fetchedAt }
    }

    enum class GraphMode {
        EVOLUTION,
        ERROR,
    }

    enum class ButtonMode {
        EVOLUTION,
        ERROR,
        TEMPERATURE,
    }

    enum class ActualLookupMode {
        NONE,
        SOURCE_SPECIFIC,
        ANY_SOURCE,
    }

    private var graphMode = GraphMode.EVOLUTION
    private var cachedSnapshots: List<ForecastEntity> = emptyList()
    private var cachedApiActualRow: DailyHistoryEntity? = null
    private var cachedAppActual: com.weatherwidget.data.local.DailyHistoryEntity? = null
    private var cachedDate: LocalDate? = null
    private var cachedRequestedSource: WeatherSource? = null
    private lateinit var targetDate: String
    private var targetLat: Double = 0.0
    private var targetLon: Double = 0.0
    private lateinit var targetLocalDate: LocalDate
    private lateinit var widgetStateManager: WidgetStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_history)

        val intentTargetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val requestedSource = normalizeSource(intent.getStringExtra(EXTRA_SOURCE))

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

        val visibleSources = effectiveVisibleSources()
        cachedRequestedSource = requestedSource?.takeIf { it in visibleSources } ?: visibleSources.firstOrNull()
        updateApiSourceButton()

        val graphModeButton = findViewById<Button>(R.id.graph_mode_button)
        graphModeButton.setOnClickListener {
            val date = cachedDate
            val hasActualValues = cachedApiActualRow?.apiHighTemp != null && cachedApiActualRow?.apiLowTemp != null
            val showTemperatureButton = shouldShowTemperatureButton(date, hasActualValues)
            if (shouldLaunchTemperature(date != null, showTemperatureButton)) {
                launchWidgetTemperatureMode(date!!)
                return@setOnClickListener
            }

            graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
            updateModeUi()
            if (cachedDate != null) {
                displayData(cachedSnapshots, cachedApiActualRow, cachedAppActual, cachedDate!!, cachedRequestedSource)
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
                val sortedAppActuals = appActuals.sortedBy {
                    com.weatherwidget.shared.util.TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon)
                }
                val appActual = sortedAppActuals.find { it.source == requestedSource?.id }

                withContext(Dispatchers.Main) {
                    displayData(snapshots, apiActualRow, appActual, date, requestedSource)
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
        appActual: com.weatherwidget.data.local.DailyHistoryEntity?,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        cachedSnapshots = snapshots
        cachedApiActualRow = apiActualRow
        cachedAppActual = appActual
        cachedDate = date
        cachedRequestedSource = requestedSource
        updateApiSourceButton()

        // Hindcasts (fetched after the extreme was reached) are not forecasts: drop them per side.
        // Today's extreme times are only the running max/min, so today uses the fixed cutoffs alone.
        val settledActual = appActual.takeIf { date.isBefore(LocalDate.now()) }
        val evolutionPoints = ForecastEvolutionCutoff.points(
            rows = snapshots.map {
                ForecastEvolutionCutoff.Row(it.dateOfPrediction, it.fetchedAt, it.highTemp, it.lowTemp, it.source)
            },
            targetDate = date,
            highReachedAt = settledActual?.computedHighAt,
            lowReachedAt = settledActual?.computedLowAt,
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
        val legendActualGroup = findViewById<View>(R.id.legend_actual_group)
        val legendAppActualGroup = findViewById<View>(R.id.legend_app_actual_group)

        if (isPastDate) {
            legendActualGroup.visibility = View.VISIBLE
            legendAppActualGroup.visibility = View.VISIBLE
        } else {
            legendActualGroup.visibility = View.GONE
            legendAppActualGroup.visibility = View.GONE
        }

        // Single forecast legend (one color for every API), relabeled to the selected API.
        findViewById<TextView>(R.id.legend_forecast_text).text =
            requestedSource?.shortDisplayName ?: getString(R.string.forecast_history_api_fallback_label)

        val apiHigh = if (isPastDate) apiActualRow?.apiHighTemp else null
        val apiLow = if (isPastDate) apiActualRow?.apiLowTemp else null
        val computedHighTemp = if (isPastDate) appActual?.computedHighTemp else null
        val computedLowTemp = if (isPastDate) appActual?.computedLowTemp else null

        val actualsLegendCard = findViewById<View>(R.id.actuals_legend_card)
        if ((apiHigh != null && apiLow != null) || (computedHighTemp != null && computedLowTemp != null)) {
            actualsLegendCard.visibility = View.VISIBLE

            val apiActualGroup = findViewById<View>(R.id.footer_api_actual_group)
            val apiActualText = findViewById<TextView>(R.id.footer_api_actual_text)
            if (apiHigh != null && apiLow != null) {
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
                    formatTemp(apiHigh),
                    formatTemp(apiLow),
                ) + provenance
            } else {
                apiActualGroup.visibility = View.GONE
            }

            val locationActualGroup = findViewById<View>(R.id.footer_location_actual_group)
            val locationActualText = findViewById<TextView>(R.id.footer_location_actual_text)
            if (computedHighTemp != null && computedLowTemp != null) {
                locationActualGroup.visibility = View.VISIBLE
                locationActualText.text = getString(
                    R.string.forecast_history_location_actual,
                    formatTemp(computedHighTemp),
                    formatTemp(computedLowTemp),
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
            if (isErrorMode && (apiHigh == null || apiLow == null)) {
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
        weatherRepository.recomputeDailyExtremesFromStoredObservations(lat, lon, startDate, endDate, emptyList(), force = true)
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
        val showTemperatureButton = shouldShowTemperatureButton(cachedDate, hasActualValues)
        when (resolveButtonMode(showTemperatureButton, graphMode)) {
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

    /** The Current Observations look, from the palette desktop shares ([DataScreenStyler]). */
    private fun applyDataScreenStyle() {
        DataScreenStyler.background(findViewById(R.id.history_root))
        listOf(
            R.id.summary_card,
            R.id.no_data_text,
            R.id.high_graph_card,
            R.id.low_graph_card,
            R.id.actuals_legend_card,
            R.id.accuracy_card,
            R.id.freshness_card,
        ).forEach { DataScreenStyler.card(findViewById(it)) }
        listOf(R.id.api_source_button, R.id.graph_mode_button, R.id.detailed_stats_button)
            .forEach { DataScreenStyler.pillButton(findViewById(it)) }
    }

    /**
     * Refetches the viewed source's forecast only (user, 2026-10-09), through the same single-source
     * path a day tap uses, then reloads the graphs and repaints the widget that opened this screen.
     */
    private fun refreshViewedSource() {
        val source = cachedRequestedSource ?: return
        val refreshButton = findViewById<View>(R.id.refresh_button)
        refreshButton.isEnabled = false
        refreshButton.alpha = 0.5f
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    weatherRepository.fetchSourceOnDemand(targetLat, targetLon, source, request = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "History refresh failed for ${source.id}", e)
                false
            }
            refreshButton.isEnabled = true
            refreshButton.alpha = 1.0f
            val message = if (ok) R.string.obs_refreshed_source else R.string.forecast_history_refresh_failed
            Toast.makeText(this@ForecastHistoryActivity, getString(message, source.shortDisplayName), Toast.LENGTH_SHORT).show()
            if (!ok) return@launch
            loadData(targetDate, targetLat, targetLon, targetLocalDate, source)
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                sendBroadcast(
                    Intent(this@ForecastHistoryActivity, WidgetActionReceiver::class.java).apply {
                        action = com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        putExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, true)
                    },
                )
            }
        }
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
        val lastFullFetchMs = FetchMetadata.getLastFullFetchTime(this)

        // Last full forecast fetch
        forecastFetchView.text = if (lastFullFetchMs > 0L) {
            getString(R.string.freshness_forecast_fetch_ago, formatRelativeTime(nowMs - lastFullFetchMs))
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
                formatRelativeTime(nowMs - displayedFetchedAt),
            )
            displayedDataView.visibility = View.VISIBLE
        } else {
            displayedDataView.visibility = View.GONE
        }
    }

    private fun formatRelativeTime(durationMs: Long): String {
        val minutes = durationMs / 60_000
        return when {
            minutes < 1 -> getString(R.string.freshness_just_now)
            minutes < 60 -> "${minutes}min"
            else -> {
                val hours = minutes / 60
                val remainMin = minutes % 60
                if (remainMin == 0L) "${hours}h" else "${hours}h ${remainMin}min"
            }
        }
    }
}
