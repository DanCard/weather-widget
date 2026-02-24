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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StatisticsActivity : AppCompatActivity() {
    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    private lateinit var adapter: DailyAccuracyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

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
                val latestWeather = database.weatherDao().getLatestWeather()

                if (latestWeather == null) {
                    findViewById<TextView>(R.id.stats_summary_text).text =
                        "No weather data available yet."
                    return@launch
                }

                val lat = latestWeather.locationLat
                val lon = latestWeather.locationLon

                // Calculate comparison statistics
                val comparison = accuracyCalculator.calculateComparison(lat, lon, 30)

                // Get daily breakdown for both sources and combine
                val nwsDaily = accuracyCalculator.getDailyAccuracyBreakdown(WeatherSource.NWS, lat, lon, 30)
                val meteoDaily = accuracyCalculator.getDailyAccuracyBreakdown(WeatherSource.OPEN_METEO, lat, lon, 30)
                val weatherApiDaily = accuracyCalculator.getDailyAccuracyBreakdown(WeatherSource.WEATHER_API, lat, lon, 30)
                val allDaily = (nwsDaily + meteoDaily + weatherApiDaily).sortedByDescending { it.date }

                // Check if we have any data
                val hasAnyData = allDaily.isNotEmpty()

                // Display summary
                val summaryText =
                    if (!hasAnyData) {
                        "No historical forecast data available yet.\n\n" +
                            "Forecast snapshots are being saved daily. Check back tomorrow for your first accuracy comparison!"
                    } else {
                        buildString {
                            if (comparison.nwsStats != null && comparison.nwsStats.totalForecasts > 0) {
                                val stats = comparison.nwsStats
                                append(
                                    "NWS: High ±%.1f°%s, Low ±%.1f°%s\n".format(
                                        stats.avgHighError,
                                        formatBias(stats.highBias),
                                        stats.avgLowError,
                                        formatBias(stats.lowBias),
                                    ),
                                )
                            } else {
                                append("NWS: No data yet\n")
                            }

                            if (comparison.meteoStats != null && comparison.meteoStats.totalForecasts > 0) {
                                val stats = comparison.meteoStats
                                append(
                                    "Open-Meteo: High ±%.1f°%s, Low ±%.1f°%s\n".format(
                                        stats.avgHighError,
                                        formatBias(stats.highBias),
                                        stats.avgLowError,
                                        formatBias(stats.lowBias),
                                    ),
                                )
                            } else {
                                append("Open-Meteo: No data yet\n")
                            }

                            if (comparison.weatherApiStats != null && comparison.weatherApiStats.totalForecasts > 0) {
                                val stats = comparison.weatherApiStats
                                append(
                                    "WeatherAPI: High ±%.1f°%s, Low ±%.1f°%s".format(
                                        stats.avgHighError,
                                        formatBias(stats.highBias),
                                        stats.avgLowError,
                                        formatBias(stats.lowBias),
                                    ),
                                )
                            } else {
                                append("WeatherAPI: No data yet")
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
