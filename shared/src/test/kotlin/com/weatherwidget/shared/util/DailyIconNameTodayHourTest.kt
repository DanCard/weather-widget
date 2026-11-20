package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.WeatherConditionResolver as R
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's icon: the header's rain % (next N hours, the user's lookahead setting) or the current hour's
 * cloud wins a clear conflict with the daily icon (plans/261120-today-icon-hourly-rain-override.md).
 * Hourly rows are emulator-5556's Tomorrow.io fetch of 2026-11-20 07:15, whose daily row said Clear
 * (code 1000) beside a 94% day. Most cases use the 1 h lookahead set on the emulators.
 */
@Category(ShortDuration::class)
class DailyIconNameTodayHourTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 20)
    private val source = "TOMORROW_IO"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** hour → (condition, chance %, cloud %), as stored on emulator-5556. */
    private val observed = mapOf(
        5 to Triple("Rain", 15, 42),
        6 to Triple("Cloudy", 0, 98),
        7 to Triple("Rain", 35, 100),
        8 to Triple("Rain", 94, 100),
        9 to Triple("Drizzle", 6, 100),
        10 to Triple("Cloudy", 0, 100),
        11 to Triple("Mostly Clear", 0, 31),
        12 to Triple("Clear", 0, 3),
        18 to Triple("Cloudy", 0, 90),
        21 to Triple("Clear", 0, 10),
    )

    private val hourly = observed.map { (h, v) ->
        HourlyForecast(
            dateTime = at(h), temperature = 50f, condition = v.first, precipProbability = v.second,
            cloudCover = v.third, source = source, locationLat = lat, locationLon = lon,
        )
    }

    private fun override(
        daily: String, hour: Int, minute: Int = 30, rows: List<HourlyForecast> = hourly, lookahead: Int = 1,
    ) = DailyIconName.todayCurrentHourOverride(
        dailyIcon = daily, hourly = rows, displaySourceId = source,
        centerLat = lat, centerLon = lon, now = today.atTime(hour, minute), rainLookaheadHours = lookahead, zone = zone,
    ).also { println("daily=$daily now=$hour:$minute lookahead=${lookahead}h -> $it") }

    @Test
    fun `header rain over the next hour turns today's clear icon into rain from 06_37 to 08_16`() {
        // 7am 35% → 8am 94%: the interpolated chance reaches 70% at 7:36, so the next hour holds it from 06:37.
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 6, 30)) // header 64%; 6am is 98% cloud
        assertEquals(R.IC_CLOUDY_CHANCE_RAIN, override(R.IC_CLEAR, 6, 37)) // header 70%: "chance rain" tier
        assertEquals(R.IC_RAIN, override(R.IC_CLEAR, 6, 50)) // header 84%
        assertEquals(R.IC_RAIN, override(R.IC_CLEAR, 7, 50)) // header 94% (the 8am peak is in the window)
        // 8am 94% → 9am 6%: at 08:10 the header is 79% — the "chance rain" tier.
        assertEquals(R.IC_CLOUDY_CHANCE_RAIN, override(R.IC_CLEAR, 8, 10))
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 8, 17)) // 69%: cloud rule (8am is 100% cloud)
    }

    @Test
    fun `a longer lookahead turns the icon rainy earlier, as the header does`() {
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 6, 0))
        assertEquals(R.IC_RAIN, override(R.IC_CLEAR, 6, 0, lookahead = 3))
        assertEquals(R.IC_RAIN, override(R.IC_CLEAR, 5, 30, lookahead = 6))
    }

    @Test
    fun `header rain below 70 percent leaves the cloud rule`() {
        // 9:30: Drizzle 6% → 10am 0%; the hour is 100% cloud.
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 9))
    }

    @Test
    fun `rain threshold is inclusive at 70`() {
        fun rows(chance: Int) = listOf(
            HourlyForecast(at(14), 50f, "Rain", chance, cloudCover = 50, source = source, locationLat = lat, locationLon = lon),
        )
        // 70–79% is the hour's own "chance rain" icon (as the hourly view draws it); 80% is rain.
        assertEquals(R.IC_PARTLY_CLOUDY_CHANCE_RAIN, override(R.IC_CLEAR, 14, rows = rows(70)))
        assertEquals(R.IC_RAIN, override(R.IC_CLEAR, 14, rows = rows(80)))
        assertNull(override(R.IC_CLEAR, 14, rows = rows(69)))
    }

    @Test
    fun `a storm hour gives the storm icon`() {
        val rows = listOf(
            HourlyForecast(at(14), 50f, "Thunderstorm", 80, cloudCover = 100, source = source, locationLat = lat, locationLon = lon),
        )
        assertEquals(R.IC_STORM, override(R.IC_MOSTLY_CLEAR, 14, rows = rows))
    }

    @Test
    fun `a chance-rain daily icon is upgraded by a 94 percent header`() {
        assertEquals(R.IC_RAIN, override(R.IC_CLOUDY_CHANCE_RAIN, 7, 50))
    }

    @Test
    fun `a precipitation daily icon is never touched`() {
        assertNull(override(R.IC_RAIN, 12))
        assertNull(override(R.IC_SNOW, 8))
    }

    @Test
    fun `clear daily icon in a fully cloudy hour becomes cloudy, a 90 percent hour does not`() {
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 6))
        assertEquals(R.IC_CLOUDY, override(R.IC_MOSTLY_CLEAR, 10))
        // 18:00 at 90% is only "mostly cloudy" — one step is not a conflict.
        assertNull(override(R.IC_CLEAR, 18))
    }

    @Test
    fun `cloudy daily icon in a mostly clear hour becomes mostly clear, at night clear night`() {
        assertEquals(R.IC_MOSTLY_CLEAR, override(R.IC_CLOUDY, 12))
        assertEquals(R.IC_MOSTLY_CLEAR, override(R.IC_MOSTLY_CLOUDY, 12))
        assertEquals(R.IC_NIGHT, override(R.IC_CLOUDY, 21))
    }

    @Test
    fun `neighbouring cloud steps never switch`() {
        // 11am at 31% is partly cloudy: one step from both sides.
        assertNull(override(R.IC_CLEAR, 11))
        assertNull(override(R.IC_MOSTLY_CLOUDY, 11))
        // A partly cloudy daily icon is never switched by cloud.
        assertNull(override(R.IC_PARTLY_CLOUDY, 6))
        assertNull(override(R.IC_PARTLY_CLOUDY, 12))
    }

    @Test
    fun `daily icon that agrees with the hour is left alone`() {
        assertNull(override(R.IC_CLEAR, 12))
        assertNull(override(R.IC_CLOUDY, 6))
    }

    @Test
    fun `another source's hours and a missing current hour leave the icon`() {
        val other = hourly.map { it.copy(source = "OPEN_METEO") }
        assertNull(override(R.IC_CLEAR, 7, 50, rows = other))
        // 15:30: newest row at or before is 12:00, more than 3 h old.
        assertNull(override(R.IC_CLEAR, 15))
        assertNull(override(R.IC_CLEAR, 7, 50, rows = emptyList()))
    }

    @Test
    fun `a 3-hourly series feeds the cloud rule up to 3 hours later, and rain only as the header reads it`() {
        // OWM free /forecast: rows every 3 h. At 10:30 the header has no 10am/11am rows to read (it shows
        // nothing), so no rain; the 08:00 row still counts as the current hour's cloud.
        val rows = listOf(hourly.first { it.dateTime == at(8) })
        assertEquals(R.IC_CLOUDY, override(R.IC_CLEAR, 10, rows = rows))
    }
}
