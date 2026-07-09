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
import com.weatherwidget.stats.DailyRainAccuracy
import com.weatherwidget.stats.RainAccuracyCalculator
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StatisticsActivity : AppCompatActivity() {
    @Inject
    lateinit var accuracyCalculator: AccuracyCalculator

    @Inject
    lateinit var rainAccuracyCalculator: RainAccuracyCalculator

    private lateinit var adapter: DailyAccuracyAdapter
    private lateinit var rainAdapter: DailyRainAccuracyAdapter
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

        // Temperature accuracy list
        val recyclerView = findViewById<RecyclerView>(R.id.daily_accuracy_list)
        adapter = DailyAccuracyAdapter(widgetStateManager.useCelsius())
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Day/night rain accuracy list
        val rainRecyclerView = findViewById<RecyclerView>(R.id.daily_rain_accuracy_list)
        rainAdapter = DailyRainAccuracyAdapter()
        rainRecyclerView.adapter = rainAdapter
        rainRecyclerView.layoutManager = LinearLayoutManager(this)
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

                val allRainDaily = mutableListOf<DailyRainAccuracy>()
                sourcesToQuery.forEach { source ->
                    if (enabledSources.contains(source)) {
                        allDaily.addAll(accuracyCalculator.getDailyAccuracyBreakdown(source, lat, lon, 30))
                        allRainDaily.addAll(rainAccuracyCalculator.getDailyRainAccuracy(source, lat, lon, 30))
                    }
                }
                allDaily.sortByDescending { it.date }
                allRainDaily.sortByDescending { it.date }

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

                            val useCelsius = widgetStateManager.useCelsius()
                            sourcesToStats.forEachIndexed { index, (source, stats) ->
                                if (enabledSources.contains(source)) {
                                    if (stats != null && stats.totalForecasts > 0) {
                                        val highErr = if (useCelsius) stats.avgHighError / 1.8 else stats.avgHighError
                                        val lowErr = if (useCelsius) stats.avgLowError / 1.8 else stats.avgLowError
                                        append(
                                            "${source.displayName}: High ±%.1f°%s, Low ±%.1f°%s".format(
                                                highErr,
                                                formatBias(stats.highBias, useCelsius),
                                                lowErr,
                                                formatBias(stats.lowBias, useCelsius),
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
                rainAdapter.setItems(allRainDaily)
            } catch (e: Exception) {
                android.util.Log.e("StatisticsActivity", "Error loading statistics", e)
                findViewById<TextView>(R.id.stats_summary_text).text =
                    "Error loading statistics: ${e.message}"
            }
        }
    }

    private fun formatBias(bias: Double, useCelsius: Boolean): String {
        val displayBias = if (useCelsius) bias / 1.8 else bias
        val absBias = kotlin.math.abs(displayBias)
        val threshold = if (useCelsius) 0.5 / 1.8 else 0.5
        return when {
            absBias < threshold -> ""
            displayBias > 0 -> {
                if (useCelsius) " (%.1f° low)".format(absBias) else " (%d° low)".format(absBias.toInt())
            }
            else -> {
                if (useCelsius) " (%.1f° high)".format(absBias) else " (%d° high)".format(absBias.toInt())
            }
        }
    }
}
