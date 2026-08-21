package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.CloudHourBucket
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.SpatialInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Blends per-station METAR sky-cover values into an hour-keyed actual cloud series for NWS.
 *
 * Deliberately separate from [ActualTemperatureSeriesBuilder]: that machine carries forecast-driven
 * carry-forward, per-day dominance and the personal-station discount, none of which apply to a
 * quantity no station can extrapolate — a 13:47 METAR is an *instantaneous* reading of the sky, and
 * a station with no reading this hour cannot contribute one.
 *
 * The value at each hour is the IDW blend (1/d², near-zero snap) of the five (or fewer) nearest
 * stations' reports, using the same [SpatialInterpolator] arithmetic the temperature blend uses.
 * Stations that reported no sky condition are simply absent from an hour; the blend proceeds with
 * however many remain, down to one, and never reaches past the fetched station set to make up the
 * shortfall.
 */
object MetarCloudBlender {

    private const val TAG = "MetarCloudBlender"

    /** Blend diagnostics for the permanent cloud-series log lines. */
    data class Stats(
        /** Stations with at least one sky-condition report in the window. */
        val stationsWithLayers: Int,
        /** Real stations present in the window that never carried a sky-condition report. */
        val stationsSkipped: Int,
        /** Number of stations behind each emitted hour, hour-start epoch ms -> width. */
        val blendWidthByHour: Map<Long, Int>,
        /**
         * Buckets saved by the cloud-carrier preference: the station's report nearest the hour
         * omitted sky condition, but another of its reports in the bucket carried one. A rising
         * value is normal (partial METARs); a value ~equal to emitted hours points at a station
         * whose sky-condition reporting has mostly died.
         */
        val shadowedBuckets: Int = 0,
    ) {
        /**
         * Compact single-line form for CLOUD_SERIES / BACKFILL_CLOUD. The width histogram separates
         * "every station is a PWS" (stationsWithLayers=0) from a thin-but-alive blend, without a
         * DB pull.
         */
        fun summary(): String {
            val widthHistogram = blendWidthByHour.values.groupingBy { it }.eachCount()
                .toSortedMap()
                .entries.joinToString(" ") { "w${it.key}=${it.value}h" }
            return "stationsWithLayers=$stationsWithLayers stationsSkipped=$stationsSkipped" +
                (if (shadowedBuckets == 0) "" else " shadowed=$shadowedBuckets") +
                (if (widthHistogram.isEmpty()) "" else " blendWidth=[$widthHistogram]")
        }
    }

    data class Result(
        val hours: Map<Long, Int>,
        val stats: Stats,
        /** False for non-NWS sources: their cloud actuals come from the synthetic backfill row. */
        val isMetarBlend: Boolean,
    )

    fun empty(isMetarBlend: Boolean) = Result(emptyMap(), Stats(0, 0, emptyMap()), isMetarBlend)

    /**
     * Wraps a synthetic-station series (the non-NWS sources, whose cloud arrives via
     * [HistoricalActualsBackfill]) with empty blend stats — there is no station blend behind it.
     */
    fun synthetic(hours: Map<Long, Int>) = Result(hours, Stats(0, 0, emptyMap()), isMetarBlend = false)

    /**
     * The shared source-aware half of the cloud-actuals read, so the Android and desktop DAOs
     * cannot disagree about which rows back an hour:
     *  - **NWS** blends its real METAR stations' own rows at read time ([blend]); nothing is ever
     *    written to a synthetic NWS station, so a `distanceKm=0` synthetic row cannot hijack it.
     *  - **every other source** reads the [HistoricalActualsBackfill] synthetic row only, pinned
     *    to that station: a future real-station cloud source cannot silently join the series
     *    without a deliberate change here.
     *
     * Callers pass ONE physical site's readings (their DAO collapses the location box first).
     */
    fun fromSiteRows(
        readings: List<ObservationReading>,
        startMs: Long,
        endMs: Long,
        sourceId: String,
    ): Result {
        if (WeatherSource.fromId(sourceId) == WeatherSource.NWS) {
            return blend(readings, startMs, endMs)
        }
        val station = HistoricalActualsBackfill.syntheticStationId(sourceId)
        val hours = readings.asSequence()
            .filter { it.stationId == station }
            .mapNotNull { row -> row.visibleCloud()?.let { row.timestamp to it } }
            .toMap()
        return synthetic(hours)
    }

    /** What the actual curve draws for a row: the low layer where present, else the total. */
    private fun ObservationReading.visibleCloud(): Int? = cloudCoverLow ?: cloudCover

