package com.weatherwidget.shared.actuals

import com.weatherwidget.shared.util.TempUtils

/**
 * Picks the daily_history row that supplies the "API actual" (apiHighTemp/apiLowTemp pair) for
 * one source/date, out of the proximity-box candidates.
 *
 * One physical site can persist as several same-site fragments (legacy un-quantized location
 * keys alongside the [com.weatherwidget.data.local.LocationMatch.quantize] grid), and a fragment
 * can hold a PARTIAL pair — e.g. the NWS gridpoint response still carries yesterday's
 * maxTemperature shortly after midnight while yesterday's minTemperature window has already
 * rolled off, leaving apiLowTemp null. Picking the nearest fragment by source alone and only
 * THEN checking for nulls lets such a partial row shadow a complete fragment sitting a few
 * metres away, and the "API actual" disappears from the forecast-history view (observed on
 * Pixel/Samsung/emulator for the same date). So: nearest-first ordering, but the first fragment
 * with a COMPLETE pair wins.
 *
 * Generic over the row type because Android's `DailyHistoryEntity` (Room, :app) and desktop's
 * `DailyHistory` can't both be referenced from :shared; callers pass field extractors.
 */
object ApiActualPicker {

    /**
     * @return the nearest row matching [sourceId] with non-null api high AND low, or null when
     * no fragment has a complete pair.
     */
    fun <T> pickNearestComplete(
        rows: List<T>,
        lat: Double,
        lon: Double,
        sourceId: String,
        source: (T) -> String,
        locationLat: (T) -> Double,
        locationLon: (T) -> Double,
        apiHigh: (T) -> Float?,
        apiLow: (T) -> Float?,
    ): T? =
        rows.filter { source(it) == sourceId }
            .sortedBy { TempUtils.distanceSq(locationLat(it), locationLon(it), lat, lon) }
            .firstOrNull { apiHigh(it) != null && apiLow(it) != null }
}
