package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class DailyRainLabelsTest {

    private val originalLocale = Locale.getDefault()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 6, 8)

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // ---- thresholds ----

    @Test
    fun thresholdScalesWithDistance() {
        assertEquals(1, DailyRainLabels.getMinimumPrecipProbabilityDay(0))
        assertEquals(5, DailyRainLabels.getMinimumPrecipProbabilityDay(1))
        assertEquals(9, DailyRainLabels.getMinimumPrecipProbabilityDay(2))
        // Night mirrors day.
        assertEquals(9, DailyRainLabels.getMinimumPrecipProbabilityNight(2))
    }

    // ---- rain-label font scale ----

    @Test
    fun probabilityScaleFactorStepsUpWithChance() {
        assertEquals(0.3f, DailyRainLabels.precipProbabilityScaleFactor(1), 1e-6f)
        assertEquals(0.7f, DailyRainLabels.precipProbabilityScaleFactor(15), 1e-6f)
        assertEquals(0.9f, DailyRainLabels.precipProbabilityScaleFactor(50), 1e-6f)
        assertEquals(1.0f, DailyRainLabels.precipProbabilityScaleFactor(90), 1e-6f)
    }

    @Test
    fun historyFontScaleIsProbabilityOnly() {
        // Past days ignore the distance term entirely: the scale is exactly the probability factor
        // regardless of the (meaningless for history) daysFromToday value.
        assertEquals(
            DailyRainLabels.precipProbabilityScaleFactor(15),
            DailyRainLabels.rainLabelFontScale(isPastDate = true, precipProbability = 15, daysFromToday = -1),
            1e-6f,
        )
        assertEquals(
            DailyRainLabels.rainLabelFontScale(isPastDate = true, precipProbability = 15, daysFromToday = -1),
            DailyRainLabels.rainLabelFontScale(isPastDate = true, precipProbability = 15, daysFromToday = -9),
            1e-6f,
        )
    }

    @Test
    fun futureFontScaleShrinksWithDistance() {
        val near = DailyRainLabels.rainLabelFontScale(isPastDate = false, precipProbability = 15, daysFromToday = 1)
        val far = DailyRainLabels.rainLabelFontScale(isPastDate = false, precipProbability = 15, daysFromToday = 6)
        assertEquals(true, far < near)
        // A near-certain day is barely distance-shrunk (probFraction≈1 zeroes the distance term).
        val certainFar = DailyRainLabels.rainLabelFontScale(isPastDate = false, precipProbability = 100, daysFromToday = 6)
        assertEquals(DailyRainLabels.precipProbabilityScaleFactor(100), certainFar, 1e-6f)
    }

    // ---- formatPrecipAmount ----

    @Test
    fun formatsInchesForUsLocale() {
        Locale.setDefault(Locale.US)
        assertEquals(".079in", DailyRainLabels.formatPrecipAmount(2f))    // 2mm ≈ 0.0787in, 3-dp
        assertEquals("1in", DailyRainLabels.formatPrecipAmount(25.4f))    // exactly 1 inch
    }

    @Test
    fun formatsMillimetersForMetricLocale() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("2mm", DailyRainLabels.formatPrecipAmount(2f))
        assertEquals("12mm", DailyRainLabels.formatPrecipAmount(12.4f))
    }

    // ---- buildDailyRainLabel ----

    @Test
    fun pastDayShowsObservedAmount() {
        Locale.setDefault(Locale.FRANCE)
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.minusDays(1),
            today = today,
            isPastDate = true,
            precipAmountMm = 99f, // forecast amount must be ignored for past days
            dayPrecipProbability = 80,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = 3f,
        )
        assertEquals("3mm", label)
    }

    @Test
    fun pastDayWithNoObservedRainShowsForecastChance() {
        // No measurable rain fell, but the forecast chance must stay visible in history
        // instead of silently vanishing when the day turns into the past.
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.minusDays(1),
            today = today,
            isPastDate = true,
            precipAmountMm = 5f, // forecast amount must be ignored for past days
            dayPrecipProbability = 80,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertEquals("80%", label)
    }

    @Test
    fun pastDayWithNoObservedRainAndZeroChanceIsNull() {
        // Dry, zero-chance history stays clean (no clutter of "0%").
        assertNull(
            DailyRainLabels.buildDailyRainLabel(
                date = today.minusDays(1),
                today = today,
                isPastDate = true,
                precipAmountMm = null,
                dayPrecipProbability = 0,
                allowTodayRainChanceLabel = true,
                observedPrecipAmountMm = null,
            ),
        )
        assertNull(
            DailyRainLabels.buildDailyRainLabel(
                date = today.minusDays(1),
                today = today,
                isPastDate = true,
                precipAmountMm = null,
                dayPrecipProbability = null,
                allowTodayRainChanceLabel = true,
                observedPrecipAmountMm = null,
            ),
        )
    }

    @Test
    fun todayShowsAmountWhenHighProbability() {
        Locale.setDefault(Locale.FRANCE)
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today,
            today = today,
            isPastDate = false,
            precipAmountMm = 4f,
            dayPrecipProbability = 96,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertEquals("4mm", label)
    }

    @Test
    fun todayFallsBackToProbabilityWhenAllowed() {
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today,
            today = today,
            isPastDate = false,
            precipAmountMm = null,
            dayPrecipProbability = 40,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertEquals("40%", label)
    }

    @Test
    fun futureSuppressedBelowDistanceThreshold() {
        // 3 days out → threshold = 4*3+1 = 13; 10% is below it.
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.plusDays(3),
            today = today,
            isPastDate = false,
            precipAmountMm = 2f,
            dayPrecipProbability = 10,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertNull(label)
    }

    @Test
    fun futureShowsProbabilityAboveThreshold() {
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.plusDays(3),
            today = today,
            isPastDate = false,
            precipAmountMm = null,
            dayPrecipProbability = 60,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertEquals("60%", label)
    }

    @Test
    fun futureShowsAmountAtVeryHighProbability() {
        Locale.setDefault(Locale.FRANCE)
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.plusDays(2),
            today = today,
            isPastDate = false,
            precipAmountMm = 6f,
            dayPrecipProbability = 100,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertEquals("6mm", label)
    }

    // ---- buildNightRainLabel ----

    @Test
    fun nightPastShowsObservedAmount() {
        Locale.setDefault(Locale.FRANCE)
        val label = DailyRainLabels.buildNightRainLabel(
            date = today.minusDays(1),
            today = today,
            isPastDate = true,
            nightPrecipProbability = 50,
            observedNightPrecipMm = 2f,
        )
        assertEquals("2mm", label)
    }

    @Test
    fun nightPastWithNoObservedRainShowsForecastChance() {
        // Reported case: NWS forecast 15% night chance, no measurable rain fell — the label
        // must stay as "15%" in history instead of disappearing.
        assertEquals(
            "15%",
            DailyRainLabels.buildNightRainLabel(
                date = today.minusDays(1),
                today = today,
                isPastDate = true,
                nightPrecipProbability = 15,
                observedNightPrecipMm = null,
            ),
        )
        // Zero/absent chance with no observed rain stays blank.
        assertNull(
            DailyRainLabels.buildNightRainLabel(
                date = today.minusDays(1),
                today = today,
                isPastDate = true,
                nightPrecipProbability = 0,
                observedNightPrecipMm = null,
            ),
        )
    }

    @Test
    fun nightFutureGatedByThreshold() {
        // 2 days out → threshold 9; 5% suppressed, 30% shown.
        assertNull(
            DailyRainLabels.buildNightRainLabel(
                date = today.plusDays(2), today = today, isPastDate = false,
                nightPrecipProbability = 5, observedNightPrecipMm = null,
            ),
        )
        assertEquals(
            "30%",
            DailyRainLabels.buildNightRainLabel(
                date = today.plusDays(2), today = today, isPastDate = false,
                nightPrecipProbability = 30, observedNightPrecipMm = null,
            ),
        )
    }

    // ---- calculateDayNightPrecipProbabilities ----

    private fun hour(date: LocalDate, h: Int, prob: Int, source: String = WeatherSource.NWS.id): HourlyForecast {
        val ms = date.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        return HourlyForecast(dateTime = ms, temperature = 60f, condition = "Rain", precipProbability = prob, source = source)
    }

    @Test
    fun dayNightWindowsTakeMaxPerWindow() {
        val target = today
        val hourly = listOf(
            hour(target, 9, 20),   // day
            hour(target, 15, 70),  // day  -> dayMax 70
            hour(target, 22, 40),  // night
            hour(target.plusDays(1), 5, 90), // night (before 8am) -> nightMax 90
        )
        val result = DailyRainLabels.calculateDayNightPrecipProbabilities(hourly, target, WeatherSource.NWS.id, zoneId = zone)
        assertEquals(70, result.dayMax)
        assertEquals(90, result.nightMax)
    }

    @Test
    fun fallsBackToGenericGapWhenNoSourceRows() {
        val target = today
        val hourly = listOf(
            hour(target, 15, 55, source = WeatherSource.GENERIC_GAP.id),
        )
        // Display source has no rows → fall back to GENERIC_GAP.
        val result = DailyRainLabels.calculateDayNightPrecipProbabilities(hourly, target, WeatherSource.NWS.id, zoneId = zone)
        assertEquals(55, result.dayMax)
        assertNull(result.nightMax)
    }

    // ---- resolveDailyLabelPrecip (Android/desktop parity for the daily rain %) ----

    @Test
    fun nwsUsesHourlyWindowMaxLikeOtherSources() {
        // NWS shown as NWS gets NO special treatment: the hourly 8am-8pm window max wins over NWS's
        // native period chance. (Deliberate reversal of the old NWS-direct branch — NWS periods run
        // 6am/6pm, so trusting them made the effective night cutoff 6am instead of the app-wide 8am
        // and dropped 6-8am rain from "tonight".)
        val hourly = listOf(hour(today, 14, 2))
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 15,
            nighttimePrecipProbability = 8,
            precipProbability = 99,
            hourly = hourly,
            targetDate = today,
            zoneId = zone,
        )
        assertEquals(2, resolved.dayPrecip)
        assertEquals(8, resolved.nightPrecip) // no hourly night rows -> period fallback
    }

    @Test
    fun nightWindowIncludesEarlyMorningHoursNwsPeriodExcludes() {
        // Regression (2026-07-04): NWS hourly said 14% at 7am but the night label showed NWS's
        // "Tonight" period chance of 9% — NWS periods end at 6am, orphaning 6-8am rain. The night
        // window max (8pm-8am) must win over the period field.
        val hourly = listOf(
            hour(today.plusDays(1), 5, 9),
            hour(today.plusDays(1), 7, 14),
        )
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 0,
            nighttimePrecipProbability = 9,
            precipProbability = null,
            hourly = hourly,
            targetDate = today,
            zoneId = zone,
        )
        assertEquals(14, resolved.nightPrecip)
    }

    @Test
    fun fallsBackToDailyPrecipWhenHourlyAndPeriodChanceMissing() {
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = null,
            nighttimePrecipProbability = null,
            precipProbability = 30,
            hourly = emptyList(),
            targetDate = today,
            zoneId = zone,
        )
        assertEquals(30, resolved.dayPrecip)
        assertNull(resolved.nightPrecip)
    }

    @Test
    fun nonNwsUsesHourlyWindowMaxWithPeriodFallback() {
        // Open-Meteo shown as Open-Meteo: not the direct-NWS path → hourly 8am-8pm max wins.
        val hourly = listOf(
            hour(today, 15, 60, source = WeatherSource.OPEN_METEO.id),
            hour(today, 22, 40, source = WeatherSource.OPEN_METEO.id),
        )
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = 99, // period fields ignored when hourly has rows
            nighttimePrecipProbability = 99,
            precipProbability = null,
            hourly = hourly,
            targetDate = today,
            zoneId = zone,
        )
        assertEquals(60, resolved.dayPrecip)
        assertEquals(40, resolved.nightPrecip)
    }

    @Test
    fun pastDayReturnsPeriodFieldsForIconNotHourly() {
        // Past days label from observed amounts; these resolved values only feed the icon, and must
        // mirror Android's past-day path (period fields, no hourly recompute).
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = true,
            displaySourceId = WeatherSource.OPEN_METEO.id,
            daytimePrecipProbability = 12,
            nighttimePrecipProbability = 7,
            precipProbability = 50,
            hourly = listOf(hour(today.minusDays(1), 15, 90, source = WeatherSource.OPEN_METEO.id)),
            targetDate = today.minusDays(1),
            zoneId = zone,
        )
        assertEquals(12, resolved.dayPrecip)
        assertEquals(7, resolved.nightPrecip)
    }

    @Test
    fun pastNwsNightOnlyChanceDoesNotLeakIntoDaytimeLabel() {
        // Regression: a past NWS day with a night-only chance (daytime=null, night=15,
        // precipProbability=15). The isPast branch must NOT apply the `?: precipProbability` daytime
        // fallback, so the night chance does not surface as a spurious daytime label on the bar.
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = true,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = null,
            nighttimePrecipProbability = 15,
            precipProbability = 15,
            hourly = emptyList(),
            targetDate = today.minusDays(1),
            zoneId = zone,
        )
        assertNull(resolved.dayPrecip)
        assertEquals(15, resolved.nightPrecip)
    }

    @Test
    fun pastDayPrefersStoredSnapshotOverRawPeriodFields() {
        // Once a day is history, the label should replay the value snapshotted into daily_history
        // while the day was still live (e.g. 14%, the hourly window max) rather than falling back to
        // NWS's raw period fields (9%), which is exactly the bug this storage feature fixes.
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = true,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 0,
            nighttimePrecipProbability = 9,
            precipProbability = null,
            hourly = emptyList(),
            targetDate = today.minusDays(1),
            zoneId = zone,
            storedDayPrecipChance = 2,
            storedNightPrecipChance = 14,
        )
        assertEquals(2, resolved.dayPrecip)
        assertEquals(14, resolved.nightPrecip)
    }

    @Test
    fun pastDayFallsBackToPeriodFieldsWhenNoStoredSnapshot() {
        // Rows written before this feature existed have null stored chances; must fall back to the
        // legacy raw period fields rather than surfacing null/blank labels.
        val resolved = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = true,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 12,
            nighttimePrecipProbability = 9,
            precipProbability = null,
            hourly = emptyList(),
            targetDate = today.minusDays(1),
            zoneId = zone,
            storedDayPrecipChance = null,
            storedNightPrecipChance = null,
        )
        assertEquals(12, resolved.dayPrecip)
        assertEquals(9, resolved.nightPrecip)
    }

    @Test
    fun resolveLiveDayNightChanceMatchesNonPastResolveDailyLabelPrecip() {
        // Anti-drift: the function used to snapshot storage values must be identical to what the
        // live (non-past) label path computes, for the same inputs.
        val hourly = listOf(
            hour(today, 14, 2),
            hour(today.plusDays(1), 7, 14, source = WeatherSource.NWS.id),
        )
        val live = DailyRainLabels.resolveLiveDayNightChance(
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 15,
            nighttimePrecipProbability = 9,
            precipProbability = 99,
            hourly = hourly,
            targetDate = today,
            zoneId = zone,
        )
        val viaResolve = DailyRainLabels.resolveDailyLabelPrecip(
            isPast = false,
            displaySourceId = WeatherSource.NWS.id,
            daytimePrecipProbability = 15,
            nighttimePrecipProbability = 9,
            precipProbability = 99,
            hourly = hourly,
            targetDate = today,
            zoneId = zone,
        )
        assertEquals(live, viaResolve)
        assertEquals(2, live.dayPrecip)
        assertEquals(14, live.nightPrecip)
    }
}
