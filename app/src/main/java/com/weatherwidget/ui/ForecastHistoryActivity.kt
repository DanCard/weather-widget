package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
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
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.ForecastSnapshotEntity
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ForecastEvolutionRenderer
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.handlers.DayClickHelper
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ForecastHistoryActivity : AppCompatActivity() {
    @Inject
    lateinit var forecastSnapshotDao: ForecastSnapshotDao

    @Inject
    lateinit var weatherDao: WeatherDao

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
        fun shouldLaunchHourly(hasDate: Boolean, snapshotsEmpty: Boolean): Boolean =
            hasDate && snapshotsEmpty

        /**
         * Determines the button label mode: HOURLY when no history exists,
         * or the current graph mode (EVOLUTION/ERROR) when history exists.
         */
        fun resolveButtonMode(snapshotsEmpty: Boolean, graphMode: GraphMode): ButtonMode =
            if (snapshotsEmpty) ButtonMode.HOURLY
            else if (graphMode == GraphMode.EVOLUTION) ButtonMode.EVOLUTION
            else ButtonMode.ERROR
    }

    enum class GraphMode {
        EVOLUTION,
        ERROR,
    }

    enum class ButtonMode {
        EVOLUTION,
        ERROR,
        HOURLY,
    }

    private var graphMode = GraphMode.EVOLUTION
    private var cachedSnapshots: List<ForecastSnapshotEntity> = emptyList()
    private var cachedActualWeather: WeatherEntity? = null
    private var cachedDate: LocalDate? = null
    private var cachedRequestedSource: WeatherSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_history)

        val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val requestedSource = normalizeSource(intent.getStringExtra(EXTRA_SOURCE))

        if (targetDate == null || lat == 0.0) {
            Log.e(TAG, "Missing required extras")
            finish()
            return
        }

        Log.d(TAG, "Loading forecast history for $targetDate at $lat, $lon (source=$requestedSource)")

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        val graphModeButton = findViewById<Button>(R.id.graph_mode_button)
        graphModeButton.setOnClickListener {
            val date = cachedDate
            if (shouldLaunchHourly(date != null, cachedSnapshots.isEmpty())) {
                launchWidgetHourlyMode(date!!)
                return@setOnClickListener
            }

            graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
            updateModeUi()
            if (cachedDate != null) {
                displayData(cachedSnapshots, cachedActualWeather, cachedDate!!, cachedRequestedSource)
            }
        }
        updateModeUi()

        val date = LocalDate.parse(targetDate)
        val dateText =
            date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                ", " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + date.dayOfMonth
        findViewById<TextView>(R.id.date_subtitle).text = dateText

        loadData(targetDate, lat, lon, date, requestedSource)
    }

    private fun loadData(
        targetDate: String,
        lat: Double,
        lon: Double,
        date: LocalDate,
        requestedSource: WeatherSource?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
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
                    if (date.isBefore(LocalDate.now())) {
                        if (requestedSource != null) {
                            weatherDao.getWeatherForDateBySource(targetDate, lat, lon, requestedSource.id)
                                ?: weatherDao.getWeatherForDate(targetDate, lat, lon)
                        } else {
                            weatherDao.getWeatherForDate(targetDate, lat, lon)
                        }
                    } else {
                        null
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

        val evolutionPoints =
            snapshots.map { snapshot ->
                val forecastDate = LocalDate.parse(snapshot.forecastDate)
                val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(forecastDate, date).toInt()
                ForecastEvolutionRenderer.EvolutionPoint(
                    forecastDate = snapshot.forecastDate,
                    fetchedAt = snapshot.fetchedAt,
                    daysAhead = daysAhead,
                    highTemp = snapshot.highTemp,
                    lowTemp = snapshot.lowTemp,
                    source = WeatherSource.fromId(snapshot.source),
                )
            }

        val nwsPoints = evolutionPoints.filter { it.source == WeatherSource.NWS }
        val meteoPoints = evolutionPoints.filter { it.source == WeatherSource.OPEN_METEO }
        val gapPoints = evolutionPoints.filter { it.source == WeatherSource.GENERIC_GAP }

        val snapshotSummaryView = findViewById<TextView>(R.id.snapshot_summary_text)
        val summaryCount =
            when (requestedSource) {
                WeatherSource.NWS -> nwsPoints.size
                WeatherSource.OPEN_METEO -> meteoPoints.size
                else -> nwsPoints.size + meteoPoints.size
            }
        val summaryText =
            buildString {
                if (requestedSource == WeatherSource.NWS) {
                    append("$summaryCount NWS forecast snapshots")
                } else if (requestedSource == WeatherSource.OPEN_METEO) {
                    append("$summaryCount Open-Meteo forecast snapshots")
                } else {
                    append("${nwsPoints.size} NWS + ${meteoPoints.size} Open-Meteo snapshots")
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
            actualTextView.text = "$sourceLabel actual: $actualHigh° / $actualLow°"
            actualTextView.visibility = View.VISIBLE
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

        if (nwsPoints.isNotEmpty() || meteoPoints.isNotEmpty()) {
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
                        meteoPoints = meteoPoints,
                        actualHigh = actualHigh,
                        widthPx = width,
                        heightPx = height,
                    )
                } else {
                    ForecastEvolutionRenderer.renderHighGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoPoints,
                        actualHigh = actualHigh,
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
                        meteoPoints = meteoPoints,
                        actualLow = actualLow,
                        widthPx = width,
                        heightPx = height,
                    )
                } else {
                    ForecastEvolutionRenderer.renderLowGraph(
                        context = this,
                        nwsPoints = nwsPoints,
                        meteoPoints = meteoPoints,
                        actualLow = actualLow,
                        widthPx = width,
                        heightPx = height,
                    )
                }
            lowGraphView.setImageBitmap(lowBitmap)
        } else {
            val sourceLabel = requestedSource?.displayName ?: "selected source"
            noDataTextView.text = "No forecast history for this date ($sourceLabel)."
            noDataTextView.visibility = View.VISIBLE
            highCard.visibility = View.GONE
            lowCard.visibility = View.GONE
        }
        updateModeUi()
    }

    private fun updateModeUi() {
        val modeButton = findViewById<Button>(R.id.graph_mode_button)
        val actualLegendText = findViewById<TextView>(R.id.legend_actual_text)
        when (resolveButtonMode(cachedSnapshots.isEmpty(), graphMode)) {
            ButtonMode.EVOLUTION -> {
                modeButton.text = getString(R.string.forecast_mode_evolution)
                actualLegendText.text = getString(R.string.legend_actual)
            }
            ButtonMode.ERROR -> {
                modeButton.text = getString(R.string.forecast_mode_error)
                actualLegendText.text = getString(R.string.legend_zero_error)
            }
            ButtonMode.HOURLY -> {
                modeButton.text = getString(R.string.forecast_show_hourly)
                actualLegendText.text = getString(R.string.legend_actual)
            }
        }
    }

    private fun launchWidgetHourlyMode(targetDay: LocalDate) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.w(TAG, "Cannot switch widget to hourly mode: missing appWidgetId")
            return
        }

        val offset = DayClickHelper.calculatePrecipitationOffset(LocalDateTime.now(), targetDay)
        val startMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "launchWidgetHourlyMode: start widget=$appWidgetId targetDay=$targetDay offset=$offset")
        CoroutineScope(Dispatchers.IO).launch {
            WidgetIntentRouter.handleSetView(
                context = this@ForecastHistoryActivity,
                appWidgetId = appWidgetId,
                targetMode = ViewMode.HOURLY,
                targetOffset = offset,
            )
            val afterSetViewMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "launchWidgetHourlyMode: handleSetView complete in ${afterSetViewMs - startMs}ms")
            withContext(Dispatchers.Main) {
                val beforeFinishMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "launchWidgetHourlyMode: finishing activity at +${beforeFinishMs - startMs}ms")
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
            else -> null
        }
    }
}
