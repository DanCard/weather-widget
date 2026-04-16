package com.weatherwidget.data.repository

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class ForecastRepositoryPhantomDayTest {

    @Test
    fun `removes future date with null high and valid low`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `keeps future date with both high and low`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (72f to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
        assertEquals(72f, map["2026-04-04"]!!.first)
    }

    @Test
    fun `keeps today with null high`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-03-28" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
    }

    @Test
    fun `keeps past date with null high`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-03-27" to (null to 65f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
    }

    @Test
    fun `removes multiple phantom days`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-04-04" to (null to 65f),
            "2026-04-05" to (null to 62f),
            "2026-03-30" to (75f to 55f),
        )
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertEquals(1, map.size)
        assertTrue(map.containsKey("2026-03-30"))
    }

    @Test
    fun `no-op on empty map`() {
        val today = LocalDate.parse("2026-03-28")
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertTrue(map.isEmpty())
    }
}
