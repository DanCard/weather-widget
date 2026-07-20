package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading

/**
 * Real NWS rows captured from Samsung SM-F936U1 (RFCT71FR9NT) at 2026-07-19 21:14 local, spanning
 * the 72h context window around that render. Backing TSVs live in (tab-separated: station names contain commas)
 * `shared/src/test/resources/device-blend/`.
 *
 * Loaded from resources rather than inlined as Kotlin literals: 1672 `ObservationReading(...)`
 * constructor calls in one initializer blows the JVM's 64 KB method-size limit on `<clinit>`.
 */
object DeviceBlendFixture {

    private fun rows(name: String): List<Map<String, String>> {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-blend/$name")) {
            "missing test resource /device-blend/$name"
        }.bufferedReader().readText()
        val lines = text.trim().lines()
        val header = lines.first().split("\t")
        return lines.drop(1).map { line -> header.zip(line.split("\t")).toMap() }
    }

    val observations: List<ObservationReading> by lazy {
        rows("observations.tsv").map { r ->
            ObservationReading(
                stationId = r.getValue("stationId"),
                stationName = r.getValue("stationName"),
                timestamp = r.getValue("timestamp").toLong(),
                temperature = r.getValue("temperature").toFloat(),
                condition = r.getValue("condition"),
                locationLat = r.getValue("locationLat").toDouble(),
                locationLon = r.getValue("locationLon").toDouble(),
                distanceKm = r.getValue("distanceKm").toFloat(),
                stationType = r.getValue("stationType"),
                api = r.getValue("api"),
                fetchedAt = r.getValue("fetchedAt").toLong(),
                qcFailed = r.getValue("qcFailed") == "1",
            )
        }
    }

    val hourlyForecasts: List<HourlyForecast> by lazy {
        rows("hourly.tsv").map { r ->
            HourlyForecast(
                dateTime = r.getValue("dateTime").toLong(),
                temperature = r.getValue("temperature").toFloat(),
                condition = r.getValue("condition"),
                source = r.getValue("source"),
                fetchedAt = r.getValue("fetchedAt").toLong(),
                locationLat = r.getValue("locationLat").toDouble(),
                locationLon = r.getValue("locationLon").toDouble(),
            )
        }
    }
}
