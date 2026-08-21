package com.weatherwidget.shared.graph

/**
 * When a fetched cloud value is trustworthy enough to be filed as an *actual*.
 *
 * Cloud actuals live in `observations`, written by
 * [com.weatherwidget.shared.actuals.HistoricalActualsBackfill] alongside temperature, condition and
 * precipitation. This object owns the one question that is specific to cloud.
 *
 * ## The answer is "as soon as the hour has ended"
 *
 * [SETTLE_MS] is 0, so [hasSettled] reduces to [CloudSeriesBuilder.isRetroCorrected]. The seam is
 * kept because the question is real and was briefly answered differently; the history is worth more
 * than the indirection costs.
 *
 * ## Why a lag was tried, and why it was wrong
 *
 * Open-Meteo revises an elapsed hour's cloud *after* the hour ends, once a later run has assimilated
 * observations. Measured 2026-08-20 for the 20:00 hour, which ended at 21:00: a fetch at 21:37
 * returned 6% — the pre-hour forecast — while 21:39 and 21:43 returned 86%, which the surface
 * stations backed (KNUQ went clear -> 13% @ 900ft -> 38% through that hour as the evening marine
 * layer pushed in; KSFO 69-100% @ 300-700ft). Two devices that fetched either side of that
 * correction disagreed by 80 points on the graph's rightmost point.
 *
 * A 2-hour lag fixed that and broke the feature. An hour counts as settled only when
 * `hourStart <= now - 1h - SETTLE_MS`, and the widget's 4-hour zoom shows roughly `now-3h .. now+1h`,
 * so **no hour inside the visible window could ever qualify** — the actual curve vanished at the
 * zoom level actually in use, while remaining visible on desktop's wider window. Measured at 22:54
 * with a 20:00-00:00 window: 0h lag gives 2 settled hours (enough to draw), 1h gives 1, 2h gives 0.
 *
 * A gate expressed in absolute time was set by measuring the data and never checked against how much
 * past the graph actually displays.
 *
 * ## Why 0 is defensible, not just convenient
 *
 * Temperature actuals from the same payload carry **no** settling gate at all and are subject to the
 * same retro-correction. Cloud was being held to a stricter standard on the strength of one dramatic
 * example. And the error is transient, not sticky: `observations` is keyed on
 * `(stationId, timestamp)`, so the next fetch REPLACEs the hour in place and any revision is picked
 * up. The exposure is roughly 40 minutes of a provisional value at the rightmost point, against a
 * permanently absent curve — which is the worse of the two, because an absent curve is
 * indistinguishable from a broken one.
 *
 * If this is revisited, the constraint to respect is: **whatever lag is chosen must leave at least
 * two settled hours inside the narrowest supported zoom window**, or the curve cannot be drawn at
 * all (the renderer needs two points).
 */
object CloudActualSettling {

    /**
     * Additional wait after an hour ends before its cloud value is trusted.
     *
     * Zero — see the class note. Raising this above `narrowest_window_past_span - 2h` makes the
     * actual curve undrawable at that zoom.
     */
    const val SETTLE_MS = 0L

    /**
     * True when the hour starting at [hourStartMs] has ended as of [nowMs] (plus [SETTLE_MS]).
     *
     * Applies unchanged to sub-hourly readings: nothing here is hour-aligned.
     */
    fun hasSettled(hourStartMs: Long, nowMs: Long): Boolean =
        CloudSeriesBuilder.isRetroCorrected(hourStartMs, nowMs - SETTLE_MS)
}
