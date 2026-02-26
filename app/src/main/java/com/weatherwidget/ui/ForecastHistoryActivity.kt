package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.widget.ForecastEvolutionRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.handlers.DayClickHelper
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@AndroidEntryPoint
class ForecastHistoryActivity : AppCompatActivity() {
    @Inject
    lateinit var forecastSnapshotDao: ForecastSnapshotDao

    @Inject
    lateinit var weatherDao: WeatherDao

    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

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
    private var cachedSnapshots: List<ForecastSnapshotEntity> = emptyList()
    private var cachedActualWeather: WeatherEntity? = null
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

        val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val requestedSource = normalizeSource(intent.getStringExtra(EXTRA_SOURCE))

        if (!hasRequiredHistoryExtras(targetDate, intent.hasExtra(EXTRA_LAT), intent.hasExtra(EXTRA_LON))) {
            Log.e(TAG, "Missing required extras")
            finish()
            return
        }
        val safeTargetDate = checkNotNull(targetDate)
        widgetStateManager = WidgetStateManager(this)
        this.targetDate = safeTargetDate
        targetLat = lat
        targetLon = lon
        targetLocalDate = LocalDate.parse(safeTargetDate)

        Log.d(TAG, "Loading forecast history for $safeTargetDate at $lat, $lon (source=$requestedSource)")

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.api_source_button).setOnClickListener {
            cycleApiSource()
        }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        cachedRequestedSource = requestedSource ?: firstVisibleSource()
        updateApiSourceButton()

        val graphModeButton = findViewById<Button>(R.id.graph_mode_button)
        graphModeButton.setOnClickListener {
            val date = cachedDate
            val hasActualValues = cachedActualWeather?.highTemp != null && cachedActualWeather?.lowTemp != null
            val showTemperatureButton = shouldShowTemperatureButton(date, hasActualValues)
            if (shouldLaunchTemperature(date != null, showTemperatureButton)) {
                launchWidgetTemperatureMode(date!!)
                return@setOnClickListener
            }

            graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
            updateModeUi()
            if (cachedDate != null) {
                displayData(cachedSnapshots, cachedActualWeather, cachedDate!!, cachedRequestedSource)
            }
        }
        updateModeUi()

        val dateText =
            targetLocalDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                ", " + targetLocalDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + targetLocalDate.dayOfMonth
        findViewById<TextView>(R.id.date_subtitle).text = dateText

        loadData(
            targetDate = this.targetDate,
            lat = targetLat,
            lon = targetLon,
            date = targetLocalDate,
            requestedSource = checkNotNull(cachedRequestedSource),
        )
        loadAccuracySummary(targetLat, targetLon)
    }

    private fun loadData(
        targetDate: String,
        lat: Double,
        lon: Double,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allSnapshots = forecastSnapshotDao.getForecastEvolution(targetDate, lat, lon)
                val snapshots =
                    if (requestedSource != null) {
                        allSnapshots.filter { it.source == requestedSource.id }
                    } else {
                        allSnapshots
                    }
                Log.d(TAG, "Found ${snapshots.size} snapshots for $targetDate")

                val actualWeather =
                    when (resolveActualLookupMode(date, requestedSource)) {
                        ActualLookupMode.NONE -> null
                        ActualLookupMode.SOURCE_SPECIFIC ->
                            weatherDao.getWeatherForDateBySource(
                                targetDate,
                                lat,
                                lon,
                                checkNotNull(requestedSource).id,
                            )
                        ActualLookupMode.ANY_SOURCE ->
                            weatherDao.getWeatherForDate(targetDate, lat, lon)
                    }

                withContext(Dispatchers.Main) {
                    displayData(snapshots, actualWeather, date, requestedSource)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading forecast history", e)
            }
        }
    }

    private fun displayData(
        snapshots: List<ForecastSnapshotEntity>,
        actualWeather: WeatherEntity?,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        cachedSnapshots = snapshots
        cachedActualWeather = actualWeather
        cachedDate = date
        cachedRequestedSource = requestedSource
        updateApiSourceButton()

        val evolutionPoints =
            snapshots.map { snapshot ->
                val forecastDate = LocalDate.parse(snapshot.forecastDate)
                val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(forecastDate, date).toInt()
                ForecastEvolutionRenderer.EvolutionPoint(
                    forecastDate = snapshot.forecastDate,
                    fetchedAt = snapshot.fetchedAt,
                    daysAhead = daysAhead,
                    highTemp = snapshot.highTemp?.roundToInt(),
                    lowTemp = snapshot.lowTemp?.roundToInt(),
                    source = WeatherSource.fromId(snapshot.source),
                )
            }

        val nwsPoints = evolutionPoints.filter { it.source == WeatherSource.NWS }
        val meteoPoints = evolutionPoints.filter { it.source == WeatherSource.OPEN_METEO }
        val weatherApiPoints = evolutionPoints.filter { it.source == WeatherSource.WEATHER_API }
        val meteoLikePoints = meteoPoints + weatherApiPoints
        val gapPoints = evolutionPoints.filter { it.source == WeatherSource.GENERIC_GAP }

        val snapshotSummaryView = findViewById<TextView>(R.id.snapshot_summary_text)
        val summaryCount =
            when (requestedSource) {
                WeatherSource.NWS -> nwsPoints.size
                WeatherSource.OPEN_METEO -> meteoPoints.size
                WeatherSource.WEATHER_API -> weatherApiPoints.size
                else -> nwsPoints.size + meteoPoints.size + weatherApiPoints.size
            }
        val summaryText =
            buildString {
                if (requestedSource == WeatherSource.NWS) {
                    append("$summaryCount NWS forecast snapshots")
                } else if (requestedSource == WeatherSource.OPEN_METEO) {
                    append("$summaryCount Open-Meteo forecast snapshots")
                } else if (requestedSource == WeatherSource.WEATHER_API) {
                    append("$summaryCount WeatherAPI forecast snapshots")
                } else {
                    append("${nwsPoints.size} NWS + ${meteoPoints.size} Open-Meteo + ${weatherApiPoints.size} WeatherAPI snapshots")
                    if (gapPoints.isNotEmpty()) append(" • ${gapPoints.size} climate-fill points")
                }
            }
        snapshotSummaryView.text = summaryText
        if (summaryCount == 0) {
            snapshotSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            snapshotSummaryView.setTextColor(resources.getColor(R.color.widget_text_primary, theme))
            snapshotSummaryView.setTypeface(snapshotSummaryView.typeface, android.graphics.Typeface.BOLD)
        } else {
            snapshotSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            snapshotSummaryView.setTextColor(resources.getColor(R.color.widget_text_secondary, theme))
            snapshotSummaryView.setTypeface(snapshotSummaryView.typeface, android.graphics.Typeface.NORMAL)
        }

        val nwsLegend = findViewById<View>(R.id.legend_nws_group)
        val meteoLegend = findViewById<View>(R.id.legend_meteo_group)
        when (requestedSource) {
            WeatherSource.NWS -> {
                nwsLegend.visibility = View.VISIBLE
                meteoLegend.visibility = View.GONE
            }
            WeatherSource.OPEN_METEO -> {
                nwsLegend.visibility = View.GONE
                meteoLegend.visibility = View.VISIBLE
            }
            WeatherSource.WEATHER_API -> {
                nwsLegend.visibility = View.GONE
                meteoLegend.visibility = View.VISIBLE
            }
            else -> {
                nwsLegend.visibility = View.VISIBLE
                meteoLegend.visibility = View.VISIBLE
            }
        }

        val actualHigh = actualWeather?.highTemp
        val actualLow = actualWeather?.lowTemp
        if (actualHigh != null && actualLow != null) {
            val actualTextView = findViewById<TextView>(R.id.actual_temps_text)
            val sourceLabel = requestedSource?.displayName ?: "Observed"
            actualTextView.text = "$sourceLabel actual: ${formatTemp(actualHigh)} / ${formatTemp(actualLow)}"
            actualTextView.visibility = View.VISIBLE
        } else {
            findViewById<TextView>(R.id.actual_temps_text).visibility = View.INVISIBLE
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

        if (nwsPoints.isNotEmpty() || meteoLikePoints.isNotEmpty()) {
            if (isErrorMode && (actualHigh == null || actualLow == null)) {
                noDataTextView.text = getString(R.string.forecast_error_requires_actuals)
                noDataTextView.visibility = View.VISIBLE
                highCard.visibility = View.GONE
                lowCard.visibility = View.GONE
                return
            }

            noDataTextView.visibility = View.GONE
            highCard.visibility = View.VISIBLE
            lowCard.visibility = View.VISIBLE

            val highBitmap =
                if (isErrorMode) {
                    ForecastEvolutionRenderer.renderHighErrorGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoLikePoints,
                        actualHigh = actualHigh?.roundToInt(),
                        widthPx = width,
                        heightPx = height,
                    )
                } else {
                    ForecastEvolutionRenderer.renderHighGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoLikePoints,
                        actualHigh = actualHigh?.roundToInt(),
                        widthPx = width,
                        heightPx = height,
                    )
                }
            highGraphView.setImageBitmap(highBitmap)

            val lowBitmap =
                if (isErrorMode) {
                    ForecastEvolutionRenderer.renderLowErrorGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoLikePoints,
                        actualLow = actualLow?.roundToInt(),
                        widthPx = width,
                        heightPx = height,
                    )
                } else {
                    ForecastEvolutionRenderer.renderLowGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoLikePoints,
                        actualLow = actualLow?.roundToInt(),
                        widthPx = width,
                        heightPx = height,
                    )
                }
            lowGraphView.setImageBitmap(lowBitmap)
        } else {
            val sourceLabel = requestedSource?.displayName ?: "selected source"
            noDataTextView.text = getString(R.string.forecast_history_no_data_for_source, sourceLabel)
            noDataTextView.visibility = View.VISIBLE
            highCard.visibility = View.GONE
            lowCard.visibility = View.GONE
        }
        updateModeUi()
    }

    private fun formatTemp(value: Float): String {
        val rounded = value.roundToInt()
        return if (abs(value - rounded.toFloat()) < 0.01f) "$rounded°" else String.format("%.1f°", value)
    }

    private fun loadAccuracySummary(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val comparison = accuracyCalculator.calculateComparison(lat, lon, 30)
                val hasAnyData =
                    (comparison.nwsStats?.totalForecasts ?: 0) > 0 ||
                        (comparison.meteoStats?.totalForecasts ?: 0) > 0 ||
                        (comparison.weatherApiStats?.totalForecasts ?: 0) > 0

                val summary =
                    if (!hasAnyData) {
                        "No historical forecast data available yet.\n" +
                            "Forecast snapshots are saved daily. Check back tomorrow for your first accuracy comparison."
                    } else {
                        buildString {
                            val nws = comparison.nwsStats
                            if (nws != null && nws.totalForecasts > 0) {
                                append("NWS\n")
                                append("High ±%.1f°%s  Low ±%.1f°%s\n".format(
                                    nws.avgHighError,
                                    formatBias(nws.highBias),
                                    nws.avgLowError,
                                    formatBias(nws.lowBias),
                                ))
                                append("%% within 3°: %.0f%%  Forecasts: %d\n\n".format(nws.percentWithin3Degrees, nws.totalForecasts))
                            } else {
                                append("NWS: No data yet\n\n")
                            }

                            val meteo = comparison.meteoStats
                            if (meteo != null && meteo.totalForecasts > 0) {
                                append("Open-Meteo\n")
                                append("High ±%.1f°%s  Low ±%.1f°%s\n".format(
                                    meteo.avgHighError,
                                    formatBias(meteo.highBias),
                                    meteo.avgLowError,
                                    formatBias(meteo.lowBias),
                                ))
                                append("%% within 3°: %.0f%%  Forecasts: %d\n\n".format(meteo.percentWithin3Degrees, meteo.totalForecasts))
                            } else {
                                append("Open-Meteo: No data yet\n\n")
                            }

                            val weatherApi = comparison.weatherApiStats
                            if (weatherApi != null && weatherApi.totalForecasts > 0) {
                                append("WeatherAPI\n")
                                append("High ±%.1f°%s  Low ±%.1f°%s\n".format(
                                    weatherApi.avgHighError,
                                    formatBias(weatherApi.highBias),
                                    weatherApi.avgLowError,
                                    formatBias(weatherApi.lowBias),
                                ))
                                append("%% within 3°: %.0f%%  Forecasts: %d".format(weatherApi.percentWithin3Degrees, weatherApi.totalForecasts))
                            } else {
                                append("WeatherAPI: No data yet")
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

    private fun formatBias(bias: Double): String {
        val absBias = kotlin.math.abs(bias)
        return when {
            absBias < 0.5 -> ""
            bias > 0 -> " (${String.format("%.1f", absBias)}° low)"
            else -> " (${String.format("%.1f", absBias)}° high)"
        }
    }

    private fun updateModeUi() {
        val modeButton = findViewById<Button>(R.id.graph_mode_button)
        val actualLegendText = findViewById<TextView>(R.id.legend_actual_text)
        val hasActualValues = cachedActualWeather?.highTemp != null && cachedActualWeather?.lowTemp != null
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

    private fun normalizeSource(rawSource: String?): WeatherSource? {
        return when (rawSource) {
            "NWS" -> WeatherSource.NWS
            "Open-Meteo", "OPEN_METEO" -> WeatherSource.OPEN_METEO
            "WeatherAPI", "WEATHER_API" -> WeatherSource.WEATHER_API
            else -> null
        }
    }

    private fun cycleApiSource() {
        val visibleSources = widgetStateManager.getVisibleSourcesOrder()
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
            val updateIntent = Intent(this, com.weatherwidget.widget.WeatherWidgetProvider::class.java).apply {
                action = com.weatherwidget.widget.WeatherWidgetProvider.ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.weatherwidget.widget.WeatherWidgetProvider.EXTRA_UI_ONLY, true)
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
        return widgetStateManager.getVisibleSourcesOrder().firstOrNull()
    }
}
