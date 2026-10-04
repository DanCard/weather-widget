package com.weatherwidget.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.local.toReading
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.DailyActualsAssembler
import com.weatherwidget.shared.actuals.DailyActualsSource
import com.weatherwidget.shared.actuals.DailyHistoryWriter
import com.weatherwidget.widget.DailyActualsBySource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.handlers.GraphDataLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import com.weatherwidget.shared.observations.ActualsProviderResolver

private const val TAG = "DailyActualsStore"

/**
 * Only write a DAILY_RECOMPUTE_PERF row when one day's recompute is genuinely slow. The recompute
 * walks up to nine days per sync, so an ungated row per day would itself become app_logs bloat.
 */
private const val DAY_RECOMPUTE_SLOW_MS = 250L

/** Log tag AND the `setprop log.tag.<...>` switch that turns the extrema window diagnostic on. */
private const val EXTREMA_WINDOW_DIAG_TAG = "EXTREMA_WINDOW_DIAG"

/**
 * Cap on tracked (day, location) signatures. Retention keeps ~10 days and a device visits few
 * places, so this is never reached in normal use; it exists so a pathological run of location
 * changes cannot grow the map without bound. Cleared wholesale rather than evicted — the only cost
 * of a miss is one recompute.
 */
private const val MAX_TRACKED_DAYS = 256
private const val DAYTIME_COVERAGE_HOUR = 14

@VisibleForTesting
/**
 * The observation `api` whose rows actually built [rowSource]'s daily blend: the resolved actuals
 * provider, which for a redirected source is another feed entirely (ActualsAggregator drops a
 * redirected source's own rows). DAILY_HISTORY_BLEND keyed its station list on the row's own id,
 * so on 2026-09-28 Open-Meteo's Warsaw row — built from 8 Synoptic stations — logged
 * `stations=[OPEN_METEO_MAIN]` and hid that it could not exist until Synoptic answered.
 */
internal fun blendInputApi(
    rowSource: String,
    /** Null = the app's installed actuals-provider preferences. */
    preference: ((WeatherSource) -> WeatherSource?)? = null,
): String {
    val source = WeatherSource.entries.firstOrNull { it.id == rowSource } ?: return rowSource
    return if (preference == null) {
        ActualsProviderResolver.providerIdFor(source)
    } else {
        ActualsProviderResolver.providerIdFor(source, preference)
    }
}

internal fun pastDayLacksAfternoonCoverage(
    obsTimestampsMs: List<Long>,
    date: LocalDate,
    zone: ZoneId,
    today: LocalDate,
    daytimeHour: Int = DAYTIME_COVERAGE_HOUR,
): Boolean {
    if (!date.isBefore(today) || obsTimestampsMs.isEmpty()) return false
    return obsTimestampsMs.none { ms ->
        Instant.ofEpochMilli(ms).atZone(zone).hour >= daytimeHour
    }
}

