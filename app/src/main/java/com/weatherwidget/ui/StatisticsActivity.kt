package com.weatherwidget.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.stats.AccuracyCalculator
import com.weatherwidget.stats.DailyAccuracy
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StatisticsActivity : AppCompatActivity() {
    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    private lateinit var adapter: DailyAccuracyAdapter
    private lateinit var widgetStateManager: WidgetStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        widgetStateManager = WidgetStateManager(this)
        setupViews()
        loadStatistics()
    }

    private fun setupViews() {
        // Back button
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        // RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.daily_accuracy_list)
        adapter = DailyAccuracyAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadStatistics() {
        lifecycleScope.launch {
            try {
                // Get location from latest weather data
                val database = WeatherDatabase.getDatabase(this@StatisticsActivity)
                val latestWeather = database.forecastDao().getLatestWeather()

                if (latestWeather == null) {
                    findViewById<TextView>(R.id.stats_summary_text).text =
                        "No weather data available yet."
                    return@launch
                }

                val lat = latestWeather.locationLat
                val lon = latestWeather.locationLon
                val enabledSources = widgetStateManager.getVisibleSourcesOrder().toSet()

                // Calculate comparison statistics
                val comparison = accuracyCalculator.calculateComparison(lat, lon, 30)

                // Get daily breakdown for enabled sources and combine
                val allDaily = mutableListOf<DailyAccuracy>()
                val sourcesToQuery = listOf(
                    WeatherSource.NWS,
                    WeatherSource.VISUAL_CROSSING,
                    WeatherSource.OPEN_WEATHER_MAP,
                    WeatherSource.OPEN_METEO,
                    WeatherSource.WEATHER_API,
                    WeatherSource.TOMORROW_IO,
                    WeatherSource.SILURIAN,
                )

                sourcesToQuery.forEach { source ->
                    if (enabledSources.contains(source)) {
                        allDaily.addAll(accuracyCalculator.getDailyAccuracyBreakdown(source, lat, lon, 30))
                    }
                }
                allDaily.sortByDescending { it.date }

                // Check if we have any data
                val hasAnyData = allDaily.isNotEmpty()

                // Display summary
                val summaryText =
                    if (!hasAnyData) {
                        "No historical forecast data available yet.\n\n" +
                            "Forecast snapshots are being saved daily. Check back tomorrow for your first accuracy comparison!"
                    } else {
                        buildString {
                            val sourcesToStats = listOf(
                                WeatherSource.NWS to comparison.nwsStats,
                                WeatherSource.VISUAL_CROSSING to comparison.visualCrossingStats,
                                WeatherSource.OPEN_WEATHER_MAP to comparison.openWeatherMapStats,
                                WeatherSource.OPEN_METEO to comparison.meteoStats,
                                WeatherSource.WEATHER_API to comparison.weatherApiStats,
                                WeatherSource.TOMORROW_IO to comparison.tomorrowIoStats,
                                WeatherSource.SILURIAN to comparison.silurianStats,
                            )

                            sourcesToStats.forEachIndexed { index, (source, stats) ->
                                if (enabledSources.contains(source)) {
                                    if (stats != null && stats.totalForecasts > 0) {
                                        append(
                                            "${source.displayName}: High ±%.1f°%s, Low ±%.1f°%s".format(
                                                stats.avgHighError,
                                                formatBias(stats.highBias),
                                                stats.avgLowError,
                                                formatBias(stats.lowBias),
                                            ),
                                        )
                                    } else {
                                        append("${source.displayName}: No data yet")
                                    }
                                    if (index < sourcesToStats.size - 1) {
                                        append("\n")
                                    }
                                }
                            }
                        }
                    }
                findViewById<TextView>(R.id.stats_summary_text).text = summaryText

                adapter.setItems(allDaily)
            } catch (e: Exception) {
                android.util.Log.e("StatisticsActivity", "Error loading statistics", e)
                findViewById<TextView>(R.id.stats_summary_text).text =
                    "Error loading statistics: ${e.message}"
            }
        }
    }

    private fun formatBias(bias: Double): String {
        val absBias = kotlin.math.abs(bias)
        return when {
            absBias < 0.5 -> ""
            bias > 0 -> " (${absBias.toInt()}° low)"
            else -> " (${absBias.toInt()}° high)"
        }
    }
}
