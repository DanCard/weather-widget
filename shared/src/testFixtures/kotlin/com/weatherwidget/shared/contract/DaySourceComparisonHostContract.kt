package com.weatherwidget.shared.contract

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.DaySourceComparison
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Forecast History "All sources" table through each platform's real database reads
 * (`RoomDaySourceComparisonHost`, `DesktopDaySourceComparisonHost`) into the shared
 * [DaySourceComparison] runner. Same seeds, same rows: a host that drops a column, a site or a
 * fetch fails here. plans/261105-all-sources-day-comparison.md
 */
abstract class DaySourceComparisonHostContract {
    /** One `forecasts` row, written as given. */
    data class ForecastSeed(
        val source: String,
        val high: Float?,
        val low: Float?,
        val fetchedAt: Long,
        val lastConfirmedAt: Long = fetchedAt,
        val lat: Double = LAT,
        val lon: Double = LON,
        val precipProbability: Int? = null,
        val dayMax: Int? = null,
        val nightMax: Int? = null,
        val peakAt: Long? = null,
        val noonCloud: Int? = null,
        val amountMm: Float? = null,
        val isClimateNormal: Boolean = false,
    )

    interface Seed {
        fun forecast(row: ForecastSeed, targetDate: Long, dateOfPrediction: Long)
        fun hourly(row: HourlyForecast)
    }

    /** The platform's host over a fresh, empty database, showing [sources]. */
    protected abstract fun open(sources: List<WeatherSource>): Pair<DaySourceComparison.Host, Seed>

    private lateinit var host: DaySourceComparison.Host
    private lateinit var seed: Seed

    @Before
    fun openDatabase() {
        val (h, s) = open(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        host = h
        seed = s
    }

    private fun forecast(row: ForecastSeed) =
        seed.forecast(row, targetDate = DAY.toEpochDay() * DAY_MS, dateOfPrediction = TODAY.toEpochDay() * DAY_MS)

    @Test
    fun `newest row per source at the nearest site, sorted by high, with rain cloud amount and age`() = runBlocking {
        val peak = DAY.atTime(14, 0).atZone(ZONE).toInstant().toEpochMilli()
        // NWS: an older 70 and the newer 66 that stands.
        forecast(ForecastSeed("NWS", 70f, 52f, fetchedAt = NOW_MS - 10 * HOUR))
        forecast(ForecastSeed("NWS", 66f, 50f, fetchedAt = NOW_MS - 5 * HOUR, lastConfirmedAt = NOW_MS - 40 * MINUTE, precipProbability = 30))
        // NWS at a GPS-excursion fragment 0.05° away: newer, never shown.
        forecast(ForecastSeed("NWS", 80f, 60f, fetchedAt = NOW_MS - 5 * MINUTE, lat = LAT + 0.05))
        // Open-Meteo with the stored hourly summary the widget reads.
        forecast(
            ForecastSeed(
                "OPEN_METEO", 67f, 52f, fetchedAt = NOW_MS - 2 * HOUR, precipProbability = 20,
                dayMax = 60, nightMax = 25, peakAt = peak, noonCloud = 30, amountMm = 2.5f,
            ),
        )
        // Open-Meteo's stored hours, 8am to midnight: 0.1 mm each, so 8am–8pm sums to 1.2 mm while the
        // row's midnight-to-midnight total is 2.5 mm. The noon hour's cloud overrides the row's, as on the widget.
        for (h in 8..23) {
            seed.hourly(
                HourlyForecast(
                    DAY.atTime(h, 0).atZone(ZONE).toInstant().toEpochMilli(), 64f, "Cloudy",
                    precipProbability = when { h == 14 -> 60; h >= 20 -> 25; else -> 30 },
                    precipAmountMm = 0.1f, cloudCover = if (h == 12) 80 else 50, source = "OPEN_METEO",
                    fetchedAt = NOW_MS - 2 * HOUR, locationLat = LAT, locationLon = LON,
                ),
            )
        }
        // Silurian has only a climate normal: listed, with no forecast.
        forecast(ForecastSeed("SILURIAN", 61f, 45f, fetchedAt = NOW_MS - HOUR, isClimateNormal = true))

        val rows = DaySourceComparison.load(host, DAY, LAT, LON, NOW, ZONE, use24HourClock = false)
        println("DaySourceComparisonHostContract rows: $rows")

        assertEquals(listOf("OPEN_METEO", "NWS", "SILURIAN"), rows.map { it.source.id })
        val meteo = rows[0]
        assertEquals(67f, meteo.high)
        assertEquals(60, meteo.dayRainPercent)
        assertEquals(25, meteo.nightRainPercent)
        assertEquals("2pm", meteo.dayPeakLabel)
        assertEquals(80, meteo.noonCloudPercent)
        // The widget's 8am–8pm day amount, not the row's midnight-to-midnight 2.5 mm.
        assertEquals(1.2f, meteo.amountMm!!, 1e-4f)
        assertEquals(NOW_MS - 2 * HOUR, meteo.lastConfirmedAt)
        val nws = rows[1]
        assertEquals("no stored hours: no amount", null, nws.amountMm)
        assertEquals(66f, nws.high)
        assertEquals(50f, nws.low)
        assertEquals(NOW_MS - 40 * MINUTE, nws.lastConfirmedAt)
        assertEquals(null, rows[2].lastConfirmedAt)
    }

    companion object {
        const val LAT = 37.422
        const val LON = -122.084
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY_MS = 86_400_000L
        val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        val TODAY: LocalDate = LocalDate.of(2026, 11, 5)
        val DAY: LocalDate = TODAY.plusDays(1)
        val NOW: LocalDateTime = TODAY.atTime(10, 0)
        val NOW_MS: Long = NOW.atZone(ZONE).toInstant().toEpochMilli()
    }
}
