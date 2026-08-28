package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ObservationSiteMerge
import kotlin.math.abs

/**
 * Which coordinate the observation blend reads at.
 *
 * This is **not** just a coordinate the render draws with. It is the centre of
 * [ObservationSiteMerge]'s ±[ObservationSiteMerge.MERGE_TOLERANCE_DEG] filter, so a wrong value here
 * does not down-weight far-away observations — it removes every row outside that box before the
 * blend runs. Measured 2026-08-28 on a Samsung Fold: a 6 km move (0.011° lat, 0.068° lon) left the
 * blend centred on the fragment the device had left, whose newest row was frozen at 11:10; KNUQ's
 * 13:35 reading was in the database and could not reach the graph, and the hourly label read
 * `knuq 66.2 @ 11:10 am` at 14:17.
 *
 * So the rungs are ordered by *what the caller is asking about*, not by what is closest to hand:
 *
 *  1. **The configured location** — the app's canonical answer to "where is the user", maintained by
 *     `ActiveLocationResolver` and mirrored into `widget_lat_<id>`. The blend answers "what is the
 *     sky doing where the user is", so this is the only rung that answers the actual question.
 *  2. **A coordinate carried by the rows about to be drawn** — a device site is fetch provenance, not
 *     a weather location (see [ObservationSiteMerge]), so this is a fallback for installs with no
 *     configured location, never a preference.
 *  3. **[Double.NaN]** — never a hardcoded coordinate. Degrades honestly downstream (sun shading
 *     falls back to `UNKNOWN_LOCATION`, IDW distance weights drop out) instead of silently
 *     rendering someone else's weather.
 *
 * Immediately after a move the configured site may hold fewer observations than the one just left.
 * Drawing fewer actual points is the correct outcome: the alternative is another location's frozen
 * readings labelled with the current time. The window is short — on the measured incident the
 * location handoff and the first fetch at the new site were eleven seconds apart.
 */
object BlendCentre {

    /** Which rung [resolve] landed on. Logged as `locSource=` so a paint can be audited after the fact. */
    enum class Source { CONFIGURED, DATA, NONE }

    data class Centre(val lat: Double, val lon: Double, val source: Source)

    /**
     * [configured] is the widget's configured location; [dataDerived] a coordinate carried by the
     * rows about to be drawn. Non-finite coordinates count as absent — `stored()` screens NaN but
     * not infinities, and every consumer of this centre treats a non-finite value as "no location".
     */
    fun resolve(
        configured: Pair<Double, Double>?,
        dataDerived: Pair<Double, Double>?,
    ): Centre {
        finite(configured)?.let { return Centre(it.first, it.second, Source.CONFIGURED) }
        finite(dataDerived)?.let { return Centre(it.first, it.second, Source.DATA) }
        return Centre(Double.NaN, Double.NaN, Source.NONE)
    }

    /**
     * True when both coordinates are known and sit further apart than the blend's own merge box, i.e.
     * the rows about to be drawn were fetched somewhere that shares **no** observations with where
     * the user is. [resolve] already picks the right centre, so this changes nothing on its own; it
     * names the upstream condition — an hourly list holding only far-site rows — that would otherwise
     * only be visible as a stale label hours later.
     */
    fun divergesBeyondMergeTolerance(
        configured: Pair<Double, Double>?,
        dataDerived: Pair<Double, Double>?,
    ): Boolean {
        val a = finite(configured) ?: return false
        val b = finite(dataDerived) ?: return false
        return abs(a.first - b.first) > ObservationSiteMerge.MERGE_TOLERANCE_DEG ||
            abs(a.second - b.second) > ObservationSiteMerge.MERGE_TOLERANCE_DEG
    }

    private fun finite(pair: Pair<Double, Double>?): Pair<Double, Double>? =
        pair?.takeIf { it.first.isFinite() && it.second.isFinite() }
}
