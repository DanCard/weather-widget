package com.weatherwidget.shared.daily

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.shared.fog.FogLevel
import com.weatherwidget.shared.fog.MorningFog
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Today's fog on the shared column (Android widget and desktop), from the stored row, the stored hours
 * and the observed fog together (plans/261112-observed-no-fog-vetoes-forecast.md).
 *
 * NWS, Pixel 2026-11-12 07:28: the 00:14 fetch forecast 0.48 / 0.24 / 0.48 mi at 03-05 under "Mostly
 * Cloudy", and its row held DENSE 0.24 mi. KNUQ read `10SM CLR` all morning, so the observed fog was
 * NONE, but the ellipse drew at 77 % (user: "has fog indicator this morning, but there is no fog").
 */
@Category(ShortDuration::class)
class DailyColumnFogTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 12)
    private val source = "NWS"
    private val lat = 37.417
    private val lon = -122.089

    private fun at(h: Int) = today.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private val hours: List<HourlyForecast> = run {
        val temps = listOf(55f, 54f, 53f, 52f, 52f, 52f, 52f, 52f, 56f, 60f, 65f, 68f, 70f, 72f)
        val vis = listOf(10f, 10f, 10f, 0.48f, 0.24f, 0.48f, 1.64f, 2.04f, 10f, 10f, 10f, 10f, 10f, 10f)
        temps.indices.map { h ->
            HourlyForecast(
                dateTime = at(h), temperature = temps[h], condition = if (h in 3..5) "Mostly Cloudy" else "Mostly Sunny",
                source = source, locationLat = lat, locationLon = lon, visibilityMi = vis[h],
            )
        }
    }

    /** The 00:14 row as persisted (ForecastPersistRun with the site: pre-dawn). */
    private val row = DailyForecast(
        date = today.toString(), highTemp = 72f, lowTemp = 52f, condition = "Mostly Sunny", source = source,
        fogLevel = FogLevel.DENSE, fogTopTemp = 52f, fogVisibilityMi = 0.24f, fogPredawn = true,
    )

    private fun observed(fog: MorningFog?) = DailyHistory(
        date = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), source = source,
        locationLat = lat, locationLon = lon, computedHighTemp = null, computedLowTemp = 50f,
        condition = "Clear", updatedAt = at(7),
    ).withObservedFog(fog)

    private fun fogAt(hour: Int, minute: Int, actual: DailyHistory?) = DailyColumnBuilder.build(
        date = today, label = "Today", today = today, now = today.atTime(hour, minute), forecast = row,
        actual = actual, snapshots = emptyList(), hourly = hours, currentTemp = null,
        displaySourceId = source, centerLat = lat, centerLon = lon, use24HourClock = false,
    ).fog

    @Test
    fun `stations that saw no fog cancel the forecast's fog once its hours have passed`() {
        val fog = fogAt(7, 30, observed(MorningFog.NONE))
        println("07:30 observed NONE: fog=$fog")
        assertEquals(MorningFog.NONE, fog)
    }

    @Test
    fun `before the forecast fog's hours the forecast still shows, petite`() {
        val fog = fogAt(2, 0, observed(MorningFog.NONE))!!
        println("02:00 observed NONE: fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(0.24f, fog.visibilityMi!!, 0f)
        assertTrue("03-05 fog ends before the 06:44 sunrise", fog.predawn)
    }

    @Test
    fun `stations that saw fog still combine with the whole-morning row`() {
        val mist = MorningFog(FogLevel.LIGHT, 53f, 3f, predawn = false)
        val fog = fogAt(7, 30, observed(mist))!!
        println("07:30 observed mist: fog=$fog")
        assertEquals(FogLevel.DENSE, fog.level)
        assertEquals(false, fog.predawn) // the mist is daylight fog
    }

    @Test
    fun `no observation reads the forecast alone`() {
        val fog = fogAt(7, 30, null)!!
        assertEquals(FogLevel.DENSE, fog.level)
        assertTrue(fog.predawn)
    }

    @Test
    fun `a saturation hint alone draws only the hint, not the forecast's dense fog`() {
        // Calm, saturated air at KNUQ without FG or BR: a hint (ObservedMorningFog rule 2).
        val hint = MorningFog(FogLevel.HINT, 52f, 8f)
        val fog = fogAt(7, 30, observed(hint))
        println("07:30 observed hint: fog=$fog")
        assertEquals(hint, fog)
    }
}