@Singleton
class DailyActualsStore @Inject constructor(
    private val observationDao: ObservationDao,
    private val dailyHistoryDao: DailyHistoryDao,
    private val appLogDao: AppLogDao,
    private val hourlyForecastDao: HourlyForecastDao,
    private val personalStationWeightProvider: PersonalStationWeightProvider,
    /**
     * Last observation signature a day was successfully reduced from, keyed by (day, quantized
     * location). Was a within-process map on the reasoning that losing it on process death costs
     * "exactly one redundant recompute" — which turned out to be 12 s of cold CPU at the worst
     * moment (see [ReducedSignatureStore]). Hilt supplies the persisted one; the in-memory default
     * keeps manual construction and tests unchanged.
     *
     * Concurrent because recomputes for different days overlap on the sync's dispatcher.
     */
    private val reducedSignatures: ReducedSignatureStore = InMemoryReducedSignatureStore(),
) {
    suspend fun getDailyActualsWithLiveToday(
        latitude: Double,
        longitude: Double,
        hourlyForecasts: List<HourlyForecastEntity>,
        activeSourceList: List<String>,
    ): DailyActualsBySource {
        val sources = activeSourceList.map(WeatherSource::fromId)
        if (sources.isEmpty()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startDate = today.minusDays(30).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val endDate = today.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // Composition is shared with desktop (DailyActualsAssembler); this only reads and logs.
        val result = DailyActualsAssembler.assemble(
            activeSources = sources,
            pastRows = dailyHistoryDao.getExtremesInRange(startDate, endDate, latitude, longitude)
                .map { it.toDailyHistory() },
            yesterdayDonors = dailyHistoryDao.getMeasuredExtremesForDateAnySite(endDate)
                .map { it.toDailyHistory() },
            observations = observationDao
                .getObservationsInRange(
                    todayStartMs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS,
                    tomorrowMs,
                    latitude,
                    longitude,
                    // Unscoped: this pass indexes actuals for every configured source at once, which
                    // is precisely the case the DAO's note says must NOT be narrowed.
                    apis = null,
                )
                .map { it.toReading() },
            hourlyForecasts = hourlyForecasts.map { it.toHourlyForecast() },
            latitude = latitude,
            longitude = longitude,
            today = today,
            zone = zone,
            nowMs = System.currentTimeMillis(),
            personalStationWeight = personalStationWeightProvider.currentWeight(),
        )

        result.bySource.forEach { (source, byDate) ->
            byDate.values.filter { it.isActualsBorrowed }.forEach {
                Log.d(
                    TAG,
                    "PREVIOUS_SITE_HISTORY source=$source date=${it.toLocalDate()} km=${"%.0f".format(it.actualsBorrowedFromKm)} " +
                        "high=${it.computedHighTemp} low=${it.computedLowTemp} wholeRow=${it.borrowedWithoutLocalRow}",
                )
            }
        }
        val obsSpanSummary = result.todayObsSpan
            ?.let { (first, last) -> "${DailyActualsAssembler.formatLocal(first, zone)}..${DailyActualsAssembler.formatLocal(last, zone)}" }
            ?: "none"
        val liveSummary = result.live
            .joinToString("; ") { "${it.source}[blendedHigh=${it.high},blendedLow=${it.low},rows=${it.rows}]" }
            .ifEmpty { "none" }
        Log.d(
            TAG,
            "getDailyActualsWithLiveToday: date=$today lat=$latitude lon=$longitude " +
                "todayObsRows=${result.todayObsRows} span=$obsSpanSummary live=[$liveSummary]",
        )
        result.suppressedTodayLows.forEach {
            Log.d(
                TAG,
                "TODAY_LOW_UNCOVERED source=${it.source} span=$obsSpanSummary " +
                    "suppressedLow=${it.low} lat=$latitude lon=$longitude",
            )
            appLogDao.log(
                "TODAY_LOW_UNCOVERED",
                "source=${it.source} span=$obsSpanSummary suppressedLow=${it.low} " +
                    "rows=${it.rows} at=$latitude,$longitude",
                "DEBUG",
            )
        }
        return result.bySource
    }

    internal suspend fun recomputeDailyExtremesFromStoredObservations(
        latitude: Double,
        longitude: Double,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        /** See [recomputeDailyExtremesForDay]; true for the repair paths. */
        force: Boolean = false,
    ) {
        val cutoffDate = LocalDate.now().minusDays(9)
        var current = startDate
        while (!current.isAfter(endDateInclusive)) {
            if (!current.isBefore(cutoffDate)) {
                recomputeDailyExtremesForDay(latitude, longitude, current, hourlyForecasts, force)
            } else {
                Log.d(TAG, "recomputeDailyExtremesFromStoredObservations: skipping pruned date $current")
            }
            current = current.plusDays(1)
        }
    }

    internal suspend fun recomputeDailyExtremesForDay(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        hourlyForecasts: List<HourlyForecastEntity>,
        /**
         * Recompute even when nothing has been written for the day since the last one.
         *
         * For the repair paths — the history screen and the backfillers — which call this precisely
         * because they distrust what is stored, and whose whole purpose the skip would defeat.
         */
        force: Boolean = false,
    ) {
        val dayStartMs = SystemClock.elapsedRealtime()
        val zone = ZoneId.systemDefault()
        val dateMillis = date.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        val startTs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // A day's extremes cannot move while its observations are unchanged. Measured 2026-09-06 over
        // three days on the Samsung, 6,805 of 7,468 per-day recomputes produced a byte-identical
        // result (91%) — the day was queried, blended and reduced, and only then found unchanged,
        // which is what DAILY_HISTORY_STABLE records. This skips that work.
        //
        // The signature is content-derived on purpose; see observationSignatureInRange for why the
        // obvious `MAX(fetchedAt) <= updatedAt` test was tried first and never fired once.
        val signatureKey = if (force) null else signatureKey(date, latitude, longitude)
        val signature = if (force) null else observationSignature(startTs, endTs, latitude, longitude)
        if (signatureKey != null && signature != null && reducedSignatures[signatureKey] == signature) {
            appLogDao.log(
                "DAILY_RECOMPUTE_SKIP",
                "date=$date sig=$signature at=$latitude,$longitude",
                "INFO",
            )
            return
        }
        val contextStartTs = startTs - ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextEndTs = endTs + ActualsAggregator.DAILY_BLEND_CONTEXT_MS
        val contextObs = observationDao.getObservationsInRange(
            contextStartTs,
            contextEndTs,
            latitude,
            longitude,
            // Unscoped: the daily recompute builds history for EVERY source in one pass.
            apis = null,
        )
        val afterQueryMs = SystemClock.elapsedRealtime()
        val dayObs = contextObs.filter { it.timestamp in startTs until endTs }
        if (dayObs.isEmpty()) return

        val effectiveHourly = hourlyForecasts.ifEmpty {
            GraphDataLoader.unifyToNearestSite(
                hourlyForecastDao.getHourlyForecasts(
                    contextStartTs,
                    contextEndTs,
                    latitude,
                    longitude,
                ),
                latitude,
                longitude,
            )
        }
        val afterHourlyMs = SystemClock.elapsedRealtime()
        val newExtremes = ObservationResolver
            .computeDailyExtremes(
                contextObs,
                effectiveHourly,
                latitude,
                longitude,
                personalStationWeightProvider.currentWeight(),
            )
            .filter { it.date == dateMillis }
        // NOTE: apiHighTemp/apiLowTemp are deliberately NOT derived here. The stored observation
        // pool is a mix of NWS API rows and Synoptic rows from the prefer-newest latest path, and
        // its API subset is too thinly sampled to carry a daily peak (measured at KNUQ: 17-24 of
        // the endpoint's 72 readings, under-reporting two days' maxima by 1.8 °F). NWS daily
        // extremes come from a dedicated complete pull instead — see NwsApiDailyActualsFetcher.
        // persistExtremes preserves whatever that writer stored.

        val afterExtremesMs = SystemClock.elapsedRealtime()
        logBlendBreakdown(date, dayObs, newExtremes, latitude, longitude)
        val afterBreakdownMs = SystemClock.elapsedRealtime()
        logExtremaWindowDiagnostic(
            date = date,
            zone = zone,
            startTs = startTs,
            endTs = endTs,
            dayObs = dayObs,
            effectiveHourly = effectiveHourly,
            latitude = latitude,
            longitude = longitude,
        )

        val afterDiagMs = SystemClock.elapsedRealtime()

        persistExtremes(date, dateMillis, newExtremes, latitude, longitude)
        // Recorded only after the day has actually been reduced and written, so a failure part way
        // through leaves the day open rather than marking it settled on work that did not finish.
        if (signatureKey != null && signature != null) {
            if (reducedSignatures.size >= MAX_TRACKED_DAYS) reducedSignatures.clear()
            reducedSignatures[signatureKey] = signature
        }
        // Stage timing for one day's recompute. SYNC_PERF's parent `actuals=` stage measured ~24s
        // on this 3-widget device without saying where it went; this splits it so the two pure
        // diagnostics (logBreakdown/logDiag) can be told apart from the real extremes computation.
        val endMs = SystemClock.elapsedRealtime()
        if (endMs - dayStartMs >= DAY_RECOMPUTE_SLOW_MS) {
            appLogDao.log(
                "DAILY_RECOMPUTE_PERF",
                "date=$date total=${endMs - dayStartMs}ms " +
                    "query=${afterQueryMs - dayStartMs}ms " +
                    "hourly=${afterHourlyMs - afterQueryMs}ms " +
                    "extremes=${afterExtremesMs - afterHourlyMs}ms " +
                    "logBreakdown=${afterBreakdownMs - afterExtremesMs}ms " +
                    "logDiag=${afterDiagMs - afterBreakdownMs}ms " +
                    "persist=${endMs - afterDiagMs}ms " +
                    "contextObs=${contextObs.size} dayObs=${dayObs.size} hourly=${effectiveHourly.size}",
                "INFO",
            )
        }
    }

    private suspend fun logBlendBreakdown(
        date: LocalDate,
        dayObservations: List<com.weatherwidget.data.local.ObservationEntity>,
        newExtremes: List<DailyHistoryEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        val perSourceBreakdown = dayObservations
            .groupBy { it.api }
            .mapValues { (_, sourceObservations) ->
                sourceObservations.groupBy { it.stationId }.entries.joinToString(",") { (stationId, observations) ->
                    val distance = observations.first().distanceKm
                    val high = observations.maxOf { it.temperature }
                    val low = observations.minOf { it.temperature }
                    "$stationId(d=${"%.2f".format(distance)}km,hi=$high,lo=$low,n=${observations.size})"
                }
            }
        newExtremes.forEach { new ->
            appLogDao.log(
                "DAILY_HISTORY_BLEND",
                "date=$date src=${new.source} computed_hi=${new.computedHighTemp} computed_lo=${new.computedLowTemp} " +
                    "stations=[${perSourceBreakdown[blendInputApi(new.source)] ?: "n/a"}] " +
                    "userLat=$latitude userLon=$longitude",
                "VERBOSE",
            )
        }
    }

    /**
     * Reads the current rows **here**, immediately before merging and writing, rather than taking
     * a snapshot from the caller.
     *
     * The caller does a network-free but non-trivial amount of work between its own reads and this
     * write — observation queries, the IDW blend, two diagnostic log passes. A concurrent
     * [persistNwsDailyActuals] landing in that window used to be silently clobbered by the stale
     * snapshot. Observed on the Pixel 2026-08-08: the station pull wrote all six dates with
     * provenance at 12:59:30.115, then a recompute overwrote 2026-08-06 with a snapshot in which
     * `actualsSource` was still null, erasing it. 08-07 survived only on interleaving luck. Because
     * the freeze guard reads that same field, the race also defeats the guard on the very cycle
     * that establishes it — both dates' blends moved despite being pull-derived.
     *
     * This used to only shrink the window to the merge loop. The write is now an optimistic
     * conditional UPDATE ([DailyHistoryDao.updateBlendIfUnchanged]) that sets ONLY the columns the
     * recompute owns and is keyed on the row's `updatedAt` still matching what we read — so a
     * concurrent [persistNwsDailyActuals] can no longer have its provenance clobbered by a stale
     * snapshot, and a conflicting write is detected and skipped. (Residual, accepted risk: `updatedAt`
     * is millisecond precision, so two writes in the same millisecond could theoretically collide.)
     */
    private suspend fun persistExtremes(
        date: LocalDate,
        dateMillis: Long,
        newExtremes: List<DailyHistoryEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        val existingHistory = dailyHistoryDao
            .getExtremesInRange(dateMillis, dateMillis, latitude, longitude)
            .groupBy { it.source }
        val toInsert = mutableListOf<DailyHistoryEntity>()
        newExtremes.forEach { new ->
            // Collapse the box result to the anchor site before writing. `new` was computed from an
            // observation pool that ObservationDao already collapsed via selectNearestSite, so it
            // describes ONE site; getExtremesInRange applies only the coarse ±0.1° ROOM_WHERE box
            // (~7 mi) and happily returns fragments for sites the device visited hours ago. Writing
            // an anchored blend onto all of them mixes sites.
            //
            // Samsung 2026-08-22: a GPS excursion promoted 37.424,-122.088, whose observations for
            // the day began at 12:00. The recompute anchored there wrote its truncated low onto the
            // home row 800 m away — `DAILY_HISTORY_OVERWRITE date=2026-08-22 src=TOMORROW_IO
            // at=37.41682… low=57.03->66.52` at 18:41:58 — destroying a correct value built from 40
            // rows spanning the whole day, and taking yesterday's row with it. Same shape as the
            // cross-site repair bug: filter an uncollapsed pool by `source` but not by site.
            val fragments = existingHistory[new.source].orEmpty()
                .filter { LocationMatch.sameSite(it.locationLat, it.locationLon, latitude, longitude) }
            if (fragments.isEmpty()) {
                toInsert.add(new.copy(lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue))
                return@forEach
            }
            var changedAny = false
            fragments.forEach { existing ->
                // Build the row we would write, then compare it whole. Enumerating the fields to
                // compare is how precip-only deltas were silently dropped before (see
                // recomputeDailyExtremesForDay's precip gate); comparing the merged candidate
                // against `existing` cannot go stale when a column is added.
                // A past day whose blend came from the NWS station pull is frozen. Without this the
                // ordinary recompute — which runs on widget loads and history-screen opens — would
                // immediately overwrite the API-derived blend with one rebuilt from the stored
                // (part-Synoptic, thinner) pool. Today's row is never frozen; its blend must stay
                // live. CACHED_OBSERVATIONS rows are NOT frozen: their blend already comes from the
                // stored pool, so the recompute is its rightful owner and can keep improving it as
                // observations backfill.
                val freezeBlend = DailyActualsSource.fromStored(existing.actualsSource) ==
                    DailyActualsSource.NWS_STATION_PULL && date.isBefore(LocalDate.now())
                // Built from `existing`, enumerating only the fields THIS writer owns.
                //
                // It used to be built from `new` — a freshly constructed entity from
                // ObservationResolver.computeDailyExtremes — with a list of fields to take back
                // from `existing`. That inverts the safe default: every column added later
                // defaults to null in `new` and is silently dropped unless someone remembers to
                // extend the list. `actualsSource` was dropped exactly that way the day it was
                // added, which also disabled the freeze guard that reads it. Building from
                // `existing` means an unknown column is preserved by construction.
                val merged = existing.copy(
                    computedHighTemp = if (freezeBlend) existing.computedHighTemp else new.computedHighTemp,
                    computedLowTemp = if (freezeBlend) existing.computedLowTemp else new.computedLowTemp,
                    // The times move with the values they describe (ForecastOverlaySettle). A frozen
                    // pull row written before v72/v25 has none; it adopts the recompute's, which
                    // describe when the same day's high/low happened — so it can still be settled.
                    computedHighAt = if (freezeBlend) existing.computedHighAt ?: new.computedHighAt else new.computedHighAt,
                    computedLowAt = if (freezeBlend) existing.computedLowAt ?: new.computedLowAt else new.computedLowAt,
                    condition = new.condition,
                    precipAmountMm = new.precipAmountMm,
                    precipDayMm = new.precipDayMm,
                    precipNightMm = new.precipNightMm,
                    lastWriter = DailyHistoryWriter.BLEND_RECOMPUTE.storedValue,
                )
                if (merged.copy(lastWriter = existing.lastWriter) != existing) {
                    changedAny = true
                    appLogDao.log(
                        "DAILY_HISTORY_OVERWRITE",
                        "date=$date src=${new.source} at=${existing.locationLat},${existing.locationLon} " +
                            "high=${existing.computedHighTemp}->${new.computedHighTemp} low=${existing.computedLowTemp}->${new.computedLowTemp} " +
                            "precip=${existing.precipAmountMm}->${new.precipAmountMm} " +
                            "apiHigh=${existing.apiHighTemp}->${merged.apiHighTemp} station=${merged.apiStationId}",
                        "DEBUG",
                    )
                    updateBlendRow(merged, existing, new.updatedAt, date)
                }
            }
            if (!changedAny) {
                appLogDao.log(
                    "DAILY_HISTORY_STABLE",
                    "date=$date src=${new.source} high=${new.computedHighTemp} low=${new.computedLowTemp} fragments=${fragments.size}",
                    "DEBUG",
                )
            }
        }

        if (toInsert.isNotEmpty()) dailyHistoryDao.insertAll(toInsert)
    }

    /**
     * Writes a recomputed blend for ONE existing fragment via an optimistic conditional UPDATE
     * (full PK + the `updatedAt` we read). Only the fields the blend recompute owns are set, so a
     * concurrent writer that already bumped provenance fields (`actualsSource`, `apiHighTemp`, …)
     * can never be clobbered. 0 affected rows means that writer landed between our read and write;
     * the row is left alone and the next recompute will pick up the change.
     */
    private suspend fun updateBlendRow(
        merged: DailyHistoryEntity,
        existing: DailyHistoryEntity,
        newUpdatedAt: Long,
        date: LocalDate,
    ) {
        val updated = dailyHistoryDao.updateBlendIfUnchanged(
            date = merged.date,
            source = merged.source,
            locationLat = merged.locationLat,
            locationLon = merged.locationLon,
            computedHighTemp = merged.computedHighTemp,
            computedLowTemp = merged.computedLowTemp,
            computedHighAt = merged.computedHighAt,
            computedLowAt = merged.computedLowAt,
            condition = merged.condition,
            precipAmountMm = merged.precipAmountMm,
            precipDayMm = merged.precipDayMm,
            precipNightMm = merged.precipNightMm,
            lastWriter = merged.lastWriter,
            updatedAt = newUpdatedAt,
            expectedUpdatedAt = existing.updatedAt,
        )
        if (updated == 0) {
            appLogDao.log(
                "DAILY_HISTORY_RACE",
                "date=$date src=${merged.source} at=${merged.locationLat},${merged.locationLon} " +
                    "expectedUpdatedAt=${existing.updatedAt} — skipped, row changed concurrently",
                "WARN",
            )
        }
    }

    internal suspend fun incompletelyCoveredPastDates(
        dayKeyEpochs: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
    ): Set<Long> =
        dayKeyEpochs.filterTo(mutableSetOf()) { dayKeyEpoch ->
            val date = LocalDate.ofEpochDay(dayKeyEpoch / WidgetConstants.MS_IN_A_DAY)
            if (!date.isBefore(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val timestamps = observationDao
                // Scoped to exactly what the filter below keeps. This runs inside the daily
                // recompute, which is where the worst reads of 2026-09-07 were measured
                // (spanH=72 apis=ALL, 37,551 candidates, 8,325ms under contention).
                .getObservationsInRange(dayStart, dayEnd, latitude, longitude, setOf(WeatherSource.NWS.id))
                .filter { it.api == WeatherSource.NWS.id && it.stationId != "NWS_BLEND" }
                .map { it.timestamp }
            pastDayLacksAfternoonCoverage(timestamps, date, zone, today)
        }

    private suspend fun observationSignature(
        startTs: Long,
        endTs: Long,
        latitude: Double,
        longitude: Double,
    ): String? = observationDao.observationSignatureInRange(startTs, endTs, latitude, longitude)

    /**
     * Quantized to the write grid so GPS jitter cannot mint an unbounded number of keys for what is
     * one place; the same 3 dp the coordinate-keyed tables already store at.
     */
    private fun signatureKey(date: LocalDate, latitude: Double, longitude: Double): String =
        "$date|${"%.3f".format(latitude)}|${"%.3f".format(longitude)}"

    private suspend fun logExtremaWindowDiagnostic(
        date: LocalDate,
        zone: ZoneId,
        startTs: Long,
        endTs: Long,
        dayObs: List<com.weatherwidget.data.local.ObservationEntity>,
        effectiveHourly: List<HourlyForecastEntity>,
        latitude: Double,
        longitude: Double,
    ) {
        // OFF BY DEFAULT — enable with:
        //   adb shell setprop log.tag.EXTREMA_WINDOW_DIAG VERBOSE
        // This is a pure diagnostic, but not a cheap one: it issues its OWN +/-24h observation read
        // and runs blendObservationSeries TWICE (isolated window and wide window). Measured
        // 2026-09-06 on the Samsung it cost logDiag=4977ms for a single day and ~7.9s per sync,
        // about a third of the whole actuals recompute, on a path the user is waiting behind.
        // Guarding the WORK (not just the write) is the point — the AppLogDao VERBOSE gate only
        // skips the insert, which is the cheapest part. Same idiom as
        // TemperatureGraphAnnotationRenderer. The diagnostic itself is deliberately kept.
        if (!Log.isLoggable(EXTREMA_WINDOW_DIAG_TAG, Log.VERBOSE)) return
        try {
            val hourlyReadings = effectiveHourly.map { it.toHourlyForecast() }
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            fun probe(observations: List<ObservationReading>, start: Long, end: Long): String {
                val series = ActualTemperatureSeriesBuilder
                    .blendObservationSeries(
                        observations = observations,
                        hourlyForecasts = hourlyReadings,
                        displaySourceId = WeatherSource.NWS.id,
                        userLat = latitude,
                        userLon = longitude,
                        startMs = start,
                        endMs = end,
                        personalStationWeight = personalStationWeightProvider.currentWeight(),
                    )
                    .observations
                    .filter { it.timestamp in startTs until endTs }
                if (series.isEmpty()) return "empty"
                val high = series.maxBy { it.temperature }
                val low = series.minBy { it.temperature }
                return "hi=${"%.2f".format(high.temperature)}@" +
                    "${Instant.ofEpochMilli(high.timestamp).atZone(zone).format(formatter)} " +
                    "lo=${"%.2f".format(low.temperature)}@" +
                    "${Instant.ofEpochMilli(low.timestamp).atZone(zone).format(formatter)} pts=${series.size}"
            }

            val nwsDayObs = dayObs.filter { it.api == WeatherSource.NWS.id }.map { it.toReading() }
            if (nwsDayObs.isNotEmpty()) {
                val dayMs = 24 * 3_600_000L
                val wideObs = observationDao
                    .getObservationsInRange(
                        startTs - dayMs,
                        endTs + dayMs,
                        latitude,
                        longitude,
                        // Scoped to exactly what the filter below keeps; see above.
                        setOf(WeatherSource.NWS.id),
                    )
                    .filter { it.api == WeatherSource.NWS.id }
                    .map { it.toReading() }
                appLogDao.log(
                    EXTREMA_WINDOW_DIAG_TAG,
                    "date=$date isolated=[${probe(nwsDayObs, startTs, endTs)}] " +
                        "wide=[${probe(wideObs, startTs - dayMs, endTs + dayMs)}]",
                    "DEBUG",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.v(TAG, "Unable to write extrema window diagnostic", e)
        }
    }

}
