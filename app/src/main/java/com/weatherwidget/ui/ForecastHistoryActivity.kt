package com.weatherwidget.ui

import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.weatherwidget.R
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.widget.ForecastEvolutionRenderer
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        private const val TAG = "ForecastHistoryActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast_history)

        // Get extras
        val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_LON, 0.0)

        if (targetDate == null || lat == 0.0) {
            Log.e(TAG, "Missing required extras")
            finish()
            return
        }

        Log.d(TAG, "Loading forecast history for $targetDate at $lat, $lon")

        // Setup back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        // Set date subtitle
        val date = LocalDate.parse(targetDate)
        val dateText = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                ", " + date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + date.dayOfMonth
        findViewById<TextView>(R.id.date_subtitle).text = dateText

        // Load data and render graphs
        loadData(targetDate, lat, lon, date)
    }

    private fun loadData(targetDate: String, lat: Double, lon: Double, date: LocalDate) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get all snapshots for this target date
                val snapshots = forecastSnapshotDao.getForecastEvolution(targetDate, lat, lon)
                Log.d(TAG, "Found ${snapshots.size} snapshots for $targetDate")

                // Get actual weather if this is a past date
                val actualWeather = if (date.isBefore(LocalDate.now())) {
                    weatherDao.getWeatherForDate(targetDate, lat, lon)
                } else null

                withContext(Dispatchers.Main) {
                    displayData(snapshots, actualWeather, date)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading forecast history", e)
            }
        }
    }

    private fun displayData(
        snapshots: List<com.weatherwidget.data.local.ForecastSnapshotEntity>,
        actualWeather: com.weatherwidget.data.local.WeatherEntity?,
        date: LocalDate
    ) {
        val targetDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Convert snapshots to EvolutionPoints
        val evolutionPoints = snapshots.map { snapshot ->
            val forecastDate = LocalDate.parse(snapshot.forecastDate)
            val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(forecastDate, date).toInt()

            ForecastEvolutionRenderer.EvolutionPoint(
                forecastDate = snapshot.forecastDate,
                fetchedAt = snapshot.fetchedAt,
                daysAhead = daysAhead,
                highTemp = snapshot.highTemp,
                lowTemp = snapshot.lowTemp,
                source = snapshot.source
            )
        }

        // Group by source
        val nwsPoints = evolutionPoints.filter { it.source == "NWS" }
        val meteoPoints = evolutionPoints.filter { it.source == "OPEN_METEO" }
        val gapPoints = evolutionPoints.filter { it.source == "GENERIC_GAP" }

        // Update summary
        val summaryText = buildString {
            append("${nwsPoints.size} forecasts from NWS, ${meteoPoints.size} from Open-Meteo")
            if (gapPoints.isNotEmpty()) {
                append(", ${gapPoints.size} from Climate Avg")
            }
        }
        findViewById<TextView>(R.id.snapshot_summary_text).text = summaryText

        // Show actual temps if past date
        val actualHigh = actualWeather?.highTemp
        val actualLow = actualWeather?.lowTemp

        if (actualHigh != null && actualLow != null) {
            val actualTextView = findViewById<TextView>(R.id.actual_temps_text)
            actualTextView.text = "Actual: $actualHigh° / $actualLow°"
            actualTextView.visibility = android.view.View.VISIBLE
        }

        // Render graphs
        val highGraphView = findViewById<ImageView>(R.id.high_temp_graph)
        val lowGraphView = findViewById<ImageView>(R.id.low_temp_graph)

        // Get screen dimensions for bitmap size
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels - dpToPx(32) // 16dp margin each side
        val height = dpToPx(200) // Fixed height for each graph

        if (nwsPoints.isNotEmpty() || meteoPoints.isNotEmpty()) {
            val highBitmap = ForecastEvolutionRenderer.renderHighGraph(
                context = this,
                nwsPoints = nwsPoints,
                meteoPoints = meteoPoints,
                actualHigh = actualHigh,
                widthPx = width,
                heightPx = height
            )
            highGraphView.setImageBitmap(highBitmap)

            val lowBitmap = ForecastEvolutionRenderer.renderLowGraph(
                context = this,
                nwsPoints = nwsPoints,
                meteoPoints = meteoPoints,
                actualLow = actualLow,
                widthPx = width,
                heightPx = height
            )
            lowGraphView.setImageBitmap(lowBitmap)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
