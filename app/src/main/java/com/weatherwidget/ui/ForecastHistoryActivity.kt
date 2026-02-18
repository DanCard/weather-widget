package com.weatherwidget.ui

import android.os.Bundle
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
    }

    private enum class GraphMode {
        EVOLUTION,
        ERROR,
    }

    private var graphMode = GraphMode.EVOLUTION
    private var cachedSnapshots: List<ForecastSnapshotEntity> = emptyList()
    private var cachedActualWeather: WeatherEntity? = null
    private var cachedDate: LocalDate? = null
    private var cachedRequestedSource: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_history)

        // Get extras
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

        // Setup back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }
        val graphModeButton = findViewById<Button>(R.id.graph_mode_button)
        graphModeButton.setOnClickListener {
            graphMode = if (graphMode == GraphMode.EVOLUTION) GraphMode.ERROR else GraphMode.EVOLUTION
            updateModeUi()
            if (cachedDate != null) {
                displayData(cachedSnapshots, cachedActualWeather, cachedDate!!, cachedRequestedSource)
            }
        }
        updateModeUi()

        // Set date subtitle
        val date = LocalDate.parse(targetDate)
        val dateText =
            date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                ", " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + date.dayOfMonth
        findViewById<TextView>(R.id.date_subtitle).text = dateText

        // Load data and render graphs
        loadData(targetDate, lat, lon, date, requestedSource?.displayName)
    }

    private fun loadData(
        targetDate: String,
        lat: Double,
        lon: Double,
        date: LocalDate,
        requestedSource: String?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get all snapshots for this target date
                val allSnapshots = forecastSnapshotDao.getForecastEvolution(targetDate, lat, lon)
                val snapshots =
                    if (requestedSource != null) {
                        allSnapshots.filter { it.source == requestedSource }
                    } else {
                        allSnapshots
                    }
                Log.d(TAG, "Found ${snapshots.size} snapshots for $targetDate")

                // Get actual weather if this is a past date
                val actualWeather =
                    if (date.isBefore(LocalDate.now())) {
                        if (requestedSource != null) {
                            weatherDao.getWeatherForDateBySource(targetDate, lat, lon, requestedSource)
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
        requestedSource: String?,
    ) {
        cachedSnapshots = snapshots
        cachedActualWeather = actualWeather
        cachedDate = date
        cachedRequestedSource = requestedSource

        // Convert snapshots to EvolutionPoints
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

        // Group by source
        val nwsPoints = evolutionPoints.filter { it.source == WeatherSource.NWS }
        val meteoPoints = evolutionPoints.filter { it.source == WeatherSource.OPEN_METEO }
        val gapPoints = evolutionPoints.filter { it.source == WeatherSource.GENERIC_GAP }

        // Update summary
        val snapshotSummaryView = findViewById<TextView>(R.id.snapshot_summary_text)
        val summaryCount =
            when (normalizeSource(requestedSource)) {
                WeatherSource.NWS -> nwsPoints.size
                WeatherSource.OPEN_METEO -> meteoPoints.size
                else -> nwsPoints.size + meteoPoints.size
            }
        val summaryText =
            buildString {
                val source = normalizeSource(requestedSource)
                if (source == WeatherSource.NWS) {
                    append("$summaryCount NWS forecast snapshots")
                } else if (source == WeatherSource.OPEN_METEO) {
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
        when (normalizeSource(requestedSource)) {
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

        // Show actual temps if past date
        val actualHigh = actualWeather?.highTemp
        val actualLow = actualWeather?.lowTemp

        if (actualHigh != null && actualLow != null) {
            val actualTextView = findViewById<TextView>(R.id.actual_temps_text)
            val sourceLabel = normalizeSource(requestedSource)?.displayName ?: "Observed"
            actualTextView.text = "$sourceLabel actual: $actualHigh° / $actualLow°"
            actualTextView.visibility = View.VISIBLE
        }

        // Render graphs
        val highGraphView = findViewById<ImageView>(R.id.high_temp_graph)
        val lowGraphView = findViewById<ImageView>(R.id.low_temp_graph)
        val highCard = findViewById<View>(R.id.high_graph_card)
        val lowCard = findViewById<View>(R.id.low_graph_card)
        val highTitle = findViewById<TextView>(R.id.high_graph_title)
        val lowTitle = findViewById<TextView>(R.id.low_graph_title)
        val noDataTextView = findViewById<TextView>(R.id.no_data_text)
        val isErrorMode = graphMode == GraphMode.ERROR

        // Get screen dimensions for bitmap size
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels - dpToPx(44) // Card (12*2) + padding (6*2) + img margin (4*2)
        val height = dpToPx(220) // Match ImageView height

        highTitle.text =
            if (isErrorMode) {
                getString(R.string.forecast_error_high_title)
            } else {
                getString(R.string.forecast_evolution_high_title)
            }
        lowTitle.text =
            if (isErrorMode) {
                getString(R.string.forecast_error_low_title)
            } else {
                getString(R.string.forecast_evolution_low_title)
            }

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
            val sourceLabel = normalizeSource(requestedSource)?.displayName ?: "selected source"
            noDataTextView.text = "No forecast history for this date ($sourceLabel)."
            noDataTextView.visibility = View.VISIBLE
            highCard.visibility = View.GONE
            lowCard.visibility = View.GONE
        }
    }

    private fun updateModeUi() {
        val modeButton = findViewById<Button>(R.id.graph_mode_button)
        val actualLegendText = findViewById<TextView>(R.id.legend_actual_text)
        if (graphMode == GraphMode.EVOLUTION) {
            modeButton.text = getString(R.string.forecast_mode_evolution)
            actualLegendText.text = getString(R.string.legend_actual)
        } else {
            modeButton.text = getString(R.string.forecast_mode_error)
            actualLegendText.text = getString(R.string.legend_zero_error)
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
