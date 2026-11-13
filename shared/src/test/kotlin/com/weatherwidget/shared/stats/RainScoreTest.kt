package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime

@Category(ShortDuration::class)
class RainScoreTest {
    private val day = LocalDate.of(2026, 11, 13)
    private fun h(hour: Int): LocalDateTime = day.atTime(hour, 0)
    private fun allHours(amount: (Int) -> Float): Map<LocalDateTime, Float> = (0..23).associate { h(it) to amount(it) }

    @Test
    fun `a 6-hour shower among 18 measured-dry hours counts in full`() {
        val actual = allHours { if (it in 10..15) 2f else 0f }
        val pred = allHours { if (it in 10..15) 1f else 0f }

        val paired = RainScore.pair(pred, actual)
        println("paired=$paired")

        assertEquals(24, paired.hours)
        assertTrue(paired.counted)
        assertEquals(12f, paired.actualMm, 1e-4f) // the 6 wet hours' sum
        assertEquals(6f, paired.predMm, 1e-4f)
        assertTrue(paired.isRainDay)
    }

    @Test
    fun `a hour on one side only is unpaired, so a coverage gap is not forecast error`() {
        val actual = allHours { 0f } + (h(14) to 5f)
        val pred = allHours { 0f } - h(14) // forecast holds no amount for 14:00

        val paired = RainScore.pair(pred, actual)

        assertEquals(23, paired.hours)
        assertEquals(0f, paired.actualMm, 0f) // 14:00's 5 mm is not charged to the forecast
    }

    @Test
    fun `a null forecast hour is not a 0 - only the source that forecast 0 is penalized`() {
        val actual = allHours { if (it == 14) 5f else 0f }
        val missing = allHours { 0f } - h(14)
        val forecastDry = allHours { 0f }

        assertEquals(0f, RainScore.pair(missing, actual).errorMm, 0f)
        assertEquals(-5f, RainScore.pair(forecastDry, actual).errorMm, 0f)
    }

    @Test
    fun `fewer than 20 paired hours does not count`() {
        val actual = (0..18).associate { h(it) to 0f }
        assertFalse(RainScore.pair(allHours { 0f }, actual).counted)
        assertTrue(RainScore.pair(allHours { 0f }, actual + (h(19) to 0f)).counted)
    }

    @Test
    fun `the star compares sources on the hours they all share`() {
        val actual = allHours { when (it) { 9 -> 1f; 14 -> 5f; else -> 0f } }
        val skipsWetHour = allHours { 0f } - h(14) // own hours: |-1|
        val forecastBoth = allHours { when (it) { 9 -> 1f; 14 -> 3f; else -> 0f } } // own hours: |-2|

        val result = RainScore.compareDay(
            mapOf(WeatherSource.GOOGLE_WEATHER to skipsWetHour, WeatherSource.NWS to forecastBoth),
            actual,
        )

        assertEquals(setOf(WeatherSource.NWS), result.closest)
        assertEquals(23, result.commonHours)
        assertEquals(1f, result.actualMm!!, 0f)
    }

    @Test
    fun `ties within 0_01 inch share the star`() {
        val actual = allHours { if (it == 12) 10f else 0f }
        val result = RainScore.compareDay(
            mapOf(
                WeatherSource.NWS to allHours { if (it == 12) 10.2f else 0f },
                WeatherSource.GOOGLE_WEATHER to allHours { if (it == 12) 9.9f else 0f },
                WeatherSource.OPEN_METEO to allHours { if (it == 12) 7f else 0f },
            ),
            actual,
        )
        assertEquals(setOf(WeatherSource.NWS, WeatherSource.GOOGLE_WEATHER), result.closest)
    }

    @Test
    fun `no star on a dry day or with a single source`() {
        val dry = allHours { 0f }
        val twoDry = RainScore.compareDay(mapOf(WeatherSource.NWS to dry, WeatherSource.GOOGLE_WEATHER to dry), dry)
        assertTrue(twoDry.closest.isEmpty())

        val wet = allHours { if (it == 3) 4f else 0f }
        assertTrue(RainScore.compareDay(mapOf(WeatherSource.NWS to wet), wet).closest.isEmpty())
    }

    @Test
    fun `a day nobody can compare reports the most hours any source had`() {
        val actual = (0..4).associate { h(it) to 0f }
        val result = RainScore.compareDay(mapOf(WeatherSource.NWS to allHours { 0f }), actual)
        assertNull(result.actualMm)
        assertEquals(5, result.commonHours)
    }

    @Test
    fun `dry-vs-dry days count for the hit rate but not the rain-day error`() {
        val score = RainScore.score(
            WeatherSource.NWS,
            listOf(
                RainScore.Paired(predMm = 0f, actualMm = 0f, hours = 24), // dry hit
                RainScore.Paired(predMm = 4f, actualMm = 6f, hours = 24), // wet hit, -2
                RainScore.Paired(predMm = 0f, actualMm = 1f, hours = 24), // missed rain, -1
                RainScore.Paired(predMm = 9f, actualMm = 0f, hours = 10), // not counted
            ),
        )
        println("score=$score")
        assertEquals(3, score.countedDays)
        assertEquals(2, score.rainDays)
        assertEquals(1.5f, score.meanAbsErrorMm!!, 1e-4f)
        assertEquals(-1.5f, score.biasMm!!, 1e-4f) // forecast ran dry
        assertEquals(2f / 3f, score.hitRate!!, 1e-4f)
    }

    @Test
    fun `ranking puts the smallest rain-day error first and too-few-rain-day sources last`() {
        fun s(source: WeatherSource, rainDays: Int, mae: Float?) = RainSourceScore(source, 10, rainDays, mae, 0f, 0.5f)
        val ranked = RainScore.rank(
            listOf(
                s(WeatherSource.NWS, rainDays = 5, mae = 3f),
                s(WeatherSource.SILURIAN, rainDays = 2, mae = 0.1f), // best error, too few rain days
                s(WeatherSource.GOOGLE_WEATHER, rainDays = 4, mae = 1f),
            ),
        )
        assertEquals(
            listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS, WeatherSource.SILURIAN),
            ranked.map { it.source },
        )
    }
}
