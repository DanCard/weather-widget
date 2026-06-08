package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.WeatherSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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
    fun pastDayWithNoObservedRainIsNull() {
        val label = DailyRainLabels.buildDailyRainLabel(
            date = today.minusDays(1),
            today = today,
            isPastDate = true,
            precipAmountMm = 5f,
            dayPrecipProbability = 80,
            allowTodayRainChanceLabel = true,
            observedPrecipAmountMm = null,
        )
        assertNull(label)
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
}
