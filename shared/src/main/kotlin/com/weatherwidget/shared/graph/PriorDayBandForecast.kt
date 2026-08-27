package com.weatherwidget.shared.graph

/**
 * The frozen "what were this hour's mid/high cloud bands predicted to be, ~24h beforehand" series.
 *
 * The bands cannot use [PriorDayCloudForecast]'s source. Probed 2026-08-27 at three locations and
 * two lookbacks, Open-Meteo's Previous Runs API serves `cloud_cover_previous_day1` (192/192
 * non-null) but returns **all nulls** for `cloud_cover_low_previous_day1`,
 * `cloud_cover_mid_previous_day1` and `cloud_cover_high_previous_day1`. It accepts the names and
 * returns their keys; it simply has no band data to give, and never did — the app's own frozen
 * low-cloud curve produced nothing from the moment `f9a05d26` switched it to the low variable.
 *
 * So the bands' frozen forecast comes from our own snapshots instead: `hourly_forecast_history`
 * under the real `OPEN_METEO` source id, which captures roughly 76 prediction buckets a day at
 * every lead time and has carried the band columns since 2026-08-26.
 *
 * The trade-off is real and is handled rather than hidden: Previous Runs backfills a complete
 * series whether or not the app was running, while our snapshots have holes wherever it was off a
 * day before the hour. A hole returns no entry, which leaves [CloudPoint.isFrozenBands] false, the
 * forecast falling back to the live row, and the render declining to claim an accuracy comparison
 * it cannot make.
 */
object PriorDayBandForecast {

    /** Matches [PriorDayCloudForecast.LEAD_HOURS] — the same "a day ahead" claim. */
    val LEAD_MS = PriorDayCloudForecast.LEAD_MS

    /**
     * How much longer than [LEAD_MS] a snapshot may have been made and still count.
     *
     * A prediction from five days out is a genuine prediction, but it is not the one the graph
     * claims to be showing, and grading it as "yesterday's forecast" would overstate the miss.
     * Two days is the widest lead that still reads as "roughly a day ahead" after an ordinary
     * overnight gap in fetches.
     */
    const val MAX_LEAD_MS = 48L * 3_600_000L

    /** One stored prediction: which hour it is about, when it was made, and what it said. */
    data class BandSnapshot(
        val hourMs: Long,
        val bucketMs: Long,
        val bands: CloudBands,
    )

    /**
     * Reduces stored snapshots to one frozen prediction per target hour: the **most recent**
     * snapshot made at least [LEAD_MS] before that hour, and no more than [MAX_LEAD_MS] before it.
     *
     * Deliberately unlike [com.weatherwidget.shared.util.DailySnapshotSelector], which falls back
     * to the earliest candidate when nothing is old enough. That fallback suits the daily bar; here
     * it would file a prediction made two hours before the hour as a day-ago forecast. An hour with
     * no qualifying snapshot is omitted, never approximated.
     *
     * Snapshots carrying no band at all are dropped: a row that reports neither band is not a
     * prediction of a clear sky.
     */
    fun select(snapshots: List<BandSnapshot>): Map<Long, CloudBands> =
        snapshots.asSequence()
            .filter { !it.bands.isEmpty }
            .filter { it.bucketMs <= it.hourMs - LEAD_MS }
            .filter { it.bucketMs >= it.hourMs - MAX_LEAD_MS }
            .groupBy { it.hourMs }
            .mapValues { (_, candidates) -> candidates.maxBy { it.bucketMs }.bands }
}