    /**
     * @param readings already site- and source-scoped real NWS station rows (the DAO collapses the
     *   location box to one physical site first). Synthetic `NWS_BLEND` and `<SOURCE>_MAIN` rows are
     *   excluded here too, so a blended or backfilled row can never masquerade as a station.
     * @return hour-start epoch ms -> blended percent, or an empty map when no station reported sky
     *   condition. Gaps stay gaps: no interpolation across empty hours.
     */
    fun blend(
        readings: List<ObservationReading>,
        startMs: Long,
        endMs: Long,
    ): Result {
        val real = readings.asSequence()
            .filter { it.api == WeatherSource.NWS.id }
            .filter { it.stationId != "NWS_BLEND" }
            .filter { !ObservationSourceMatcher.isSyntheticBackfillStation(it.stationId, WeatherSource.NWS.id) }
            // TOTAL order, not `sortedBy { timestamp }` (a STABLE sort): same-timestamp rows must not
            // resolve differently based on the caller's query order. See ActualsRowOrderDeterminismTest.
            .sortedWith(
                compareBy(
                    { it.timestamp },
                    { it.stationId },
                    { it.locationLat },
                    { it.locationLon },
                ),
            )
            .toList()

        if (real.isEmpty()) return empty(isMetarBlend = true)

        val stationIds = real.map { it.stationId }.distinct()
        val stationsWithLayers = stationIds.count { id ->
            real.any { it.stationId == id && it.cloudCoverLow != null }
        }
        val stationsSkipped = stationIds.size - stationsWithLayers

        // QC-failed readings are stored for the stations UI but are never blend inputs, matching the
        // temperature blend's rule.
        val usable = real.filter { !it.qcFailed }

        // Bucket by round-to-nearest hour (§2.5): a 13:47 METAR is an instantaneous reading 13 minutes
        // from 14:00 and 47 from 13:00, and the graph plots instantaneous values at hour marks.
        // Flooring to the hour instead dropped KPAO (which reports at :47) almost entirely.
        val byBucket = usable
            .groupBy { CloudHourBucket.startMsOf(it.timestamp) }
            .filterKeys { it in startMs until endMs }

        val hourValues = LinkedHashMap<Long, Int>()
        val widthByHour = mutableMapOf<Long, Int>()
        var shadowedBuckets = 0
        for ((hourMs, bucketReadings) in byBucket) {
            val byStation = bucketReadings.groupBy { it.stationId }
            // Each station contributes ONE value: the reading nearest the top of the hour. The bucket
            // input is already total-ordered, so a tie resolves deterministically to the first row.
            // When the nearest report omitted sky condition (a partial METAR, or a row stored before
            // cloud parsing existed), fall back to the nearest report in the bucket that DID carry
            // one instead of dropping the station's hour — the fallback stays inside the same
            // ±30-minute bucketing tolerance the round-to-hour rule already accepts.
            val contributions = byStation.mapNotNull { (_, rows) ->
                val nearest = rows.minByOrNull { abs(it.timestamp - hourMs) } ?: return@mapNotNull null
                if ((nearest.cloudCoverLow ?: nearest.cloudCover) != null) {
                    return@mapNotNull nearest to (nearest.cloudCoverLow ?: nearest.cloudCover)
                }
                val fallback = rows
                    .filter { (it.cloudCoverLow ?: it.cloudCover) != null }
                    .minByOrNull { abs(it.timestamp - hourMs) }
                if (fallback != null) shadowedBuckets++
                fallback?.let { it to (it.cloudCoverLow ?: it.cloudCover) }
            }
            val valueByDistance = contributions.mapNotNull { (reading, cloud) ->
                cloud?.let { reading.distanceKm.toFloat() to it.toFloat() }
            }
            if (valueByDistance.isEmpty()) continue
            val blended = SpatialInterpolator.interpolateIDWValues(valueByDistance) ?: continue
            hourValues[hourMs] = blended.roundToInt().coerceIn(0, 100)
            widthByHour[hourMs] = valueByDistance.size
        }

        if (hourValues.isEmpty() && stationsWithLayers > 0) {
            // Pathological state: cloud-carrying readings entered the blend but every bucket came
            // out empty. This failed silently on-device once already (stationsWithLayers=1,
            // actual=0, no curve), so dump the decisive facts — which rows carried cloud, where
            // they bucketed, and what each bucket selected — instead of leaving it indistinguishable
            // from "all stations are PWS". Fires only in this state, so a healthy blend never pays.
            logDroppedBlend(real, usable, byBucket, startMs, endMs)
        }

        return Result(
            hours = hourValues,
            stats = Stats(stationsWithLayers, stationsSkipped, widthByHour, shadowedBuckets),
            isMetarBlend = true,
        )
    }

    private fun logDroppedBlend(
        real: List<ObservationReading>,
        usable: List<ObservationReading>,
        byBucket: Map<Long, List<ObservationReading>>,
        startMs: Long,
        endMs: Long,
    ) {
        val perStation = real.groupBy { it.stationId }
            .entries.joinToString(" ") { (id, rows) ->
                val withCloud = rows.count { (it.cloudCoverLow ?: it.cloudCover) != null }
                "$id[rows=${rows.size} cloud=$withCloud qc=${rows.count { it.qcFailed }}]"
            }
        val cloudRows = real.filter { (it.cloudCoverLow ?: it.cloudCover) != null }
            .take(6)
            .joinToString(" ") {
                val bucket = CloudHourBucket.startMsOf(it.timestamp)
                "${it.stationId}@${it.timestamp}->bucket=$bucket" +
                    "(inWindow=${bucket in startMs until endMs},qc=${it.qcFailed},d=${it.distanceKm})"
            }
        val bucketDetail = byBucket.entries.take(6).joinToString(" ") { (hourMs, rows) ->
            val picks = rows.groupBy { it.stationId }
                .entries.joinToString(",") { (id, stationRows) ->
                    val chosen = stationRows.minByOrNull { abs(it.timestamp - hourMs) }
                    "$id:${stationRows.size}rows->pick@${chosen?.timestamp} cloud=${chosen?.cloudCoverLow ?: chosen?.cloudCover}"
                }
            "$hourMs{$picks}"
        }
        Log.w(
            TAG,
            "METAR_BLEND_DROPPED window=$startMs..$endMs real=${real.size} usable=${usable.size} " +
                "buckets=${byBucket.size} stations=[$perStation] cloudRows=[$cloudRows] buckets=[$bucketDetail]",
        )
    }
}
