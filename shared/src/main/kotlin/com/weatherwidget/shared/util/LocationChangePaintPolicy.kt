package com.weatherwidget.shared.util

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.DailyForecast
import java.time.LocalDate
import java.time.ZoneId

/**
 * Decides whether a location change should replace what is on screen with a
 * "Getting weather for {place}…" interstitial, or leave the display to the normal cache/fetch
 * repaints.
 *
 * The axis is not "setup vs GPS" but **user-initiated vs background**. A setup-screen save is the
 * one location write where the user is guaranteed to be looking at the widget in the next few
 * seconds, asking "did my choice register, and did it pick the right place?" — so a message naming
 * the place is worth the flash. A follow-device move is background: nobody is watching, the fetch
 * is battery-gated (up to 1440 min off charger), and a placeholder that could sit for hours would
 * be strictly worse than the sparse-but-correct graph the immediate switch already gives. The paint
 * policy follows the fetch policy: only the path that bypasses the battery gate gets feedback.
 *
 * Shared by Android (`LocationUpdater`) and desktop (`DesktopUiApplication`).
 */
object LocationChangePaintPolicy {

    /**
     * @param userInitiated true for the setup screen / location picker; false for device following.
     * @param previous the site on screen before the change, or null when there was none.
     * @param newLat/newLon the site just written.
     * @param hasCachedRowsAtNewSite true when the new site already has a forecast row for today —
     *   switching between two saved places (home ↔ work) must not flash a spinner over a render the
     *   cache repaint can produce correctly.
     */
    fun shouldShowInterstitial(
        userInitiated: Boolean,
        previous: Pair<Double, Double>?,
        newLat: Double,
        newLon: Double,
        hasCachedRowsAtNewSite: Boolean,
    ): Boolean {
        if (!userInitiated) return false
        if (hasCachedRowsAtNewSite) return false
        return !isSameSite(previous, newLat, newLon)
    }

    /** What a location change puts on screen while its first fetch runs. */
    enum class Feedback {
        /** Background move, or no real move: leave the display to the normal repaints. */
        NONE,

        /**
         * A "Getting weather for {place}…" banner over whatever is already drawn — the previous
         * site's graph included. The user asked for this over a blank placeholder (2026-09-28):
         * the old graph plus a message saying the new one is coming beats a screen with nothing
         * on it, and a toast lasts ~4 s against a fetch that takes 10–30.
         */
        BANNER,

        /** Full-screen "Getting weather for {place}…": only when there is nothing to keep showing. */
        INTERSTITIAL,
    }

    /**
     * @param hasRenderToKeep true when the widget/popup already shows something worth keeping
     *   under a banner — the previous site's render, or the new site's cached rows for today.
     */
    fun feedback(userInitiated: Boolean, siteChanged: Boolean, hasRenderToKeep: Boolean): Feedback = when {
        !userInitiated || !siteChanged -> Feedback.NONE
        hasRenderToKeep -> Feedback.BANNER
        else -> Feedback.INTERSTITIAL
    }

    /**
     * What a user-initiated change puts on screen. [adoptCached] = draw the new site's cache now.
     *
     * A new site with today's row needs no feedback at all ([Feedback.NONE]): its own graph is the
     * answer to "did my choice register?", and a "Getting weather for Mountain View…" banner over
     * Mountain View's graph reads as still loading (2026-09-29, Pixel 7 Pro: 42 s of banner over
     * already-correct data while a background refresh ran). The banner is for when the *previous*
     * site is what is on screen.
     */
    data class Decision(val feedback: Feedback, val adoptCached: Boolean)

    /**
     * For a user-initiated change to a different site (the caller has established both).
     *
     * @param hasTodayRowAtNewSite [hasTodayRow] for the new site's cache.
     * @param hasRenderOnScreen the previous site's render is showing.
     */
    fun decide(hasTodayRowAtNewSite: Boolean, hasRenderOnScreen: Boolean): Decision =
        if (hasTodayRowAtNewSite) {
            Decision(Feedback.NONE, adoptCached = true)
        } else {
            Decision(
                feedback = feedback(userInitiated = true, siteChanged = true, hasRenderToKeep = hasRenderOnScreen),
                adoptCached = false,
            )
        }

    /**
     * The one definition of `hasCachedRowsAtNewSite`: a real (non-climate-normal) forecast row whose
     * date is [today]. "Any cached row" is not enough — a site last visited two weeks ago still
     * returns its old daily rows, none of which fall in the visible window, and adopting that cache
     * painted an empty graph for the whole first fetch (desktop, 2026-09-28).
     */
    fun hasTodayRow(dailyRows: Iterable<DailyForecast>, today: LocalDate): Boolean {
        val todayIso = today.toString()
        return dailyRows.any { !it.isClimateNormal && it.date == todayIso }
    }

    /**
     * The oldest hourly fetch a location change will adopt without a banner. A 16-day forecast
     * fetched days ago still has rows dated today — Kyiv's cache from 09-24 was adopted as-is on
     * 2026-09-29 — and showing that silently as the new site's weather is not "cached", it is stale.
     * Matches desktop's `DesktopWeatherRepository.loadCached` hourly max age, so both platforms
     * draw the same line.
     */
    const val MAX_ADOPTABLE_CACHE_AGE_MS = 24 * 60 * 60 * 1000L

    /** True when [hourlyTimesMs] (epoch ms) include an hour of [today] in [zone]. */
    fun hasHourlyForToday(hourlyTimesMs: Iterable<Long>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        hourlyTimesMs.any { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }

    /**
     * The one definition of "the new site's cache can be drawn": today's daily row AND hourly rows
     * for today, both read the way the render reads them. A daily row alone is not enough — the
     * daily selector's proximity box is wider than the hourly site match, so a precise-location fix
     * 7 km from a cached city centre found the centre's daily row, adopted it, and drew a graph with
     * zero hourly rows for the 31 s its own fetch took (Warsaw → Wola, 2026-09-29).
     */
    fun hasDrawableCache(
        dailyRows: Iterable<DailyForecast>,
        hourlyTimesMs: Iterable<Long>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = hasTodayRow(dailyRows, today) && hasHourlyForToday(hourlyTimesMs, today, zone)

    /**
     * Short place name for transient messages, or null when the label can't say which component
     * is the place. Nominatim display names lead with the most specific component, which is the
     * city for a city search ("Warsaw, Masovian Voivodeship, Poland") but a house number for an
     * address ("860, Avery Drive, Mountain View, …") and the postcode for a ZIP search ("94043,
     * Mountain View, …"). Those two are indistinguishable from the label alone, so a letterless
     * lead component (and bare coordinates) gives null; the caller says "the new location" until
     * a reverse lookup's structured name arrives.
     */
    fun shortPlaceNameOrNull(label: String): String? {
        val first = label.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return first.takeIf { part -> part.any { it.isLetter() } }
    }

    /** True when the change stayed inside one site (jitter), so nothing on screen is wrong. */
    fun isSameSite(previous: Pair<Double, Double>?, newLat: Double, newLon: Double): Boolean =
        previous != null && LocationMatch.sameSite(previous.first, previous.second, newLat, newLon)
}
