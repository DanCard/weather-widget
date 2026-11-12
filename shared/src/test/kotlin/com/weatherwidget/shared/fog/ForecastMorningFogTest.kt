package com.weatherwidget.shared.fog

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.util.DailyHourlySummaries
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Forecast fog from one source's hours, and its storage behaviour on the daily row
 * (plans/261111-daily-bar-morning-fog.md). The fixture is Open-Meteo's 2026-11-11 03:12 fetch.
 */
@Category(ShortDuration::class)
class ForecastMorningFogTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 11, 11)
    private fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** Open-Meteo 2026-11-11 03:12: "Light Fog" 02–09, Overcast 10:00 at 56.9°. */
    private val openMeteo: List<HourlyForecast> = run {
        val temps = listOf(54.8f, 53.7f, 52.9f, 53.1f, 54.4f, 54.1f, 53.1f, 53.0f, 53.0f, 53.9f, 56.9f, 61.0f, 63.8f, 66.7f)
        val conditions = listOf("Mostly Clear", "Overcast") + List(8) { "Light Fog" } + listOf("Overcast", "Mostly Clear", "Clear", "Clear")
        temps.indices.map { h -> HourlyForecast(dateTime = at(h), temperature = temps[h], condition = conditions[h], source = "OPEN_METEO") }
    }

    @Test
    fun `top is midway between the last fog hour and the first clear one`() {
        val fog = ForecastMorningFog.forDate(openMeteo, day, zone, sunriseMs = null)!!
        println("openMeteo fog=$fog")
        assertEquals(FogLevel.LIGHT, fog.level)
        assertEquals(55.4f, fog.topTempF!!, 0.001f) // midway: last fog 09:00 53.9°, clear 10:00 56.9°
        assertEquals(3f, fog.visibilityMi!!, 0f) // the word's stand-in: no visibility in these rows
    }

    @Test
    fun `provider visibility sets the level and counts fog without the word`() {
        val withVisibility = openMeteo.map { h ->
            when (h.dateTime) {
                at(7) -> h.copy(visibilityMi = 0f)
                at(10) -> h.copy(condition = "Overcast", visibilityMi = 0.12f) // fog by visibility alone
                else -> h.copy(visibilityMi = if (h.condition == "Light Fog") 0.06f else 7.6f)
            }
        }
        val fog = ForecastMorningFog.forDate(withVisibility, day, zone, sunriseMs = null)!!
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0f, fog.visibilityMi!!, 0f)
        assertEquals(58.95f, fog.topTempF!!, 0.001f) // fog ran to 10:00 (56.9°), clear at 11:00 (61.0°)
    }

    @Test
    fun `trailing mist hours do not raise the top of a fog morning`() {
        // Fog by visibility to 07:00, then mist words with 3 SM to 09:00, clear at 10:00.
        val fogThenMist = openMeteo.map { h ->
            when {
                h.dateTime <= at(7) && h.condition == "Light Fog" -> h.copy(condition = "Fog", visibilityMi = 0.2f)
                h.condition == "Light Fog" -> h.copy(condition = "Mist", visibilityMi = 3f)
                else -> h.copy(visibilityMi = 9f)
            }
        }
        val fog = ForecastMorningFog.forDate(fogThenMist, day, zone, sunriseMs = null)!!
        println("fogThenMist fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0.2f, fog.visibilityMi!!, 0f)
        // Midway 07:00 fog (53.0°) and 08:00 mist (53.0°); the mist to 09:00 and the clear 10:00 (56.9°)
        // would have made it 55.4°.
        assertEquals(53.0f, fog.topTempF!!, 0.001f)
    }

    @Test
    fun `no fog is NONE when covered and null when not`() {
        val clear = openMeteo.map { it.copy(condition = "Clear") }
        assertEquals(MorningFog.NONE, ForecastMorningFog.forDate(clear, day, zone, sunriseMs = null))
        // Starts mid-window (a download from 05:00): not covered.
        assertNull(ForecastMorningFog.forDate(clear.drop(5), day, zone, sunriseMs = null))
    }

    @Test
    fun `haze and smoke are not fog`() {
        assertEquals(FogLevel.NONE, FogLevel.fromCondition("Haze"))
        assertEquals(FogLevel.NONE, FogLevel.fromCondition("Smoke"))
        assertEquals(FogLevel.LIGHT, FogLevel.fromCondition("Patchy Fog"))
        assertEquals(FogLevel.FOG, FogLevel.fromCondition("Areas Of Fog"))
        assertEquals(FogLevel.DENSE, FogLevel.fromCondition("Dense Fog"))
        assertEquals(FogLevel.LIGHT, FogLevel.fromCondition("Mist"))
        assertEquals(FogLevel.LIGHT, FogLevel.fromCondition("Fog/Mist"))
    }

    @Test
    fun `a newer fetch without fog clears an older fetch's fog on the stored row`() {
        val older = DailyHourlySummaries.forDate(openMeteo, day, "OPEN_METEO", zone)
        assertTrue(older.fog!!.isFoggy)
        val newerClear = DailyHourlySummaries.forDate(openMeteo.map { it.copy(condition = "Clear") }, day, "OPEN_METEO", zone)
        val stored = DailyHourlySummaries.carryForward(newerClear, older)
        assertEquals(MorningFog.NONE, stored.fog)
        // A newer fetch that does not reach the window keeps the older fog.
        val partial = DailyHourlySummaries.forDate(openMeteo.drop(5), day, "OPEN_METEO", zone)
        assertEquals(older.fog, DailyHourlySummaries.carryForward(partial, older).fog)
    }

    /**
     * NWS 2026-11-12 00:14 fetch at 37.417,-122.089 (Pixel backup 20261112_072826): fog by grid
     * visibility only, 03:00-05:00, words "Mostly Cloudy". Sunrise there was about 06:45.
     */
    private val nov12 = LocalDate.of(2026, 11, 12)
    private val nwsLat = 37.417
    private val nwsLon = -122.089
    private val nws: List<HourlyForecast> = run {
        val temps = listOf(55f, 54f, 53f, 52f, 52f, 52f, 52f, 52f, 56f, 60f, 65f, 68f, 70f, 72f)
        val vis = listOf(10f, 10f, 10f, 0.48f, 0.24f, 0.48f, 1.64f, 2.04f, 10f, 10f, 10f, 10f, 10f, 10f)
        val words = listOf("Mostly Clear", "Partly Cloudy", "Partly Cloudy", "Mostly Cloudy", "Mostly Cloudy", "Mostly Cloudy",
            "Partly Sunny", "Mostly Sunny", "Mostly Sunny", "Mostly Sunny", "Mostly Sunny", "Mostly Sunny", "Sunny", "Sunny")
        temps.indices.map { h ->
            HourlyForecast(
                dateTime = nov12.atTime(h, 0).atZone(zone).toInstant().toEpochMilli(),
                temperature = temps[h], condition = words[h], source = "NWS", visibilityMi = vis[h],
            )
        }
    }
    private fun nov12At(h: Int) = nov12.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `nws nov 12 fog gone by sunrise is pre-dawn, and none of it is ahead at 07`() {
        val sunrise = MorningFogWindow.sunriseMs(nov12, nwsLat, nwsLon)!!
        println("sunrise=${java.time.Instant.ofEpochMilli(sunrise).atZone(zone)}")
        assertTrue(sunrise in nov12At(6)..nov12At(7))

        val fog = ForecastMorningFog.forDate(nws, nov12, zone, sunrise)!!
        println("nws fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0.24f, fog.visibilityMi!!, 0f)
        assertTrue(fog.predawn)

        // What the column reads once the stations have spoken: the hours from 07:00 hold no fog.
        assertEquals(MorningFog.NONE, ForecastMorningFog.forWindow(nws, nov12At(7), MorningFogWindow.endMs(nov12, zone), sunrise))

        // Fog that lasts past sunrise is daylight fog.
        val lingering = nws.map { if (it.dateTime == nov12At(7)) it.copy(visibilityMi = 0.3f) else it }
        assertFalse(ForecastMorningFog.forDate(lingering, nov12, zone, sunrise)!!.predawn)
        // No site, no sunrise: full size.
        assertFalse(ForecastMorningFog.forDate(nws, nov12, zone, sunriseMs = null)!!.predawn)
        // The stored summary carries it when the site is known.
        assertTrue(DailyHourlySummaries.forDate(nws, nov12, "NWS", zone, nwsLat, nwsLon).fog!!.predawn)
    }
}
