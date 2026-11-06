package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The Forecast History "All sources for <day>" rule (plans/261105-all-sources-day-comparison.md). */
@Category(ShortDuration::class)
class DaySourceComparisonTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = LocalDateTime.of(2026, 11, 5, 10, 0)
    private val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    private val tomorrow = LocalDate.of(2026, 11, 6)
    private val lat = 37.422
    private val lon = -122.084
    private val minute = 60_000L
    private val hour = 3_600_000L

    private fun stored(
        source: WeatherSource,
        high: Float?,
        low: Float? = 50f,
        lastConfirmedAt: Long = nowMs - 30 * minute,
        fetchedAt: Long = lastConfirmedAt,
        rowLat: Double = lat,
        rowLon: Double = lon,
        precip: Int? = null,
        dayMax: Int? = null,
        nightMax: Int? = null,
        peakAt: Long? = null,
        noonCloud: Int? = null,
        amountMm: Float? = null,
        climateNormal: Boolean = false,
    ): DaySourceComparison.StoredRow {
        val f = DailyForecast(
            date = tomorrow.toString(), highTemp = high, lowTemp = low, condition = "Cloudy",
            precipProbability = precip, precipAmountMm = amountMm, isClimateNormal = climateNormal,
            source = source.id, noonCloudPercent = noonCloud, hourlyDayPrecipMax = dayMax,
            hourlyNightPrecipMax = nightMax, hourlyDayPrecipPeakAt = peakAt,
            fetchedAt = fetchedAt, lastConfirmedAt = lastConfirmedAt,
        )
        val s = DailyForecastSnapshot(
            date = f.date, highTemp = high, lowTemp = low, condition = f.condition,
            precipProbability = precip, precipAmountMm = amountMm, fetchedAt = fetchedAt,
            lastConfirmedAt = lastConfirmedAt,
        )
        return DaySourceComparison.StoredRow(f, s, rowLat, rowLon)
    }

    private fun build(
        sources: List<WeatherSource>,
        stored: List<DaySourceComparison.StoredRow>,
        hourly: List<HourlyForecast> = emptyList(),
        bias: Map<String, Double> = emptyMap(),
        date: LocalDate = tomorrow,
    ) = DaySourceComparison.build(
        date = date, now = now, lat = lat, lon = lon, sources = sources, stored = stored,
        history = emptyList(), hourly = hourly, highBias = bias, use24HourClock = false, zone = zone,
    ).also { rows ->
        println("DaySourceComparisonTest rows: " + rows.joinToString { "${it.source.id}=${it.high}/${it.low} day=${it.dayRainPercent} peak=${it.dayPeakLabel} cloud=${it.noonCloudPercent}" })
    }

    @Test
    fun `sorts by high descending, ties keep the source order, a source without a row comes last`() {
        val sources = listOf(
            WeatherSource.TOMORROW_IO, WeatherSource.NWS, WeatherSource.OPEN_METEO,
            WeatherSource.WEATHER_API, WeatherSource.GOOGLE_WEATHER,
        )
        val rows = build(
            sources,
            listOf(
                stored(WeatherSource.NWS, 66f),
                stored(WeatherSource.OPEN_METEO, 67f),
                stored(WeatherSource.WEATHER_API, 67f),
                stored(WeatherSource.GOOGLE_WEATHER, 68f),
            ),
        )
        assertEquals(
            listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API, WeatherSource.NWS, WeatherSource.TOMORROW_IO),
            rows.map { it.source },
        )
        val missing = rows.last()
        assertFalse(missing.hasForecast)
        assertNull(missing.high)
        assertNull(missing.lastConfirmedAt)
    }

    @Test
    fun `newest fetch wins over an older one with a higher high`() {
        val rows = build(
            listOf(WeatherSource.OPEN_METEO),
            listOf(
                stored(WeatherSource.OPEN_METEO, 75f, lastConfirmedAt = nowMs - 10 * hour),
                stored(WeatherSource.OPEN_METEO, 64f, lastConfirmedAt = nowMs - 1 * hour),
            ),
        )
        assertEquals(64f, rows.single().high)
    }

    @Test
    fun `climate normal and generic gap rows never stand for a source`() {
        val rows = build(
            listOf(WeatherSource.OPEN_METEO, WeatherSource.GENERIC_GAP),
            listOf(
                stored(WeatherSource.OPEN_METEO, 60f, climateNormal = true),
                stored(WeatherSource.GENERIC_GAP, 61f),
            ),
        )
        assertEquals(listOf(WeatherSource.OPEN_METEO), rows.map { it.source })
        assertFalse(rows.single().hasForecast)
    }

    @Test
    fun `a newer row at a GPS-excursion fragment does not displace the nearest site's row`() {
        val rows = build(
            listOf(WeatherSource.NWS),
            listOf(
                stored(WeatherSource.NWS, 66f, lastConfirmedAt = nowMs - 2 * hour),
                // 0.05° away: inside the coarse box, a different site.
                stored(WeatherSource.NWS, 80f, lastConfirmedAt = nowMs - 5 * minute, rowLat = lat + 0.05),
            ),
        )
        assertEquals(66f, rows.single().high)
        assertEquals(nowMs - 2 * hour, rows.single().lastConfirmedAt)
    }

    @Test
    fun `rain uses the widget's stored hourly max over the provider daily chance, with the peak hour above 10 percent`() {
        val peak = tomorrow.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = build(
            listOf(WeatherSource.OPEN_METEO, WeatherSource.GOOGLE_WEATHER),
            listOf(
                stored(WeatherSource.OPEN_METEO, 67f, precip = 20, dayMax = 60, nightMax = 25, peakAt = peak),
                stored(WeatherSource.GOOGLE_WEATHER, 66f, precip = 10, dayMax = 10, nightMax = 0, peakAt = peak),
            ),
        )
        val meteo = rows.first { it.source == WeatherSource.OPEN_METEO }
        assertEquals(60, meteo.dayRainPercent)
        assertEquals(25, meteo.nightRainPercent)
        assertEquals("2pm", meteo.dayPeakLabel)
        val google = rows.first { it.source == WeatherSource.GOOGLE_WEATHER }
        assertEquals(10, google.dayRainPercent)
        assertNull("10% is not above the peak-hour gate", google.dayPeakLabel)
    }

    @Test
    fun `noon cloud comes from the source's own hourly noon, else its forecast row`() {
        val noon = tomorrow.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val hourly = listOf(
            HourlyForecast(noon, 65f, "Cloudy", cloudCover = 80, source = WeatherSource.OPEN_METEO.id, fetchedAt = nowMs, locationLat = lat, locationLon = lon),
            // Another source's noon must not leak into Google's row.
            HourlyForecast(noon, 65f, "Cloudy", cloudCover = 5, source = WeatherSource.NWS.id, fetchedAt = nowMs, locationLat = lat, locationLon = lon),
        )
        val rows = build(
            listOf(WeatherSource.OPEN_METEO, WeatherSource.GOOGLE_WEATHER),
            listOf(
                stored(WeatherSource.OPEN_METEO, 67f, noonCloud = 30),
                stored(WeatherSource.GOOGLE_WEATHER, 66f, noonCloud = 35),
            ),
            hourly = hourly,
        )
        assertEquals(80, rows.first { it.source == WeatherSource.OPEN_METEO }.noonCloudPercent)
        assertEquals(35, rows.first { it.source == WeatherSource.GOOGLE_WEATHER }.noonCloudPercent)
    }

    @Test
    fun `age is from lastConfirmedAt, so a re-confirmed old fetch reads as fresh`() {
        val rows = build(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_WEATHER_MAP),
            listOf(
                stored(WeatherSource.NWS, 66f, fetchedAt = nowMs - 3 * 24 * hour, lastConfirmedAt = nowMs - 40 * minute),
                stored(WeatherSource.OPEN_WEATHER_MAP, 64f, lastConfirmedAt = nowMs - 26 * hour),
            ),
            // Stored as actual − forecast: OWM's forecast ran 2.1° above the actual high.
            bias = mapOf(WeatherSource.OPEN_WEATHER_MAP.id to -2.1),
        )
        val nws = DaySourceComparison.cells(rows.first { it.source == WeatherSource.NWS }, nowMs, useCelsius = false)
        assertEquals("40m", nws.age)
        assertFalse(nws.ageIsStale)
        assertEquals("", nws.bias)
        val owm = DaySourceComparison.cells(rows.first { it.source == WeatherSource.OPEN_WEATHER_MAP }, nowMs, useCelsius = false)
        assertEquals("1d", owm.age)
        assertTrue(owm.ageIsStale)
        assertEquals("+2.1°", owm.bias)
        assertEquals("OWM", owm.source)
        assertEquals("64°", owm.high)
    }

    @Test
    fun `cells print short names, rain with peak hour, and blank for a zero amount`() {
        val peak = tomorrow.atTime(13, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = build(
            listOf(WeatherSource.SILURIAN),
            listOf(stored(WeatherSource.SILURIAN, 65f, low = 49f, dayMax = 30, nightMax = 20, peakAt = peak, noonCloud = 45, amountMm = 0f)),
        )
        val c = DaySourceComparison.cells(rows.single(), nowMs, useCelsius = false)
        assertEquals("Silur", c.source)
        assertEquals("49°", c.low)
        assertEquals("30% 1pm", c.dayRain)
        assertEquals("20%", c.nightRain)
        assertEquals("45%", c.cloud)
        assertEquals("", c.amount)
    }

    @Test
    fun `bias reads like the accuracy card - a forecast that ran high is plus`() {
        // AccuracyPure.highError = actual − forecast. The card prints +1.0 as "(1.0° low)", −1.0 as "(1.0° high)".
        val rows = build(
            listOf(WeatherSource.NWS, WeatherSource.GOOGLE_WEATHER),
            listOf(stored(WeatherSource.NWS, 72f), stored(WeatherSource.GOOGLE_WEATHER, 70f)),
            bias = mapOf(WeatherSource.NWS.id to -1.0, WeatherSource.GOOGLE_WEATHER.id to 2.5),
        )
        val cells = rows.associate { it.source to DaySourceComparison.cells(it, nowMs, useCelsius = false).bias }
        assertEquals("+1.0°", cells[WeatherSource.NWS])
        assertEquals("\u22122.5°", cells[WeatherSource.GOOGLE_WEATHER])
    }

    @Test
    fun `formatAge boundaries`() {
        assertEquals("0m", DaySourceComparison.formatAge(30_000L))
        assertEquals("59m", DaySourceComparison.formatAge(59 * minute))
        assertEquals("1h", DaySourceComparison.formatAge(60 * minute))
        assertEquals("23h", DaySourceComparison.formatAge(24 * hour - 1))
        assertEquals("1d", DaySourceComparison.formatAge(24 * hour))
    }

    @Test
    fun `formatBias signs and celsius`() {
        assertEquals("+2.1°", DaySourceComparison.formatBias(2.1, useCelsius = false))
        assertEquals("−1.2°", DaySourceComparison.formatBias(-1.2, useCelsius = false))
        assertEquals("0.0°", DaySourceComparison.formatBias(-0.01, useCelsius = false))
        assertEquals("+1.0°", DaySourceComparison.formatBias(1.8, useCelsius = true))
    }

    @Test
    fun `amount column only when wide enough and some row has an amount`() {
        val withAmount = DaySourceComparison.Cells("A", "", "", "", "", "0.02in", "", "", "", false)
        val without = withAmount.copy(amount = "")
        assertTrue(DaySourceComparison.showAmountColumn(400f, listOf(withAmount, without)))
        assertFalse(DaySourceComparison.showAmountColumn(340f, listOf(withAmount)))
        assertFalse(DaySourceComparison.showAmountColumn(600f, listOf(without)))
    }

    @Test
    fun `load reads the day plus its night, skips generic gap as a source, and passes bias through`() = runBlocking {
        val calls = mutableListOf<String>()
        val host = object : DaySourceComparison.Host {
            override suspend fun visibleSources() = listOf(WeatherSource.NWS, WeatherSource.GENERIC_GAP)
            override suspend fun forecastRows(targetDateEpoch: Long, lat: Double, lon: Double): List<DaySourceComparison.StoredRow> {
                calls += "forecastRows $targetDateEpoch"
                return listOf(stored(WeatherSource.NWS, 66f))
            }
            override suspend fun dailyHistory(targetDateEpoch: Long, lat: Double, lon: Double): List<DailyHistory> = emptyList()
            override suspend fun hourly(startMs: Long, endMs: Long, lat: Double, lon: Double, sourceIds: List<String>): List<HourlyForecast> {
                calls += "hourly $startMs $endMs $sourceIds"
                return emptyList()
            }
            override suspend fun highBias(sources: List<WeatherSource>, lat: Double, lon: Double): Map<String, Double> {
                calls += "bias ${sources.map { it.id }}"
                return mapOf(WeatherSource.NWS.id to -0.3) // actual − forecast
            }
        }
        val rows = DaySourceComparison.load(host, tomorrow, lat, lon, now, zone, use24HourClock = false)
        println("DaySourceComparisonTest calls: $calls")
        assertEquals(listOf(WeatherSource.NWS), rows.map { it.source })
        assertEquals("shown as forecast − actual", 0.3, rows.single().highBias!!, 1e-9)
        assertEquals("forecastRows ${tomorrow.toEpochDay() * 86_400_000L}", calls[0])
        val start = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = tomorrow.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("hourly $start $end [NWS, Generic]", calls[1])
        assertEquals("bias [NWS]", calls[2])
    }
}
