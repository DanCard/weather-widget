package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Parsing of `cloud_cover_previous_day1` from the Previous Runs API. The fixture mirrors a real
 * response, including the two shapes that matter: a null hour, and hours padded past "now".
 */
@Category(ShortDuration::class)
class PreviousRunsCloudParseTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(h: Int) =
        LocalDateTime.of(2026, 8, 20, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val fixture = """
        {
          "timezone": "America/Los_Angeles",
          "hourly": {
            "time": ["2026-08-20T09:00","2026-08-20T10:00","2026-08-20T11:00",
                     "2026-08-20T12:00","2026-08-20T13:00"],
            "cloud_cover_previous_day1": [100, null, 100, 55, 120]
          }
        }
    """.trimIndent()

    @Test
    fun `parses hours in the response timezone`() {
        val out = OpenMeteoApi.parsePriorDayCloudForecast(fixture, nowMs = ms(14))
        assertEquals(100, out[ms(9)])
        assertEquals(100, out[ms(11)])
    }

    /**
     * A null must not become 0. Zero is a clear sky — a real, wrong claim — while an absent key
     * lets the render fall back honestly and mark the point unfrozen.
     */
    @Test
    fun `null hours are omitted, never zeroed`() {
        val out = OpenMeteoApi.parsePriorDayCloudForecast(fixture, nowMs = ms(14))
        assertFalse("a null hour must not appear at all", out.containsKey(ms(10)))
    }

    /** The API pads the current day into the future; only elapsed hours have a settled comparison. */
    @Test
    fun `hours at or after now are dropped`() {
        val out = OpenMeteoApi.parsePriorDayCloudForecast(fixture, nowMs = ms(12))
        assertTrue(out.containsKey(ms(11)))
        assertFalse("the in-progress hour is not past", out.containsKey(ms(12)))
        assertFalse(out.containsKey(ms(13)))
    }

    @Test
    fun `out of range values are clamped`() {
        val out = OpenMeteoApi.parsePriorDayCloudForecast(fixture, nowMs = ms(14))
        assertEquals(100, out[ms(13)])
    }

    @Test
    fun `a response without the hourly block yields nothing rather than throwing`() {
        assertEquals(emptyMap<Long, Int>(), OpenMeteoApi.parsePriorDayCloudForecast("""{"error":true}""", 0L))
    }

    /**
     * Kathmandu (UTC+5:45) is no dev machine's zone: [fixture]'s Los Angeles cannot tell the reply's
     * zone from the device's on a machine in California. 2026-08-20T09:00 there is 03:15Z.
     */
    @Test
    fun `hours are filed in the reply's zone, not the device's`() {
        val reply = """
            {"timezone": "Asia/Kathmandu",
             "hourly": {"time": ["2026-08-20T09:00", "2026-08-20T10:00"], "cloud_cover_previous_day1": [40, -3]}}
        """.trimIndent()
        val nineAm = 1787195700000L

        assertEquals(
            mapOf(nineAm to 40, nineAm + 3_600_000L to 0),
            OpenMeteoApi.parsePriorDayCloudForecast(reply, nowMs = nineAm + 3_600_001L),
        )
        assertEquals(mapOf(nineAm to 40), OpenMeteoApi.parsePriorDayCloudForecast(reply, nowMs = nineAm + 3_600_000L))
    }

    @Test
    fun `a reply without the times or without the previous-day values yields nothing`() {
        assertEquals(
            emptyMap<Long, Int>(),
            OpenMeteoApi.parsePriorDayCloudForecast("""{"hourly": {"cloud_cover_previous_day1": [40]}}""", Long.MAX_VALUE),
        )
        assertEquals(
            emptyMap<Long, Int>(),
            OpenMeteoApi.parsePriorDayCloudForecast("""{"hourly": {"time": ["2026-08-20T09:00"]}}""", Long.MAX_VALUE),
        )
    }
}
