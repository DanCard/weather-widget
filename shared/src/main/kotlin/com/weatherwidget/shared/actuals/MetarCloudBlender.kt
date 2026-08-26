package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.HistoricalDataKind
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.shared.observations.CloudHourBucket
import com.weatherwidget.shared.observations.ObservationSourceMatcher
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.SpatialInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Blends per-station METAR sky-cover values into an actual cloud series whose points sit on the
 * stations' NATIVE report timestamps (~20-minute cadence at KNUQ, hourly at KSJC) — no time
 * bins, mirroring [ActualTemperatureSeriesBuilder]'s "blend once per distinct observation
 * timestamp" rule. See plans/260824-subhourly-metar-cloud-blend.md.
 *
 * Deliberately separate from the temperature builder otherwise: that machine carries
 * forecast-driven carry-forward, per-day dominance and the personal-station discount, none of
 * which apply to a quantity no station can extrapolate — a 13:47 METAR is an *instantaneous*
 * reading of the sky, and a station with no reading near a point cannot contribute one.
 *
 * Each emitted point marks a real observation's timestamp; the value is the IDW blend (1/d²,
 * near-zero snap) of the five (or fewer) nearest stations' reports, using the same
 * [SpatialInterpolator] arithmetic the temperature blend uses. Stations whose sky-condition
 * reporting is silent within [ANCHOR_TOLERANCE_MS] of the point are simply absent; the blend
 * proceeds with however many remain, down to one, and never reaches past the fetched station set
 * to make up the shortfall.
 */
object MetarCloudBlender {

    /**
     * How far a station's cloud-carrying report can sit from a candidate point and still anchor
     * that station's contribution there. Same 30-minute reach the hour bucket granted its reports
     * (CloudHourBucket.TOLERANCE_MS), so the binless blend sees exactly the readings the bucketed
     * one did — just placed at their true times instead of snapped to a mark. It is also why
     * [fromSiteRows]' ±30-minute read pad is unchanged: a candidate at the window start can still
     * anchor a report up to half an hour before it.
     */
    const val ANCHOR_TOLERANCE_MS = 30 * 60_000L

    private const val TAG = "MetarCloudBlender"

    /** Blend diagnostics for the permanent cloud-series log lines. */
    data class Stats(
        /** Stations with at least one sky-condition report in the window. */
        val stationsWithLayers: Int,
        /** Real stations present in the window that never carried a sky-condition report. */
        val stationsSkipped: Int,
        /**
         * Number of stations behind each emitted point, point timestamp -> width.
         *
         * Legacy name: keys are native report timestamps since the blend went binless
         * (plans/260824-subhourly-metar-cloud-blend.md), not hour starts.
         */
        val blendWidthByHour: Map<Long, Int>,
        /**
         * Anchored points saved by the cloud-carrier preference: the station's report nearest the
         * point's timestamp omitted sky condition, but another of its reports within the anchor
         * tolerance carried one. A rising value is normal (partial METARs); a value ~equal to
         * emitted points times station count points at a station whose sky-condition reporting
         * has mostly died.
         */
        val shadowedBuckets: Int = 0,
        /**
         * Station-points where an actual METAR was available and preferred over the ASOS 5-minute
         * samples that were nearer. A value near zero at an airport station means `rawMessage` is
         * not arriving (or the rows predate the column) and the curve has quietly reverted to
         * instantaneous ceilometer samples.
         */
        val metarPreferredBuckets: Int = 0,
    ) {
        /**
         * Compact single-line form for CLOUD_SERIES / BACKFILL_CLOUD. The width histogram separates
         * "every station is a PWS" (stationsWithLayers=0) from a thin-but-alive blend, without a
         * DB pull.
         */
        fun summary(): String {
            val widthHistogram = blendWidthByHour.values.groupingBy { it }.eachCount()
                .toSortedMap()
                .entries.joinToString(" ") { "w${it.key}=${it.value}" }
            return "stationsWithLayers=$stationsWithLayers stationsSkipped=$stationsSkipped" +
                (if (shadowedBuckets == 0) "" else " shadowed=$shadowedBuckets") +
                (if (metarPreferredBuckets == 0) "" else " metarPreferred=$metarPreferredBuckets") +
                (if (widthHistogram.isEmpty()) "" else " blendWidth=[$widthHistogram]")
        }
    }

