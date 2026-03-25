package com.weatherwidget.perf

import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.testutil.TestData
import com.weatherwidget.util.ObservationBlender
import com.weatherwidget.widget.CurrentTemperatureResolver
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.system.measureTimeMillis
import kotlin.system.measureNanoTime

class TemperaturePipelineBenchmark {

    private val lat = 37.422
    private val lon = -122.084
    private val now = LocalDateTime.of(2026, 3, 23, 18, 50)
    private val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun benchmarkObservationBlendingAndResolution() {
        val stationCount = 10
        val pointsPerStation = 24 // 24 hours of data per station
        val hourlyCount = 48
        
        val observations = mutableListOf<ObservationEntity>()
        for (i in 0 until stationCount) {
            val stationId = "STATION_$i"
            val distance = 2.0f + i * 1.5f
            for (j in 0 until pointsPerStation) {
                observations.add(
                    TestData.observation(
                        stationId = stationId,
                        timestamp = nowMs - j * 3600_000L,
                        temperature = 60f + i + (j % 5),
                        distanceKm = distance,
                        api = WeatherSource.NWS.id
                    )
                )
            }
        }

        val hourlyForecasts = (0 until hourlyCount).map { i ->
            TestData.hourly(
                dateTime = now.minusHours(24).plusHours(i.toLong()).toString(),
                temperature = 65f + (i % 10),
                fetchedAt = nowMs - 3600_000L
            )
        }

        val iterations = 100
        val warmUp = 20
        
        // Warm up
        repeat(warmUp) {
            runPipeline(observations, hourlyForecasts)
        }

        val times = mutableListOf<Long>()
        repeat(iterations) {
            val nano = measureNanoTime {
                runPipeline(observations, hourlyForecasts)
            }
            times.add(nano)
        }

        val avgMs = times.average() / 1_000_000.0
        val minMs = times.minOrNull()!! / 1_000_000.0
        val maxMs = times.maxOrNull()!! / 1_000_000.0

        println("\n=== Temperature Pipeline Performance Benchmark ===")
        println("Stations: $stationCount, Obs/Station: $pointsPerStation, Hourly: $hourlyCount")
        println("Iterations: $iterations")
        println("Average Latency: ${"%.3f".format(avgMs)} ms")
        println("Min Latency:     ${"%.3f".format(minMs)} ms")
        println("Max Latency:     ${"%.3f".format(maxMs)} ms")
        println("==================================================\n")
    }

    private fun runPipeline(observations: List<ObservationEntity>, hourlyForecasts: List<HourlyForecastEntity>) {
        val graphStyleObs = ObservationBlender.resolveCurrentObservation(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySource = WeatherSource.NWS,
            userLat = lat,
            userLon = lon,
            now = now,
            lookbackHours = 12L,
            lookaheadHours = 2L
        )

        if (graphStyleObs != null) {
            CurrentTemperatureResolver.resolve(
                now = now,
                displaySource = WeatherSource.NWS,
                hourlyForecasts = hourlyForecasts,
                observedCurrentTemp = graphStyleObs.first,
                observedAt = graphStyleObs.second,
                storedDeltaState = null,
                currentLat = lat,
                currentLon = lon
            )
        }
    }
}
