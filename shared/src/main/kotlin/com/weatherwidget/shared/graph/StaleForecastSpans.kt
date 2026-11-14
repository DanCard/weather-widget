package com.weatherwidget.shared.graph

/**
 * Which started hours drew a forecast that was already old when the hour began, and where on the x
 * axis to dim them. Both platforms, all three hourly graphs (temperature, rain chance, cloud cover).
 *
 * A started hour stays frozen at the last fetch at or before it (`SettledForecastPick`, "Freeze wins
 * always"). A device that could not fetch — desktop asleep 19:33 to 03:10 on 2026-11-04 — therefore
 * draws an evening forecast for the night (NWS 4% at 3am beside the Fold's 25%). The value is kept;
 * the line is dimmed so it reads as stale. See plans/261104-dim-stale-pre-hour-forecasts.md.
 */
object StaleForecastSpans {
    /** A started hour's forecast fetched more than this long before the hour began is stale. */
    const val STALE_AGE_MS = 3L * 3_600_000L

    /** Multiplies the forecast line paint's alpha over stale spans. */
    const val DIM_ALPHA_FACTOR = 0.4f

    /**
     * True when the hour has started and its forecast came from a real fetch ([fetchedAtMs] > 0;
     * synthesized rows carry 0) more than [STALE_AGE_MS] before it. A fetch after the hour began
     * (no pre-hour fetch existed) is not stale by this measure.
     */
    fun isStale(hourMs: Long, fetchedAtMs: Long, nowMs: Long): Boolean =
        hourMs < nowMs && fetchedAtMs > 0L && hourMs - fetchedAtMs > STALE_AGE_MS

    /** The hour starts among [forecasts] (stitched, one per hour) whose forecast is stale at [nowMs]. */
    fun staleHourStarts(forecasts: List<com.weatherwidget.data.model.HourlyForecast>, nowMs: Long): Set<Long> =
        // madeAt: the NWS issuance when known, so a late fetch of an old issuance still reads as old.
        forecasts.filter { isStale(it.dateTime, it.madeAt, nowMs) }.mapTo(HashSet()) { it.dateTime }

    data class Span(val start: Float, val end: Float, val stale: Boolean)

    /**
     * Splits [left]..[right] into runs of fresh and stale hours. Point i owns the x range between the
     * midpoints to its neighbours (the first reaches [left], the last [right]), so a dimmed stretch
     * fades in halfway between a fresh hour and a stale one. Equal neighbours merge; with no stale
     * hour the result is a single fresh span.
     */
    fun spans(xs: List<Float>, stale: List<Boolean>, left: Float, right: Float): List<Span> {
        require(xs.size == stale.size) { "xs (${xs.size}) and stale (${stale.size}) differ in size" }
        if (xs.isEmpty() || stale.none { it }) return listOf(Span(left, right, false))
        val out = mutableListOf<Span>()
        for (i in xs.indices) {
            val start = if (i == 0) left else (xs[i - 1] + xs[i]) / 2f
            val end = if (i == xs.lastIndex) right else (xs[i] + xs[i + 1]) / 2f
            val last = out.lastOrNull()
            if (last != null && last.stale == stale[i]) {
                out[out.lastIndex] = last.copy(end = end)
            } else {
                out += Span(start, end, stale[i])
            }
        }
        return out
    }

    /** [alpha] (0..255) for a span: unchanged when fresh, scaled by [DIM_ALPHA_FACTOR] when stale. */
    fun alphaFor(alpha: Int, stale: Boolean): Int =
        if (stale) (alpha * DIM_ALPHA_FACTOR).toInt() else alpha
}