    data class Result(
        /**
         * Point timestamp -> blended percent. For the station-observation blend ([isMetarBlend]
         * true) keys are NATIVE report timestamps — the series is binless, mirroring
         * ActualTemperatureSeriesBuilder's "no time-bucket thinning". The synthetic-provider
         * branches are hourly model history, so their keys stay hour marks.
         */
        val hours: Map<Long, Int>,
        val stats: Stats,
        /** False for non-NWS sources: their cloud actuals come from the synthetic backfill row. */
        val isMetarBlend: Boolean,
        val dominantContribution: BlendContribution? = null,
    )

    fun empty(isMetarBlend: Boolean) = Result(emptyMap(), Stats(0, 0, emptyMap()), isMetarBlend, dominantContribution = null)

    /**
     * Wraps a synthetic-station series (the non-NWS sources, whose cloud arrives via
     * [HistoricalActualsBackfill]) with empty blend stats — there is no station blend behind it.
     */
    fun synthetic(hours: Map<Long, Int>) = Result(hours, Stats(0, 0, emptyMap()), isMetarBlend = false, dominantContribution = null)

    /**
     * The shared source-aware half of the cloud-actuals read, so the Android and desktop DAOs
     * cannot disagree about which rows back an hour:
     *  - **NWS** blends its real METAR stations' own rows at read time ([blend]); nothing is ever
     *    written to a synthetic NWS station, so a `distanceKm=0` synthetic row cannot hijack it.
     *  - **every other source** reads the [HistoricalActualsBackfill] synthetic row only, pinned
     *    to that station: a future real-station cloud source cannot silently join the series
     *    without a deliberate change here.
     *
     * Owns the READ RANGE as well as the branch, because the two are one decision. The blend's
     * anchor tolerance reaches 30 minutes both ways, so a report just before [startMs] can still
     * anchor the first visible points; reading the bare window starves the leading edge of the
     * curve. That shape of bug shipped once — the Samsung fold's 1a-5a cloud graph started its
     * actual an hour late because KSJC's 00:30 METAR, which anchored the 01:00 point, was never
     * fetched. Leaving the pad in the DAOs would re-open exactly the Android/desktop divergence
     * this function exists to close, so the pad lives here and the DAOs supply only [readSiteRows].
     *
     * @param readSiteRows reads ONE physical site's observations for a raw-timestamp range,
     *   `start` inclusive to `end` exclusive (the DAO collapses the location box first). Called
     *   with the padded range; the emitted points stay bounded by the unpadded [startMs]/[endMs].
     */
    suspend fun fromSiteRows(
        startMs: Long,
        endMs: Long,
        sourceId: String,
        readSiteRows: suspend (start: Long, end: Long) -> List<ObservationReading>,
    ): Result {
        val source = WeatherSource.fromId(sourceId)
        // Which feed supplies THIS source's cloud. For a source that ships its own it is itself; a
        // forecast-only source borrows one, exactly as it already does for temperature.
        val provider = WeatherSource.fromId(ActualsProviderResolver.providerIdFor(source))
        // Gate before the database read so stale rows from an older build cannot resurrect an
        // unsupported curve. Asked of the PROVIDER, not the display source: "does this source have
        // cloud?" was the right question only while nothing could borrow one. Silurian's
        // include_past payload is still documented as forecast output rather than observations, and
        // is still excluded — as itself. It is excluded by never being an eligible provider.
        if (!provider.supportsCloudActuals) return empty(isMetarBlend = false)
        val readings = readSiteRows(
            CloudHourBucket.readStartMs(startMs),
            CloudHourBucket.readEndMs(endMs),
        )
        // A station-observation feed is blended across its real stations at read time. Routing on
        // the provider is what lets Open-Meteo draw a cloud curve at all: its own branch below reads
        // the `<SOURCE>_MAIN` backfill row, whose cloud HistoricalActualsBackfill deliberately
        // nulls, because that row is model output rather than a measurement.
        if (provider.historicalDataKind == HistoricalDataKind.STATION_OBSERVATION) {
            return blend(
                readings = readings,
                startMs = startMs,
                endMs = endMs,
                providerApi = provider.id,
                // NWS and Aviation Weather expose the same measured airport METARs through
                // independent transports. Keep their stored provenance separate, but let the
                // first-class METAR rows fill an NWS transport hole at read time. No other
                // provider gets this alias: METAR is supplemental to NWS, never generic filler.
                supplementalProviderApis = if (provider == WeatherSource.NWS) {
                    setOf(WeatherSource.METAR.id)
                } else {
                    emptySet()
                },
            )
        }
        if (provider == WeatherSource.TOMORROW_IO) {
            val hours = readings.asSequence()
                .filter {
                    ObservationSourceMatcher.matchesActualSource(
                        stationId = it.stationId,
                        api = it.api,
                        source = source,
                        allowGenericGap = false,
                    )
                }
                .filter { it.timestamp in CloudHourBucket.readStartMs(startMs) until CloudHourBucket.readEndMs(endMs) }
                .mapNotNull { row -> row.visibleCloud()?.let { row to it } }
                .groupBy { (row, _) -> CloudHourBucket.startMsOf(row.timestamp) }
                .filterKeys { it in startMs until endMs }
                .mapValues { (hourMs, samples) ->
                    val realtime = samples.filter { (row, _) -> TomorrowIoActuals.isRealtime(row.stationId) }
                    (realtime.ifEmpty { samples })
                        .minBy { (row, _) -> abs(row.timestamp - hourMs) }
                        .second
                }
            return synthetic(hours)
        }

        val station = HistoricalActualsBackfill.syntheticStationId(provider.id)
        val hours = readings.asSequence()
            // Both dimensions are required. A station id is not a provider namespace, and source
            // isolation must survive malformed/imported rows that reuse OPEN_METEO_MAIN.
            .filter { it.api == provider.id && it.stationId == station }
            // Re-bound to the unpadded window: the read above is widened by the bucketing
            // tolerance for the NWS branch's sake, but synthetic rows sit ON hour marks and need no
            // such reach. Without this, a caller whose endMs is mid-hour silently gains an actual
            // for the hour after it.
            .filter { it.timestamp in startMs until endMs }
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
     * @return native report timestamp -> blended percent, or an empty map when no station reported
     *   sky condition. Gaps stay gaps: no interpolation across silent stretches.
     */
    fun blend(
        readings: List<ObservationReading>,
        startMs: Long,
        endMs: Long,
        /**
         * Provenance of the rows to blend. Defaults to NWS, which is the only value that existed
         * while NWS was the sole station-observation cloud feed. A forecast-only source that borrows
         * a measured feed passes that feed's id here instead — METAR rows carry their own `api`, so
         * blending them under NWS's would be the provenance collapse the observations primary key
         * now exists to prevent.
         */
        providerApi: String = WeatherSource.NWS.id,
        /**
         * Provenance-preserving transports that may fill holes in [providerApi]. Currently only
         * `METAR` while NWS is primary; callers must opt in explicitly so a direct blend and every
         * other provider remain strictly source-isolated.
         */
        supplementalProviderApis: Set<String> = emptySet(),
    ): Result {
        val real = readings.asSequence()
            .filter { it.api == providerApi || it.api in supplementalProviderApis }
            .filter { it.stationId != "NWS_BLEND" }
            .filter { !ObservationSourceMatcher.isSyntheticBackfillStation(it.stationId, providerApi) }
            // TOTAL order, not `sortedBy { timestamp }` (a STABLE sort): same-timestamp rows must not
            // resolve differently based on the caller's query order. See ActualsRowOrderDeterminismTest.
            .sortedWith(
                compareBy(
                    { it.timestamp },
                    { it.stationId },
                    { it.locationLat },
                    { it.locationLon },
                    { if (it.api == providerApi) 0 else 1 },
                    { it.api },
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
        val usable = real
            .filter { !it.qcFailed }
            // The two transports can store the same physical station/report independently because
            // `api` is part of observation identity. Collapse that transport duplicate before the
            // station blend. A carrying row beats a partial row; otherwise the primary provider
            // wins, and the remaining fields make the choice total and query-order independent.
            .groupBy { it.stationId to it.timestamp }
            .values
            .map { duplicates ->
                duplicates.minWith(
                    compareBy<ObservationReading>(
                        { if ((it.cloudCoverLow ?: it.cloudCover) != null) 0 else 1 },
                        { if (it.api == providerApi) 0 else 1 },
                        { it.api },
                        { it.locationLat },
                        { it.locationLon },
                        { it.distanceKm },
                        { it.fetchedAt },
                    ),
                )
            }
            .sortedWith(
                compareBy(
                    { it.timestamp },
                    { it.stationId },
                    { it.locationLat },
                    { it.locationLon },
                    { it.api },
                ),
            )
        val byStation = usable.groupBy { it.stationId }
        // Carrier lists stay in the total order `usable` arrived in, so binary search and
        // deterministic ties hold.
        val carriersByStation = byStation.mapValues { (_, rows) ->
            rows.filter { (it.cloudCoverLow ?: it.cloudCover) != null }
        }

        // One candidate point per distinct cloud-carrying report timestamp: every emitted point
        // has at least one FRESH observation at its own time. Timestamps that carried no sky
        // condition (partial METARs) add no cloud information, so they are not candidates — the
        // temperature-side equivalent of "no time-bucket thinning".
        val candidateTimes = carriersByStation.values.asSequence()
            .flatten()
            .map { it.timestamp }
            .distinct()
            .filter { it in startMs until endMs }
            .sorted()
            .toList()

        val pointValues = LinkedHashMap<Long, Int>()
        val widthByPoint = mutableMapOf<Long, Int>()
        var shadowedAnchors = 0
        var metarPreferredAnchors = 0
        var latestDominantContribution: BlendContribution? = null
        for (ts in candidateTimes) {
            val contributions = byStation.mapNotNull { (id, rows) ->
                val carriers = carriersByStation.getValue(id)
                if (carriers.isEmpty()) return@mapNotNull null
                val inRange = carriersWithin(carriers, ts)
                if (inRange.isEmpty()) return@mapNotNull null
                // METAR first. `/stations/{id}/observations` interleaves the official METAR with
                // ASOS 5-minute rows, and the 5-minute rows are instantaneous single-point samples
                // that flip CLR<->SCT as the beam passes in and out of scattered cloud, while the
                // METAR's sky condition is a 30-minute assessment. Measured at KSJC 2026-08-21
                // 00:00-05:05, 60 of 66 5-minute samples read OVC and the isolated BKN dips at
                // 00:30 and 03:50 were the values the graph drew as real dips. The preference is a
                // CLASS, not a row: among several in-range METARs the freshest wins.
                val metars = inRange.filter { it.isMetar }
                if (metars.isNotEmpty()) metarPreferredAnchors++
                val anchor = (if (metars.isNotEmpty()) metars else inRange)
                    .minByOrNull { abs(it.timestamp - ts) } ?: return@mapNotNull null
                // Shadowing is measured against the report this station would have anchored a
                // naive nearest-to-time pick on: when that one omitted sky condition and an older
                // carrier rescued the point, count it.
                val nearestOverall = rows.minByOrNull { abs(it.timestamp - ts) }
                if (nearestOverall != null && (nearestOverall.cloudCoverLow ?: nearestOverall.cloudCover) == null) {
                    shadowedAnchors++
                }
                anchor to (anchor.cloudCoverLow ?: anchor.cloudCover)
            }
            val valueByDistance = contributions.mapNotNull { (reading, cloud) ->
                cloud?.let { reading.distanceKm to it.toFloat() }
            }
            if (valueByDistance.isEmpty()) continue
            val blended = SpatialInterpolator.interpolateIDWValues(valueByDistance) ?: continue
            pointValues[ts] = blended.roundToInt().coerceIn(0, 100)
            widthByPoint[ts] = valueByDistance.size

            val dominantEntry = contributions.minByOrNull { it.first.distanceKm }
            if (dominantEntry != null && dominantEntry.second != null) {
                val anchor = dominantEntry.first
                val rawCloud = dominantEntry.second!!
                latestDominantContribution = BlendContribution(
                    stationId = anchor.stationId,
                    stationName = anchor.stationName,
                    stationType = anchor.stationType,
                    distanceKm = anchor.distanceKm,
                    lastReadingMs = anchor.timestamp,
                    rawTemp = rawCloud.toFloat(),
                    resolvedTemp = rawCloud.toFloat(),
                    sourceKind = "observed",
                    ageMs = 0L,
                    weight = 1.0,
                    weightShare = 1.0,
                    isSynthetic = false,
                )
            }
        }

        if (pointValues.isEmpty() && stationsWithLayers > 0) {
            // Pathological state: cloud-carrying readings entered the blend but every candidate
            // came out empty. This failed silently on-device once already (stationsWithLayers=1,
            // actual=0, no curve), so dump the decisive facts — which rows carried cloud, which
            // candidates they made, and what each station would anchor — instead of leaving it
            // indistinguishable from "all stations are PWS". Fires only in this state, so a
            // healthy blend never pays.
            logDroppedBlend(real, usable, carriersByStation, candidateTimes, startMs, endMs)
        }

        return Result(
            hours = pointValues,
            stats = Stats(
                stationsWithLayers,
                stationsSkipped,
                widthByPoint,
                shadowedAnchors,
                metarPreferredAnchors,
            ),
            isMetarBlend = true,
            dominantContribution = latestDominantContribution,
        )
    }

    /**
     * The station's cloud-carrying reports within [ANCHOR_TOLERANCE_MS] of [ts], for anchoring.
     * [carriers] must be timestamp-sorted (it is — built from the totally-ordered `usable`).
     */
    private fun carriersWithin(
        carriers: List<ObservationReading>,
        ts: Long,
    ): List<ObservationReading> {
        var idx = carriers.binarySearch { it.timestamp.compareTo(ts) }
        if (idx < 0) idx = -idx - 1
        val out = ArrayList<ObservationReading>(8)
        var i = idx
        while (i < carriers.size && carriers[i].timestamp - ts <= ANCHOR_TOLERANCE_MS) {
            out.add(carriers[i])
            i++
        }
        i = idx - 1
        while (i >= 0 && ts - carriers[i].timestamp <= ANCHOR_TOLERANCE_MS) {
            out.add(carriers[i])
            i--
        }
        return out
    }

    private fun logDroppedBlend(
        real: List<ObservationReading>,
        usable: List<ObservationReading>,
        carriersByStation: Map<String, List<ObservationReading>>,
        candidateTimes: List<Long>,
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
                "${it.stationId}@${it.timestamp}" +
                    "(inWindow=${it.timestamp in startMs until endMs},qc=${it.qcFailed},d=${it.distanceKm})"
            }
        val candidateDetail = candidateTimes.take(6).joinToString(" ") { ts ->
            val anchors = carriersByStation.entries.joinToString(",") { (id, carriers) ->
                val nearest = carriers.minByOrNull { abs(it.timestamp - ts) }
                val age = nearest?.let { abs(it.timestamp - ts) }
                "$id->anchor@${nearest?.timestamp}(age=${age},within=${age != null && age <= ANCHOR_TOLERANCE_MS})"
            }
            "$ts{$anchors}"
        }
        Log.w(
            TAG,
            "METAR_BLEND_DROPPED window=$startMs..$endMs real=${real.size} usable=${usable.size} " +
                "candidates=${candidateTimes.size} stations=[$perStation] cloudRows=[$cloudRows] anchors=[$candidateDetail]",
        )
    }
}
