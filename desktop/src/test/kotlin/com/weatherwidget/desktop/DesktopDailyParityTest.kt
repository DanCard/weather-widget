package com.weatherwidget.desktop

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyForecastSnapshot
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch
import com.weatherwidget.data.model.ResolvedView
import com.weatherwidget.shared.parity.DailyParity
import com.weatherwidget.shared.util.PartialForecastDays
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Desktop half of the daily parity harness ([DailyParity]): the shared fixtures through
 * [DesktopDailyForecastModel.build], written as text for `scripts/daily_parity_diff.py`.
 */
@Category(ShortDuration::class)
class DesktopDailyParityTest {

    private fun snapshotOf(fixture: DailyParity.Fixture): ForecastSnapshot {
        // DesktopWeatherDao.getDailyForecasts: each day's newest row from the newest batch's first day
        // on (no past rows), then today's row by PartialForecastDays.todayRow over every stored row for
        // today — which also supplies today when the batch starts at tomorrow (Silurian evenings).
        val firstShown = fixture.firstShownDate()
        val newest = fixture.newestRows()
            .filter { (date, r) -> firstShown == null || r.isClimateNormal || !date.isBefore(firstShown) }
            .toMutableMap()
        val batchToday = newest[fixture.today]?.takeIf { !it.isClimateNormal }
        val storedToday = fixture.rows.filter { it.source == fixture.displaySource && it.date == fixture.today }
            .filter { it.high != null || it.low != null }
        PartialForecastDays.todayRow(batchToday, storedToday, { it.high }, { it.low }, { it.fetchedAt })
            ?.let { newest[fixture.today] = it }
        val daily = newest.values.sortedBy { it.date }.map { r ->
            DailyForecast(
                date = r.date.toString(),
                highTemp = r.high,
                lowTemp = r.low,
                condition = r.condition,
                iconToken = r.iconToken,
                precipProbability = r.precip,
                isClimateNormal = r.isClimateNormal,
                source = r.source,
                daytimePrecipProbability = r.dayPrecip,
                nighttimePrecipProbability = r.nightPrecip,
                noonCloudPercent = r.noonCloud,
            )
        }
        val snapshots = fixture.allRows().mapKeys { it.key.toString() }.mapValues { (_, rows) ->
            rows.map { r ->
                DailyForecastSnapshot(
                    date = r.date.toString(),
                    highTemp = r.high,
                    lowTemp = r.low,
                    condition = r.condition,
                    iconToken = r.iconToken,
                    precipProbability = r.precip,
                    fetchedAt = r.fetchedAt,
                    daytimePrecipProbability = r.dayPrecip,
                    nighttimePrecipProbability = r.nightPrecip,
                )
            }
        }
        return ForecastSnapshot(
            raw = RawFetch(
                hourly = fixture.hourly,
                daily = daily,
                dailyActuals = fixture.history.associateBy {
                    java.time.LocalDate.ofEpochDay(it.date / 86_400_000L).toString()
                },
                dailySnapshots = snapshots,
            ),
            resolved = ResolvedView(currentTemp = fixture.currentTemp),
        )
    }

    private fun column(day: DesktopDailyDay) = DailyParity.Column(
        date = day.date,
        barTop = if (day.isToday) day.barTopHigh else day.solidHigh,
        solidLow = day.solidLow,
        fcstHigh = day.forecastHigh,
        fcstLow = day.forecastLow,
        priorHigh = day.snapshotHigh,
        priorLow = day.snapshotLow,
        ghost = day.ghostHigh,
        icon = day.iconName,
        priorIcon = day.snapshotIconName,
        noonCloud = day.cloudCoverRatio?.let { Math.round(it * 100) },
        dayRain = day.dayPrecipProbability,
        nightRain = day.nightPrecipProbability,
        rainLabel = day.dailyRainLabelText,
        peakLabel = day.dailyRainPeakLabelText,
        nightLabel = day.nightRainLabelText,
        priorRain = day.priorBarRainPercent,
        climate = day.isClimateNormal,
        promoted = day.solidIsForecastFallback,
        priorStale = day.snapshotIsStale,
        fcstFallback = day.forecastIsFallback,
        otherSite = day.actualsFromOtherSite,
    )

    @Test
    fun `desktop columns for the shared parity fixtures`() {
        val mismatched = mutableListOf<String>()
        for (fixture in DailyParity.FIXTURES) {
            val config = DesktopConfig(
                lat = DailyParity.LAT,
                lon = DailyParity.LON,
                label = "Parity",
                dateOffset = 0,
                // Enabled, or displaySource falls back to the first usable source and reads none of the rows.
                settings = DesktopSettings(weatherSource = fixture.displaySource, visibleSources = listOf(fixture.displaySource)),
            )
            val state = DesktopDailyForecastModel.build(
                config, snapshotOf(fixture), DesktopDailyForecastModel.dimensions(900, 300),
                fixture.now, currentTemp = fixture.currentTemp,
            )
            val text = DailyParity.render(fixture, state.days.map(::column))
            DailyParity.write("desktop", fixture, text)
            if (text != DailyParity.expected(fixture)) mismatched += fixture.name
        }
        // Every fixture is written before failing, so the diff script sees them all.
        assertEquals(
            "desktop columns differ from the agreed ones (daily-parity/<fixture>.txt); " +
                "scripts/daily_parity_diff.py compares the platforms",
            emptyList<String>(),
            mismatched,
        )
    }
}
