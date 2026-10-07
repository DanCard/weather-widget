package com.weatherwidget.shared.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The fetch-side decision both platforms delegate to. Regression: Google Weather with NWS as its
 * actuals provider fetched no NWS observations from a full refresh on desktop, or from the
 * current-temp loop on Android (plans/261007-desktop-borrowed-nws-actuals-not-fetched-on-full-refresh.md).
 */
@Category(ShortDuration::class)
class ActualsFeedPolicyTest {

    private val usLat = 37.417
    private val usLon = -122.089
    private val kyivLat = 50.45
    private val kyivLon = 30.524
    private val now = 1_791_400_000_000L

    private fun prefs(vararg pairs: Pair<WeatherSource, WeatherSource>): (WeatherSource) -> WeatherSource? {
        val map = pairs.toMap()
        return { map[it] }
    }

    private val googleNws = prefs(WeatherSource.GOOGLE_WEATHER to WeatherSource.NWS)

    // --- feedFor ---

    @Test
    fun `feed is the resolved provider`() {
        assertEquals(WeatherSource.NWS, ActualsFeedPolicy.feedFor(WeatherSource.GOOGLE_WEATHER, usLat, usLon, googleNws))
        assertEquals(WeatherSource.METAR, ActualsFeedPolicy.feedFor(WeatherSource.GOOGLE_WEATHER, usLat, usLon, prefs()))
        assertEquals(WeatherSource.NWS, ActualsFeedPolicy.feedFor(WeatherSource.NWS, usLat, usLon, prefs()))
    }

    @Test
    fun `NWS feed outside coverage is no feed, preference untouched`() {
        assertNull(ActualsFeedPolicy.feedFor(WeatherSource.GOOGLE_WEATHER, kyivLat, kyivLon, googleNws))
    }

    // --- fullRefreshFetch: exhaustive over every source x every provider it accepts ---

    @Test
    fun `every borrowed or redirected provider gets a full-refresh fetch of that feed`() {
        for (source in WeatherSource.entries) {
            if (!ActualsProviderResolver.allowsAlternativeProvider(source)) continue
            for (provider in ActualsProviderResolver.candidates()) {
                val pref = prefs(source to provider)
                val feed = ActualsFeedPolicy.feedFor(source, usLat, usLon, pref)!!
                val plan = ActualsFeedPolicy.fullRefreshFetch(source, usLat, usLon, false, null, now, pref)
                if (feed == source) {
                    assertNull("$source supplies its own actuals in its forecast fetch", plan)
                } else {
                    assertNotNull("$source with provider $provider must fetch $feed on a full refresh", plan)
                    assertEquals("$source/$provider", feed, plan!!.feed)
                }
            }
        }
    }

    @Test
    fun `google with NWS provider fetches NWS, recent window when stored rows are fresh`() {
        val plan = ActualsFeedPolicy.fullRefreshFetch(
            WeatherSource.GOOGLE_WEATHER, usLat, usLon,
            deferObservationWindow = false,
            newestStoredFeedMs = now - 35 * 60_000L,
            nowMs = now,
            actualsPreference = googleNws,
        )
        assertEquals(ActualsFeedPolicy.ObservationFetch(WeatherSource.NWS, recentOnly = true), plan)
    }

    @Test
    fun `NWS gap beyond the recent window takes the full pull`() {
        val stale = ActualsFeedPolicy.fullRefreshFetch(
            WeatherSource.GOOGLE_WEATHER, usLat, usLon, false,
            newestStoredFeedMs = now - ActualsFeedPolicy.NWS_RECENT_WINDOW_COVERS_GAP_MS,
            nowMs = now, actualsPreference = googleNws,
        )
        val none = ActualsFeedPolicy.fullRefreshFetch(
            WeatherSource.GOOGLE_WEATHER, usLat, usLon, false, null, now, googleNws,
        )
        assertFalse(stale!!.recentOnly)
        assertFalse(none!!.recentOnly)
    }

    @Test
    fun `deferred window always takes the recent pull for NWS`() {
        val plan = ActualsFeedPolicy.fullRefreshFetch(
            WeatherSource.GOOGLE_WEATHER, usLat, usLon, true, null, now, googleNws,
        )
        assertTrue(plan!!.recentOnly)
    }

