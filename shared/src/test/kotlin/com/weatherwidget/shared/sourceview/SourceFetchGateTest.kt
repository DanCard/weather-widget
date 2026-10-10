package com.weatherwidget.shared.sourceview

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.GOOGLE_WEATHER
import com.weatherwidget.data.model.WeatherSource.METAR
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.SILURIAN
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/**
 * "Not viewed in 8 days → not fetched in the background", and the actuals feeds that follow the
 * sources still fetched. performance/261010-fetch-only-sources-likely-to-be-viewed.md
 */
@Category(ShortDuration::class)
class SourceFetchGateTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val tracked = today.minusDays(30)
    private val enabled = listOf(GOOGLE_WEATHER, NWS, OPEN_METEO, SILURIAN)

    // Mountain View (inside NWS coverage) and Paris.
    private val mv = 37.42 to -122.08
    private val paris = 48.86 to 2.35
    private val noPreference: (WeatherSource) -> WeatherSource? = { null }

    private fun row(
        daysAgo: Long,
        source: WeatherSource = OPEN_METEO,
        trigger: SourceViewTrigger = SourceViewTrigger.TOGGLE,
        switches: Int = 1,
    ) = SourceViewDayRow(
        SourceViewTally.dayMs(today.minusDays(daysAgo)), source.id, SourceViewKind.DAILY.name, trigger.name, false, switches,
    )

    private fun gate(
        vararg rows: SourceViewDayRow,
        since: LocalDate? = tracked,
        displayed: Set<String> = setOf(GOOGLE_WEATHER.id),
        primary: String? = GOOGLE_WEATHER.id,
        at: Pair<Double, Double> = mv,
        preference: (WeatherSource) -> WeatherSource? = noPreference,
    ) = SourceFetchGate.backgroundFetch(enabled, displayed, primary, rows.toList(), since, today, at.first, at.second, preference)

    private fun fetchedWith(vararg rows: SourceViewDayRow, since: LocalDate? = tracked) = OPEN_METEO in gate(*rows, since = since).forecasts

    @Test
    fun `the plan's table - 7 days on, 8 days off`() {
        assertTrue("viewed today", fetchedWith(row(0)))
        assertTrue("last viewed 7 days ago", fetchedWith(row(7)))
        assertFalse("last viewed 8 days ago", fetchedWith(row(8)))
        assertFalse("daily until 8 days ago", fetchedWith(*(8L..30L).map { row(it) }.toTypedArray()))
        assertFalse("never viewed, tracked 30 days", fetchedWith())
        assertTrue("never viewed, tracking started 5 days ago", fetchedWith(since = today.minusDays(5)))
    }

    @Test
    fun `grace ends after exactly 8 tracked days`() {
        assertTrue(fetchedWith(since = today.minusDays(7)))
        assertFalse(fetchedWith(since = today.minusDays(8)))
        assertTrue("no tracking row at all fails open", fetchedWith(since = null))
    }

    @Test
    fun `displayed and primary are always fetched`() {
        val g = gate(displayed = setOf(SILURIAN.id), primary = GOOGLE_WEATHER.id)
        assertEquals(setOf(GOOGLE_WEATHER, SILURIAN), g.forecasts)
        assertEquals(setOf(NWS, OPEN_METEO), g.off)
    }

    @Test
    fun `home and observations switches count as views, zero-switch rows and future rows don't`() {
        assertTrue(fetchedWith(row(3, trigger = SourceViewTrigger.HOME)))
        assertTrue(fetchedWith(row(3, trigger = SourceViewTrigger.OBSERVATIONS)))
        assertFalse(fetchedWith(row(3, switches = 0)))
        assertFalse(fetchedWith(row(-1)))
    }

    @Test
    fun `forecasts keep the enabled order`() {
        assertEquals(listOf(GOOGLE_WEATHER, NWS, OPEN_METEO), gate(row(1, NWS), row(1)).forecasts.toList())
    }

    @Test
    fun `actuals feeds follow the fetched sources - the plan's table`() {
        // Google displayed inside coverage, NWS forecast never viewed: NWS feed on (Google borrows it).
        val usGoogle = gate()
        assertFalse(NWS in usGoogle.forecasts)
        assertEquals(setOf(NWS), usGoogle.actualsFeeds)
        // NWS viewed: its own feed.
        assertTrue(NWS in gate(row(2, NWS)).actualsFeeds)
        // Open-Meteo displayed abroad, Google rarely viewed: Google's METAR borrowing is off too.
        val abroad = gate(displayed = setOf(OPEN_METEO.id), primary = OPEN_METEO.id, at = paris)
        assertFalse(GOOGLE_WEATHER in abroad.forecasts)
        assertFalse(METAR in abroad.actualsFeeds)
        assertFalse(NWS in abroad.actualsFeeds)
        // Google displayed abroad: METAR on.
        assertEquals(setOf(METAR), gate(at = paris).actualsFeeds)
    }

    @Test
    fun `a provider choice changes the feeds with nothing stored`() {
        val chooseMetar: (WeatherSource) -> WeatherSource? = { if (it == GOOGLE_WEATHER) METAR else null }
        assertEquals(setOf(NWS), gate().actualsFeeds)
        assertEquals(setOf(METAR), gate(preference = chooseMetar).actualsFeeds)
    }

    @Test
    fun `a forecast-only source is never its own feed`() {
        val all = SourceFetchGate.actualsFeeds(listOf(GOOGLE_WEATHER, SILURIAN), mv.first, mv.second, noPreference)
        assertFalse(GOOGLE_WEATHER in all)
        assertFalse(SILURIAN in all)
    }

    @Test
    fun `log line`() {
        assertEquals("on=GOOGLE_WEATHER off=NWS,OPEN_METEO,SILURIAN feeds=NWS", gate().logLine())
    }

    /** METAR's tier is asked over the gated set, so an on-demand borrower no longer keeps it fetched. */
    @Test
    fun `metar tier over the gated set`() {
        val abroad = gate(displayed = setOf(OPEN_METEO.id), primary = OPEN_METEO.id, at = paris)
        val metarPref: (WeatherSource) -> WeatherSource? = { if (it == GOOGLE_WEATHER) METAR else null }
        val displayed = setOf(OPEN_METEO.id)
        assertEquals(
            "every enabled source: Google keeps METAR on the background tier",
            com.weatherwidget.shared.util.MetarFetchPolicy.Tier.NON_PRIMARY,
            com.weatherwidget.shared.util.MetarFetchPolicy.tierFor(enabled, displayed, metarPref),
        )
        assertEquals(
            "gated set: Google is on demand, nothing reads METAR",
            com.weatherwidget.shared.util.MetarFetchPolicy.Tier.NONE,
            com.weatherwidget.shared.util.MetarFetchPolicy.tierFor(abroad.forecasts.toList(), displayed, metarPref),
        )
    }
}
