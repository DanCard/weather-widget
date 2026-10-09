package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.graph.ForecastEvolutionGeometry.EvolutionPoint
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Category(ShortDuration::class)
class ForecastEvolutionCutoffTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val oct8 = LocalDate.of(2026, 10, 8)

    private fun at(date: LocalDate, h: Int, m: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun point(fetchedAt: Long, high: Float?, low: Float?) = EvolutionPoint(
        forecastDate = "2026-10-08",
        fetchedAt = fetchedAt,
        daysAhead = 0,
        highTemp = high,
        lowTemp = low,
        source = WeatherSource.OPEN_METEO,
    )

    private fun apply(points: List<EvolutionPoint>, highAt: Long?, lowAt: Long?) =
        ForecastEvolutionCutoff.apply(points, oct8, highAt, lowAt, zone)

    /** Fold 4, Oct 8, Open-Meteo: high reached 14:15, low 06:55. */
    @Test
    fun `fold oct 8 keeps only the pre-extreme fetches`() {
        val prior = point(at(oct8.minusDays(1), 17, 5), 77f, 58f)
        val early = point(at(oct8, 2, 29), 79.5f, 57.2f)
        val hindcast = point(at(oct8, 15, 50), 82.4f, 57.8f)
        val carried = point(at(oct8, 21, 4), 82.4f, 57.8f)

        val result = apply(listOf(prior, early, hindcast, carried), at(oct8, 14, 15), at(oct8, 6, 55))

        assertEquals(listOf(prior, early), result)
    }

    @Test
    fun `sides are cut independently`() {
        val afterLowBeforeHigh = point(at(oct8, 10), 81f, 57f)

        val result = apply(listOf(afterLowBeforeHigh), at(oct8, 14, 15), at(oct8, 6, 55))

        assertEquals(listOf(point(at(oct8, 10), 81f, null)), result)
    }

    @Test
    fun `fixed cutoff wins when the extreme came later - post-cutoff rows are carried copies`() {
        // Low reached 06:55; a 06:30 row's low is the writer's copy of the pre-06:00 value.
        val result = apply(listOf(point(at(oct8, 6, 30), 81f, 57f)), at(oct8, 14, 15), at(oct8, 6, 55))

        assertEquals(listOf(point(at(oct8, 6, 30), 81f, null)), result)
    }

    @Test
    fun `unknown extreme time falls back to the fixed cutoffs`() {
        val beforeLow = point(at(oct8, 5, 59), 80f, 56f)
        val midday = point(at(oct8, 12), 80f, 56f)
        val evening = point(at(oct8, 16), 80f, 56f)

        val result = apply(listOf(beforeLow, midday, evening), null, null)

        assertEquals(listOf(beforeLow, point(at(oct8, 12), 80f, null)), result)
    }

    @Test
    fun `a fetch at the exact extreme time is still a forecast`() {
        val atHigh = point(at(oct8, 14, 15), 82f, null)

        assertEquals(listOf(atHigh), apply(listOf(atHigh), at(oct8, 14, 15), null))
    }

    @Test
    fun `a future day is untouched`() {
        val points = listOf(point(at(oct8.minusDays(2), 21), 80f, 56f), point(at(oct8.minusDays(1), 22), 81f, 57f))

        assertEquals(points, apply(points, null, null))
    }
}
