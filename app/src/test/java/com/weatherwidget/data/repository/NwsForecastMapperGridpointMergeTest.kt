package com.weatherwidget.data.repository

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class NwsForecastMapperGridpointMergeTest {

    private val today = LocalDate.parse("2026-04-30")

    private fun extremes(
        max: Map<String, Float> = emptyMap(),
        min: Map<String, Float> = emptyMap(),
    ) = NwsApi.DailyTemperatureExtremes(max, min)

    @Test
    fun `precedence - existing forecast values are not overwritten`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-05-01" to (70f to 50f),
        )
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(max = mapOf("2026-05-01" to 80f), min = mapOf("2026-05-01" to 55f)),
            today,
        )
        assertEquals(70f, map["2026-05-01"]!!.first)
        assertEquals(50f, map["2026-05-01"]!!.second)
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `upgrade terminal low-only day to full day`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-05-07" to (null to 48f),
        )
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(max = mapOf("2026-05-07" to 76f)),
            today,
        )
        assertEquals(76f, map["2026-05-07"]!!.first)
        assertEquals(48f, map["2026-05-07"]!!.second)
        assertTrue(changed.contains("2026-05-07"))

        // Critical: after merging, removePhantomFutureDays should keep this day.
        NwsForecastMapper.removePhantomFutureDays(map, today)
        assertTrue(map.containsKey("2026-05-07"))
        assertEquals(76f, map["2026-05-07"]!!.first)
    }

    @Test
    fun `brand-new 8th day is added when no existing entry`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(
                max = mapOf("2026-05-07" to 76f),
                min = mapOf("2026-05-07" to 52f),
            ),
            today,
        )
        assertEquals(76f, map["2026-05-07"]!!.first)
        assertEquals(52f, map["2026-05-07"]!!.second)
        assertTrue(changed.contains("2026-05-07"))
    }

    @Test
    fun `horizon cap rejects dates beyond today plus 7`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(max = mapOf("2026-05-08" to 80f)), // today + 8
            today,
        )
        assertNull(map["2026-05-08"])
        assertFalse(changed.contains("2026-05-08"))
    }

    @Test
    fun `past dates are ignored`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(
                max = mapOf("2026-04-29" to 75f),
                min = mapOf("2026-04-29" to 55f),
            ),
            today,
        )
        assertNull(map["2026-04-29"])
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `partial fill - only high gets filled when low already present`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>(
            "2026-05-01" to (null to 50f),
        )
        val changed = NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(
                max = mapOf("2026-05-01" to 80f),
                min = mapOf("2026-05-01" to 55f),
            ),
            today,
        )
        assertEquals(80f, map["2026-05-01"]!!.first)
        assertEquals(50f, map["2026-05-01"]!!.second) // low NOT overwritten
        assertTrue(changed.contains("2026-05-01"))
    }

    @Test
    fun `today inclusive in horizon`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(max = mapOf("2026-04-30" to 70f)),
            today,
        )
        assertEquals(70f, map["2026-04-30"]!!.first)
    }

    @Test
    fun `custom horizon cap - 5 days`() {
        val map = mutableMapOf<String, Pair<Float?, Float?>>()
        NwsForecastMapper.mergeGridpointTemperatures(
            map,
            extremes(
                max = mapOf(
                    "2026-05-04" to 70f, // today + 4 — within
                    "2026-05-05" to 71f, // today + 5 — outside
                ),
            ),
            today,
            horizonDays = 5,
        )
        assertEquals(70f, map["2026-05-04"]!!.first)
        assertNull(map["2026-05-05"])
    }
}
