package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.parity.DailyParity
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.WeatherIconMapper
import com.weatherwidget.widget.DailyForecastGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Android half of the daily parity harness ([DailyParity]): the shared fixtures through
 * [DailyViewLogic.prepareGraphDays], written as text for `scripts/daily_parity_diff.py`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class AndroidDailyParityTest {

    private fun entity(r: DailyParity.Row) = ForecastEntity(
        targetDate = DailyParity.utcMidnight(r.date),
        dateOfPrediction = DailyParity.utcMidnight(
            java.time.Instant.ofEpochMilli(r.fetchedAt).atZone(DailyParity.ZONE).toLocalDate(),
        ),
        locationLat = DailyParity.LAT,
        locationLon = DailyParity.LON,
        highTemp = r.high,
        lowTemp = r.low,
        condition = r.condition,
        nativeDailyIconToken = r.iconToken,
        isClimateNormal = r.isClimateNormal,
        source = r.source,
        precipProbability = r.precip,
        daytimePrecipProbability = r.dayPrecip,
        nighttimePrecipProbability = r.nightPrecip,
        batchFetchedAt = r.fetchedAt,
        fetchedAt = r.fetchedAt,
        noonCloudPercent = r.noonCloud,
    )

    private fun hourlyEntity(h: com.weatherwidget.data.model.HourlyForecast) = HourlyForecastEntity(
        dateTime = h.dateTime,
        locationLat = h.locationLat ?: DailyParity.LAT,
        locationLon = h.locationLon ?: DailyParity.LON,
        temperature = h.temperature,
        condition = h.condition,
        source = h.source ?: "",
        precipProbability = h.precipProbability,
        cloudCover = h.cloudCover,
        precipAmountMm = h.precipAmountMm,
        fetchedAt = h.fetchedAt,
        cloudCoverLow = h.cloudCoverLow,
    )

    private fun column(day: DailyForecastGraphRenderer.DayData) = DailyParity.Column(
        date = day.date,
        barTop = day.solidLineHigh,
        solidLow = day.solidLineLow,
        fcstHigh = day.dashedLineHigh,
        fcstLow = day.dashedLineLow,
        priorHigh = day.snapshotHigh,
        priorLow = day.snapshotLow,
        ghost = day.ghostLineHigh,
        icon = day.iconRes?.let(WeatherIconMapper::iconResToName),
        priorIcon = day.snapshotIconRes?.let(WeatherIconMapper::iconResToName),
        noonCloud = day.cloudCoverRatioOverride?.let { Math.round(it * 100) },
        dayRain = day.rainData.dailyPrecipProbability,
        nightRain = day.rainData.nighttimePrecipProbability,
        rainLabel = day.rainData.dailyRainLabelText,
        peakLabel = day.rainData.dailyRainPeakLabelText,
        nightLabel = day.rainData.nightRainLabelText,
        priorRain = day.priorBarRainPercent,
        climate = day.isClimateNormal || day.isSourceGapFallback,
        promoted = day.solidIsForecastFallback,
        priorStale = day.snapshotIsStale,
        fcstFallback = day.forecastIsFallback,
        otherSite = day.actualsFromOtherSite,
    )

    @Test
    fun `android columns for the shared parity fixtures`() {
        val mismatched = mutableListOf<String>()
        for (fixture in DailyParity.FIXTURES) {
            val request = GraphDayRequest(
                now = fixture.now,
                centerDate = fixture.today,
                today = fixture.today,
                weatherByDate = fixture.newestRows().mapValues { entity(it.value) },
                forecastSnapshots = fixture.allRows().mapValues { (_, rows) -> rows.map(::entity) },
                numColumns = 11,
                displaySource = WeatherSource.fromId(fixture.displaySource),
                skipYesterday = false,
                skipHistory = false,
                hourlyForecasts = fixture.hourly.map(::hourlyEntity),
                dailyActuals = fixture.history.associateBy { LocalDate.ofEpochDay(it.date / 86_400_000L) },
                currentTemp = fixture.currentTemp,
                // Production (DailyGraphRenderer) passes the header's rain % and allows today's label;
                // the header % is stood in for by today's newest row.
                todayPrecipProbability = fixture.newestRows()[fixture.today]?.precip,
                allowTodayRainChanceLabel = true,
                todayLabel = "Today",
                centerLat = DailyParity.LAT,
                centerLon = DailyParity.LON,
            )
            val text = DailyParity.render(fixture, DailyViewLogic.prepareGraphDays(request).map(::column))
            DailyParity.write("android", fixture, text)
            if (text != DailyParity.expected(fixture)) mismatched += fixture.name
        }
        // Every fixture is written before failing, so the diff script sees them all.
        assertEquals(
            "android columns differ from the agreed ones (daily-parity/<fixture>.txt); " +
                "scripts/daily_parity_diff.py compares the platforms",
            emptyList<String>(),
            mismatched,
        )
    }
}
