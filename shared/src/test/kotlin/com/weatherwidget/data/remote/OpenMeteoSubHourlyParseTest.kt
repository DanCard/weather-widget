package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

@Category(ShortDuration::class)
class OpenMeteoSubHourlyParseTest {

    @Test
    fun `quarter-hour history reaches current provider timestamp and excludes future`() {
        val rows = OpenMeteoApi.parseSubHourlyHistory(
            """
            {
              "timezone":"America/Los_Angeles",
              "current":{"time":"2026-08-21T12:15"},
              "minutely_15":{
                "time":["2026-08-21T11:45","2026-08-21T12:00","2026-08-21T12:15","2026-08-21T12:30"],
                "temperature_2m":[63.0,64.0,65.0,66.0],
                "weather_code":[3,2,1,0],
                "precipitation":[0.0,0.1,0.0,0.0],
                "cloud_cover":[75,68,63,59],
                "cloud_cover_low":[78,68,56,42]
              }
            }
            """.trimIndent(),
        )

        assertEquals(3, rows.size)
        assertEquals(listOf(78, 68, 56), rows.map { it.cloudCoverLow })
        assertEquals(listOf("Overcast", "Partly Cloudy", "Mostly Clear"), rows.map { it.condition })
        assertEquals(65f, rows.last().temperature, 0.001f)
        assertEquals(
            LocalDateTime.of(2026, 8, 21, 12, 15)
                .atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli(),
            rows.last().dateTime,
        )
    }

    @Test
    fun `missing cloud remains null and a row without temperature is omitted`() {
        val rows = OpenMeteoApi.parseSubHourlyHistory(
            """
            {
              "timezone":"UTC",
              "current":{"time":"2026-08-21T12:15"},
              "minutely_15":{
                "time":["2026-08-21T12:00","2026-08-21T12:15"],
                "temperature_2m":[64.0,null],
                "weather_code":[2,2],
                "precipitation":[null,0.0],
                "cloud_cover":[null,50],
                "cloud_cover_low":[null,25]
              }
            }
            """.trimIndent(),
        )

        assertEquals(1, rows.size)
        assertNull(rows.single().cloudCover)
        assertNull(rows.single().cloudCoverLow)
        assertNull(rows.single().precipAmountMm)
    }
}
