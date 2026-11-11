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
        val fog = ForecastMorningFog.forDate(openMeteo, day, zone)!!
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
        val fog = ForecastMorningFog.forDate(withVisibility, day, zone)!!
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
        val fog = ForecastMorningFog.forDate(fogThenMist, day, zone)!!
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
        assertEquals(MorningFog.NONE, ForecastMorningFog.forDate(clear, day, zone))
        // Starts mid-window (a download from 05:00): not covered.
        assertNull(ForecastMorningFog.forDate(clear.drop(5), day, zone))
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
}
