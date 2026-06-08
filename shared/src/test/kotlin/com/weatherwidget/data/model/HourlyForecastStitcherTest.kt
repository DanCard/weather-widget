package com.weatherwidget.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyForecastStitcherTest {

    @Test
    fun `stitch fills missing cloud cover from history while keeping current values`() {
        val t1 = 1_000L
        val t2 = 2_000L

        val current = listOf(
            HourlyForecast(
                dateTime = t1,
                temperature = 70f,
                condition = "Clear",
                cloudCover = null,
                precipProbability = 10,
                precipAmountMm = 1.2f,
                source = "NWS",
                fetchedAt = 200L,
            ),
            HourlyForecast(
                dateTime = t2,
                temperature = 72f,
                condition = "Sunny",
                cloudCover = 20,
                precipProbability = 15,
                precipAmountMm = 0.5f,
                source = "NWS",
                fetchedAt = 200L,
            ),
        )

        val history = listOf(
            HourlyForecast(
                dateTime = t1,
                temperature = 68f,
                condition = "Cloudy",
                cloudCover = 88,
                precipProbability = 30,
                precipAmountMm = 3.4f,
                source = "NWS",
                fetchedAt = 100L,
            ),
            HourlyForecast(
                dateTime = 3_000L,
                temperature = 66f,
                condition = "Overcast",
                cloudCover = 100,
                source = "NWS",
                fetchedAt = 100L,
            ),
        )

        val stitched = HourlyForecastStitcher.stitch(current, history)

        assertEquals(3, stitched.size)
        assertEquals(t1, stitched[0].dateTime)
        val first = stitched[0]
        assertEquals(70f, first.temperature, 0.0f)
        assertEquals("Clear", first.condition)
        assertEquals(88, first.cloudCover)
        assertEquals(10, first.precipProbability)
        assertEquals(1.2f, first.precipAmountMm!!, 0.0f)

        assertEquals(t2, stitched[1].dateTime)
        assertEquals(20, stitched[1].cloudCover)

        assertEquals(3_000L, stitched[2].dateTime)
        assertEquals(100, stitched[2].cloudCover)
    }

    @Test
    fun `stitch repairs missing precip fields from history`() {
        val t1 = 1_000L
        val current = listOf(
            HourlyForecast(
                dateTime = t1,
                temperature = 70f,
                condition = "Clear",
                cloudCover = 10,
                precipProbability = null,
                precipAmountMm = null,
                source = "NWS",
                fetchedAt = 200L,
            ),
        )
        val history = listOf(
            HourlyForecast(
                dateTime = t1,
                temperature = 68f,
                condition = "Rain",
                cloudCover = 90,
                precipProbability = 80,
                precipAmountMm = 5.0f,
                source = "NWS",
                fetchedAt = 100L,
            ),
        )

        val stitched = HourlyForecastStitcher.stitch(current, history)
        val repaired = stitched.first()

        assertEquals(80, repaired.precipProbability)
        assertEquals(5.0f, repaired.precipAmountMm!!, 0.0f)
        assertEquals(10, repaired.cloudCover) // Current cloud cover still wins
    }

    @Test
    fun `stitch keeps cloud cover null when neither current nor history has it`() {
        val result = HourlyForecastStitcher.stitch(
            current = listOf(
                HourlyForecast(
                    dateTime = 1_000L,
                    temperature = 70f,
                    condition = "Clear",
                    source = "NWS",
                    fetchedAt = 200L,
                ),
            ),
            history = listOf(
                HourlyForecast(
                    dateTime = 1_000L,
                    temperature = 69f,
                    condition = "Cloudy",
                    source = "NWS",
                    fetchedAt = 100L,
                ),
            ),
        )

        assertEquals(1, result.size)
        assertNull(result[0].cloudCover)
    }
}
