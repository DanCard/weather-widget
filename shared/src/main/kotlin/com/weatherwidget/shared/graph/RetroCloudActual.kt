package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast

/**
 * The retro-corrected "what this hour actually turned out to be" cloud-cover series.
 *
 * Why it exists: the actual curve used to be *inferred* at render time, by asking whether a live
 * `hourly_forecasts` row's `fetchedAt` postdated the end of its own hour
 * ([CloudSeriesBuilder.isRetroCorrected]). That inference is only sound where past rows are
 * rewritten in place, which is true on desktop and **deliberately false on Android**:
 * `HourlyForecastStore` drops everything older than `now - 1h` before writing, with the original
 * intent "so we don't retroactively overwrite older predictions with newly fetched re-analysis
 * history, preserving the difference between what was forecast vs actuals."
 *
 * Those two bounds are exactly complementary — a row survives the write filter only if it is less
 * than an hour old, and satisfies the render gate only if it is at least an hour old — so on
 * Android the qualifying set is empty by construction. Measured across every stored Open-Meteo
 * hourly row: Samsung Fold 0 of 6,899 retro-corrected, Pixel 7 Pro 0 of 1,447, both with a maximum
 * `fetchedAt - dateTime` of 59 minutes; desktop 1,858 of 2,376, maximum 8 days. The Android cloud
 * graph could therefore never draw an actual curve, and — because the dashed forecast is gated on
 * `hasFrozen && hasActual` — never draw the dashes either.
 *
 * The fix is to stop inferring. Elapsed hours are filed explicitly as actuals under [SOURCE_ID],
 * alongside [PriorDayCloudForecast]'s frozen predictions, in `hourly_forecast_history` — the same
 * table, the same synthetic-source trick, no schema change on either platform. The write side still
 * uses [CloudSeriesBuilder.isRetroCorrected] to decide which hours qualify, which is where that
 * question belongs: at write time the payload is known to be post-hoc, whereas at read time
 * `fetchedAt` was only ever a proxy for "has this device refetched since the hour ended".
 *
 * Rows carry **low** cloud cover, not total. See [CloudSeriesBuilder] for why.
 */
object RetroCloudActual {

    /** Never a display source; never passed to a source-scoped loader. */
    const val SOURCE_ID = "OPEN_METEO_RETRO"

    /**
     * The `timestampToGroupPredictions` a row for [hourStartMs] is filed under. An actual has no
     * "when was this predicted" — it is the hour itself — so the hour start doubles as the bucket.
     * Deterministic, so refetching the same hour REPLACEs in place rather than accumulating rows.
     */
    fun bucketFor(hourStartMs: Long): Long = hourStartMs

    /**
     * How long after an hour ends before its value is trusted as an actual.
     *
     * An hour ending is NOT the same as its value settling. Measured 2026-08-20 for the 20:00 hour,
     * which ended at 21:00: a fetch at 21:37 still returned 6% — the pre-hour forecast — while
     * fetches at 21:39 and 21:43 returned 86%, and the surface stations confirmed the later value
     * (KNUQ went clear -> 13% -> 38% at 900ft through that hour as the evening marine layer pushed
     * in, KSFO was 69-100% at 300-700ft). Hours 15:00-19:00 were identical across all three fetches,
     * so only the newest hour is volatile.
     *
     * Filing at hour-end+0 therefore publishes the forecast as the actual for ~40 minutes, which is
     * the exact failure this series exists to prevent. Two hours clears the observed lag with room
     * for a slower model cycle. The cost is only that the right edge of the actual curve stops two
     * hours short of now — honest, since those hours genuinely have not settled.
     */
    const val SETTLE_MS = 2 * 3_600_000L

    /**
     * True when the hour starting at [hourStartMs] has both ended and had time to settle.
     *
     * [CloudSeriesBuilder.isRetroCorrected] is the "has it ended" half; this adds [SETTLE_MS].
     */
    fun hasSettled(hourStartMs: Long, nowMs: Long): Boolean =
        CloudSeriesBuilder.isRetroCorrected(hourStartMs, nowMs - SETTLE_MS)

    /**
     * The hours of a just-fetched `past_days` payload that qualify as settled actuals, as hour
     * start to **low** cloud cover percent.
     *
     * An hour qualifies once it has ended AND settled — see [hasSettled] for why the second half is
     * not redundant. Hours with no `cloud_cover_low` are omitted, not zeroed: a missing actual must
     * stay missing rather than paint a clear sky nobody observed.
     *
     * Re-filing is expected: [bucketFor] is deterministic, so a later fetch REPLACEs an hour in
     * place and any further revision is picked up rather than accumulating duplicate rows.
     */
    fun qualifyingActuals(hourly: List<HourlyForecast>, nowMs: Long): Map<Long, Int> =
        hourly.asSequence()
            .filter { hasSettled(it.dateTime, nowMs) }
            .mapNotNull { hour -> hour.cloudCoverLow?.let { hour.dateTime to it.coerceIn(0, 100) } }
            .toMap()
}