    @Test
    fun `METAR recovery is never narrowed`() {
        val plan = ActualsFeedPolicy.fullRefreshFetch(
            WeatherSource.GOOGLE_WEATHER, usLat, usLon, true, now, now, prefs(),
        )
        assertEquals(ActualsFeedPolicy.ObservationFetch(WeatherSource.METAR, recentOnly = false), plan)
    }

    @Test
    fun `borrowed NWS outside coverage fetches nothing`() {
        assertNull(ActualsFeedPolicy.fullRefreshFetch(WeatherSource.GOOGLE_WEATHER, kyivLat, kyivLon, false, null, now, googleNws))
    }

    @Test
    fun `deferred history window follows the feed, not the displayed source`() {
        assertTrue(ActualsFeedPolicy.hasDeferredHistoryWindow(WeatherSource.NWS, usLat, usLon, prefs()))
        assertTrue(ActualsFeedPolicy.hasDeferredHistoryWindow(WeatherSource.GOOGLE_WEATHER, usLat, usLon, googleNws))
        assertFalse(ActualsFeedPolicy.hasDeferredHistoryWindow(WeatherSource.GOOGLE_WEATHER, usLat, usLon, prefs()))
        assertFalse(ActualsFeedPolicy.hasDeferredHistoryWindow(WeatherSource.OPEN_METEO, usLat, usLon, prefs()))
    }

    // --- Android current-temp loop ---

    @Test
    fun `current-temp loop reaches a borrowed NWS feed that is not visible`() {
        val feeds = ActualsFeedPolicy.currentTempFeeds(listOf(WeatherSource.GOOGLE_WEATHER), usLat, usLon, googleNws)
        assertEquals(mapOf(WeatherSource.NWS to listOf(WeatherSource.GOOGLE_WEATHER)), feeds)
    }

    @Test
    fun `current-temp loop never fetches a forecast-only source as its own feed`() {
        for (source in WeatherSource.entries.filter { !it.supportsTemperatureActuals }) {
            val feeds = ActualsFeedPolicy.currentTempFeeds(listOf(source), usLat, usLon, prefs())
            assertFalse("$source", source in feeds.keys)
        }
    }

    @Test
    fun `current-temp loop keeps self-actuals sources and leaves station networks to their refreshers`() {
        val feeds = ActualsFeedPolicy.currentTempFeeds(
            listOf(WeatherSource.NWS, WeatherSource.GOOGLE_WEATHER, WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO),
            usLat, usLon,
            prefs(WeatherSource.GOOGLE_WEATHER to WeatherSource.NWS),
        )
        // Silurian borrows METAR by default: METAR has its own refresher.
        assertEquals(listOf(WeatherSource.NWS, WeatherSource.TOMORROW_IO), feeds.keys.toList())
        assertEquals(listOf(WeatherSource.NWS, WeatherSource.GOOGLE_WEATHER), feeds[WeatherSource.NWS])
    }

    // --- requiresFeed / borrowers ---

    @Test
    fun `NWS history is required by NWS and by its borrowers only`() {
        assertTrue(ActualsFeedPolicy.requiresFeed(WeatherSource.NWS, listOf(WeatherSource.NWS), usLat, usLon, prefs()))
        assertTrue(ActualsFeedPolicy.requiresFeed(WeatherSource.NWS, listOf(WeatherSource.GOOGLE_WEATHER), usLat, usLon, googleNws))
        assertFalse(ActualsFeedPolicy.requiresFeed(WeatherSource.NWS, listOf(WeatherSource.GOOGLE_WEATHER), usLat, usLon, prefs()))
    }

    @Test
    fun `borrowers matches the METAR and Synoptic consumer lists`() {
        val visible = listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.GOOGLE_WEATHER, WeatherSource.OPEN_METEO)
        val pref = prefs(WeatherSource.GOOGLE_WEATHER to WeatherSource.SYNOPTIC)
        assertEquals(MetarFetchPolicy.consumers(visible, pref), ActualsFeedPolicy.borrowers(WeatherSource.METAR, visible, pref))
        assertEquals(SynopticFetchPolicy.consumers(visible, pref), ActualsFeedPolicy.borrowers(WeatherSource.SYNOPTIC, visible, pref))
        assertEquals(listOf(WeatherSource.GOOGLE_WEATHER), ActualsFeedPolicy.borrowers(WeatherSource.SYNOPTIC, visible, pref))
    }
}
