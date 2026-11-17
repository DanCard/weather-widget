package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.AviationWeatherApi
import com.weatherwidget.data.remote.AviationWeatherStationFilter
import com.weatherwidget.data.remote.FetchOutcome
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.actuals.dayHasOfficialGap
import com.weatherwidget.shared.actuals.pastDayLacksAfternoonCoverage
import com.weatherwidget.shared.util.ActualsFeedPolicy
import com.weatherwidget.shared.util.Log
import com.weatherwidget.shared.util.StoredDataVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "NwsGapFillRun"

/**
 * The NWS observation gap-fill, one rule for both platforms: which recent days need station history,
 * how far back to ask, which stations in what order, when to stop, and what to log. Android's
 * `NwsObservationBackfiller` and desktop's `refreshObservationWindow` each supply a [Host] for the
 * station list, the per-station fetch, storage and the extremes recompute
 * (plans/261025-shared-nws-gap-fill.md). Desktop pulled 7 days on every wake whether or not a day
 * was missing; it now follows Android's need check and 3-day window.
 */
object NwsGapFillRun {
    /** How far back a fill asks each station: the hourly graph's historical lookback. */
    const val BACKFILL_DAYS = 3

    const val MAX_STATIONS = NwsStationBundles.MAX_STATIONS

    /** Today joins the need only once its early hours have had time to report. */
    private const val TODAY_FROM_HOUR = 2

    private const val MS_IN_A_DAY = 86_400_000L

    interface Host {
        /** The site's NWS observation stations, nearest first; [FetchOutcome.Failed] = never answered. */
        suspend fun stations(latitude: Double, longitude: Double): FetchOutcome<List<NwsApi.StationInfo>>

        /** One station's [startIso, endIso) window through [NwsStationHistoryFetcher], with the platform's web leg. */
        suspend fun fetchStation(
            station: NwsApi.StationInfo,
            index: Int,
            latitude: Double,
            longitude: Double,
            startIso: String,
            endIso: String,
            fallbackLogTag: String,
        ): StationRows

        suspend fun store(rows: List<ObservationReading>, latitude: Double, longitude: Double)

        /** Aviation Weather's METAR reports for [stationIds] over the last [hours], in one request ([reconcileMetars]). */
        suspend fun fetchMetars(stationIds: List<String>, hours: Int): FetchOutcome<List<AviationWeatherApi.MetarRow>>

        /** daily_history rows in the box for day keys in [startDayMs, endDayMs] (UTC-midnight keys). */
        suspend fun dailyHistoryRows(startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double): List<DayRow>

        /** Stored observations in the box in [startMs, endMs); a host may pre-filter to NWS rows. */
        suspend fun observations(startMs: Long, endMs: Long, latitude: Double, longitude: Double): List<ObsStamp>

        /** Recompute [days]' extremes from what is now stored, bypassing any unchanged-input skip. */
        suspend fun recomputeExtremes(latitude: Double, longitude: Double, days: Set<LocalDate>)

        suspend fun log(tag: String, message: String, level: String)

        /** Which boot last reconciled each site ([reconcileOncePerBoot]). */
        val bootStore: MetarReconcileBootStore
    }

    data class StationRows(
        val rows: List<ObservationReading>,
        val usedWebFallback: Boolean,
        /** Simple name of the exception the API call threw, or null when it answered. */
        val apiFailure: String? = null,
    )

    data class DayRow(val dateMs: Long, val source: String)

    data class ObsStamp(
        val timestamp: Long,
        val api: String,
        val stationId: String,
        val stationType: StationType = StationType.UNKNOWN,
    )

    data class Result(
        /** Days the need check asked for; empty = nothing to do, and nothing was fetched. */
        val needed: Set<LocalDate>,
        /** Needed days still missing or incomplete after the fill. */
        val remaining: Set<LocalDate>,
        val stationsTried: Int,
        val rows: Int,
        /**
         * NWS never answered: the station lookup failed, or every tried station's fetch failed.
         * Distinct from an answered-but-empty fill, which is a real data limit.
         */
        val unreachable: Boolean,
    )

    /** The one summary line of a [dailyForSources] run, on both platforms. */
    const val RESULT_LOG_TAG = "NWS_DAILY_BACKFILL_RESULT"

    /**
     * Every trigger's entry to the daily pass — Android's full sync and Refresh tap, desktop's
     * launch/wake catch-up and Refresh: runs [daily] only when one of [sourcesServed] takes its actuals
     * from NWS ([ActualsFeedPolicy.requiresFeed]), then logs one [RESULT_LOG_TAG] line. Visible
     * sources, not only the displayed one: the station rows feed every source that borrows NWS
     * actuals, and desktop gating on the displayed source alone left a borrower unfilled while Android
     * filled it. Null when no served source reads NWS — nothing was asked.
     */
    suspend fun dailyForSources(
        host: Host,
        latitude: Double,
        longitude: Double,
        sourcesServed: Collection<WeatherSource>,
        reason: String,
        nowMs: Long,
        zone: ZoneId,
        storedDataVersion: StoredDataVersion = StoredDataVersion.process,
    ): Result? {
        if (!ActualsFeedPolicy.requiresFeed(WeatherSource.NWS, sourcesServed, latitude, longitude)) return null
        // First, so the need check below judges the rows with every station's own reports in place.
        reconcileOncePerBoot(host, latitude, longitude, nowMs, zone, storedDataVersion)
        val result = daily(host, latitude, longitude, nowMs, zone, storedDataVersion)
        host.log(
            RESULT_LOG_TAG,
            "reason=$reason need=${result.needed.sorted().ifEmpty { "none" }} rows=${result.rows} " +
                "stations=${result.stationsTried} unreachable=${result.unreachable} remaining=${result.remaining.sorted()}",
            if (result.unreachable) "WARN" else "INFO",
        )
        return result
    }

    /** The one summary line of a [reconcileMetars] pass, on both platforms. */
    const val RECONCILE_LOG_TAG = "NWS_METAR_RECONCILE"

    /**
     * How long after a boot the reconcile keeps running on every pass before that boot counts as done.
     * The NWS API adds reports 60-80 min late, so a boot inside a hole would otherwise repair only part
     * of it: the emulator booted at about 08:10 on 2026-11-17, inside KNUQ's 05:55-08:55 hole.
     */
    const val RECONCILE_SETTLE_MS = 2 * 60 * 60 * 1000L

    /**
     * Runs [reconcileMetars] once per device boot and site: whenever the site's stored boot differs from
     * the current one. The boot is marked done only after an answered reconcile at least
     * [RECONCILE_SETTLE_MS] after boot, so a pass with no network, or one too soon for the NWS lag,
     * leaves it pending for the next pass. Without a boot id it always runs. Returns the rows added,
     * or null when it was skipped or never answered. plans/261117-metar-reconcile-once-per-device-boot.md
     */
    suspend fun reconcileOncePerBoot(
        host: Host,
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        zone: ZoneId,
        storedDataVersion: StoredDataVersion = StoredDataVersion.process,
    ): Int? = reconcileLock.withLock {
        val store = host.bootStore
        val siteKey = MetarReconcileBootStore.siteKey(latitude, longitude)
        val boot = runCatching { store.currentBoot(nowMs) }.getOrNull()
        if (boot != null && runCatching { store.reconciledBootId(siteKey) }.getOrNull() == boot.id) {
            Log.v(TAG, "$RECONCILE_LOG_TAG skipped=boot_done boot=${boot.id} site=$siteKey")
            return@withLock null
        }
        val added = reconcileMetars(host, latitude, longitude, nowMs, zone, storedDataVersion) ?: return@withLock null
        if (boot != null && nowMs - boot.bootedAtMs >= RECONCILE_SETTLE_MS) {
            runCatching { store.markReconciled(siteKey, boot.id) }
                .onFailure { Log.w(TAG, "Could not mark boot ${boot.id} reconciled for $siteKey: $it") }
            host.log(RECONCILE_LOG_TAG, "boot_done boot=${boot.id} site=$siteKey", "INFO")
        }
        added
    }

    /**
     * One reconcile at a time in this process, so a pass that arrives during another waits and then sees
     * its marker. Without it the emulator's widget Refresh and full sync both reconciled boot 126 within
     * 5 s on 2026-11-17: the second read the rows after the first stored them, while the first was still
     * recomputing, and logged `added=0`.
     */
    private val reconcileLock = Mutex()

    /**
     * Adds to the NWS station rows every report the stations' own METARs hold and the rows lack: one
     * Aviation Weather request for the nearest [MAX_STATIONS] METAR stations' last [BACKFILL_DAYS], each
     * report stamped as its NWS station's web row ([NwsStationLatestFetcher.asNwsWebRow]) and kept only
     * where no NWS row has that station and time (an API row keeps its own). Returns the rows added, or
     * null when NWS's station list or Aviation Weather never answered. Run through [reconcileOncePerBoot].
     *
     * No hole detection: a report that does not exist adds nothing, so a station silent
     * overnight costs nothing. The need check merges official stations, so a hole in the nearest one
     * stayed while a farther one reported: the emulator's KNUQ (3.8 km) had no NWS rows 06:15-08:35 on
     * 2026-11-17, KSJC (15.9 km) stood in with its fog, and the NWS Today column drew dense fog over a
     * clear `10SM CLR` morning. plans/261117-nws-station-rows-reconciled-with-own-metars.md
     */
    suspend fun reconcileMetars(
        host: Host,
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        zone: ZoneId,
        storedDataVersion: StoredDataVersion = StoredDataVersion.process,
    ): Int? {
        val lookup = host.stations(latitude, longitude)
        if (lookup is FetchOutcome.Failed) {
            host.log(RECONCILE_LOG_TAG, "stations=? unreachable=true reason=${lookup.reason}", "WARN")
            return null
        }
        val stations = lookup.valueOrNull().orEmpty()
            .take(MAX_STATIONS)
            .filter { NwsMetarTextWeb.isMetarStation(it) }
        if (stations.isEmpty()) return 0
        val ids = stations.map { it.id }
        val startMs = nowMs - BACKFILL_DAYS * MS_IN_A_DAY
        val outcome = try {
            host.fetchMetars(ids, NwsMetarHistoryWeb.hoursFor(startMs, nowMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchOutcome.failed(e)
        }
        if (outcome is FetchOutcome.Failed) {
            host.log(RECONCILE_LOG_TAG, "stations=${ids.joinToString(",")} unreachable=true reason=${outcome.reason}", "WARN")
            return null
        }
        val byId = stations.associateBy { it.id.uppercase() }
        val fetched = outcome.valueOrNull().orEmpty()
            .filter { it.observedAtMillis in startMs..nowMs }
            .mapNotNull { row ->
                val station = byId[row.stationId.uppercase()] ?: return@mapNotNull null
                // Distance is re-measured from NWS's station coordinates by asNwsWebRow.
                val ranked = AviationWeatherStationFilter.RankedStation(station, distanceKm = 0.0, elevationMeters = null)
                MetarObservationMapper.toReading(row, ranked, latitude, longitude)
                    ?.let { NwsStationLatestFetcher.asNwsWebRow(it, station, latitude, longitude) }
            }
        val stored = host.observations(startMs, nowMs + 1, latitude, longitude)
            .filter { it.api == WeatherSource.NWS.id }
            .mapTo(HashSet()) { it.stationId.uppercase() to it.timestamp }
        val added = fetched
            .filter { (it.stationId.uppercase() to it.timestamp) !in stored }
            .distinctBy { it.stationId.uppercase() to it.timestamp }
        val days = added.mapTo(sortedSetOf()) { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        if (added.isNotEmpty()) {
            host.store(added, latitude, longitude)
            storedDataVersion.bump("nws_metar_reconcile")
            host.recomputeExtremes(latitude, longitude, days)
            storedDataVersion.bump("nws_metar_reconcile_extremes")
        }
        host.log(
            RECONCILE_LOG_TAG,
            "stations=${ids.joinToString(",")} fetched=${fetched.size} added=${added.size} " +
                "byStation=${added.groupingBy { it.stationId }.eachCount()} days=$days",
            "INFO",
        )
        return added.size
    }

    /**
     * The daily pass: fills the last [BACKFILL_DAYS] when day−2, yesterday or (from 02:00) today has no
     * NWS daily_history row, a past one was reduced from an overnight-only slice, or a day's official
     * stations went quiet for over 75 min (today up to now — a wake after a night asleep). Stations are
     * tried nearest first and the pass stops as soon as every needed day is complete.
     */
    suspend fun daily(
        host: Host,
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        zone: ZoneId,
        /** Bumped after each store and extremes recompute, so cached reads of them go stale. */
        storedDataVersion: StoredDataVersion = StoredDataVersion.process,
    ): Result {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate()
        val rangeStart = today.minusDays(2).dayKey()
        val rangeEnd = today.dayKey()
        val required = buildSet {
            add(rangeStart)
            add(today.minusDays(1).dayKey())
            if (now.hour >= TODAY_FROM_HOUR) add(rangeEnd)
        }
        val existing = nwsDayKeys(host, rangeStart, rangeEnd, latitude, longitude)
        val incomplete = incompleteDays(host, existing, latitude, longitude, zone, today, nowMs)
        val missing = required - existing
        Log.d(
            TAG,
            "History check required=${required.toDates()} existing=${existing.toDates()} " +
                "missing=${missing.toDates()} incomplete=${incomplete.toDates()} hour=${now.hour}",
        )
        val needed = missing + incomplete
        if (needed.isEmpty()) return Result(emptySet(), emptySet(), 0, 0, unreachable = false)

        val stationsOutcome = host.stations(latitude, longitude)
        val stations = stationsOutcome.valueOrNull().orEmpty().take(MAX_STATIONS)
        if (stations.isEmpty()) {
            val unreachable = stationsOutcome is FetchOutcome.Failed
            host.log(
                "NWS_DAILY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=${if (unreachable) "stations_unreachable" else "no_stations"}",
                "WARN",
            )
            return Result(needed.toDates().toSet(), needed.toDates().toSet(), 0, 0, unreachable)
        }

        val startIso = DateTimeFormatter.ISO_INSTANT.format(now.minusDays(BACKFILL_DAYS.toLong()).toInstant())
        val endIso = DateTimeFormatter.ISO_INSTANT.format(now.toInstant())
        val remaining = needed.toMutableSet()
        var tried = 0
        var failed = 0
        var rows = 0
        for ((index, station) in stations.withIndex()) {
            tried++
            val fetch = fetchAndStore(
                host, station, index, latitude, longitude, startIso, endIso, storedDataVersion,
                fallbackLogTag = "NWS_DAILY_WEB_FALLBACK",
                stationLogTag = "NWS_DAILY_BACKFILL_STATION",
            )
            if (fetch.failed) failed++
            if (fetch.rows.isEmpty()) continue
            rows += fetch.rows.size

            val days = fetch.rows.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.toSet()
            host.recomputeExtremes(latitude, longitude, days)
            storedDataVersion.bump("nws_gap_fill_extremes")
            val rowDates = nwsDayKeys(host, rangeStart, rangeEnd, latitude, longitude)
            val stillIncomplete = incompleteDays(host, rowDates, latitude, longitude, zone, today, nowMs)
            remaining.removeAll(rowDates - stillIncomplete)
            if (remaining.isEmpty()) break
        }

        if (remaining.isNotEmpty()) {
            host.log(
                "NWS_DAILY_BACKFILL_INCOMPLETE",
                "lat=$latitude lon=$longitude remaining=${remaining.toDates()}",
                "WARN",
            )
            Log.w(TAG, "Daily backfill still missing/incomplete for ${remaining.toDates()}")
        }
        return Result(
            needed = needed.toDates().toSet(),
            remaining = remaining.toDates().toSet(),
            stationsTried = tried,
            rows = rows,
            unreachable = failed == tried,
        )
    }

    data class RecentResult(
        val stationsTried: Int,
        val rowsFetched: Int,
        val affectedDates: Set<LocalDate>,
        /**
         * NWS never answered: the station lookup failed, or every station's fetch failed. Distinct from
         * an answered-but-empty backfill, which is a real data limit and must not be retried quickly
         * ([ObservationBackfillRetryPolicy]).
         */
        val unreachable: Boolean = false,
    )

    /**
     * The recent-window pass: every station's last [lookbackHours] (no early stop — the gap being
     * repaired is in the graph's hours, not a daily row), then the affected days' extremes recomputed.
     * Android runs it for a render-detected observation gap.
     */
    suspend fun recent(
        host: Host,
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        nowMs: Long,
        zone: ZoneId,
        storedDataVersion: StoredDataVersion = StoredDataVersion.process,
    ): RecentResult {
        val now = Instant.ofEpochMilli(nowMs)
        val startIso = DateTimeFormatter.ISO_INSTANT.format(now.minusSeconds(lookbackHours * 3600L))
        val endIso = DateTimeFormatter.ISO_INSTANT.format(now)
        host.log(
            "OBS_HOURLY_BACKFILL_START",
            "lat=$latitude lon=$longitude lookbackHours=$lookbackHours start=$startIso end=$endIso",
            "INFO",
        )

        val stationsOutcome = host.stations(latitude, longitude)
        val stations = stationsOutcome.valueOrNull().orEmpty().take(MAX_STATIONS)
        if (stations.isEmpty()) {
            val unreachable = stationsOutcome is FetchOutcome.Failed
            host.log(
                "OBS_HOURLY_BACKFILL_FAIL",
                "lat=$latitude lon=$longitude reason=${if (unreachable) "stations_unreachable" else "no_stations"}",
                "WARN",
            )
            return RecentResult(0, 0, emptySet(), unreachable = unreachable)
        }

        var rows = 0
        var failed = 0
        val affected = mutableSetOf<LocalDate>()
        for ((index, station) in stations.withIndex()) {
            val fetch = fetchAndStore(
                host, station, index, latitude, longitude, startIso, endIso, storedDataVersion,
                fallbackLogTag = "OBS_HOURLY_WEB_FALLBACK",
                stationLogTag = "OBS_HOURLY_BACKFILL_STATION",
            )
            if (fetch.failed) failed++
            rows += fetch.rows.size
            affected += fetch.rows.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        }

        if (affected.isNotEmpty()) {
            // The whole span, gaps included, as Android's range recompute did.
            val days = generateSequence(affected.min()) { it.plusDays(1) }.takeWhile { !it.isAfter(affected.max()) }.toSet()
            host.recomputeExtremes(latitude, longitude, days)
            storedDataVersion.bump("nws_gap_fill_extremes")
        }
        host.log(
            "OBS_HOURLY_BACKFILL_DONE",
            "lat=$latitude lon=$longitude stations=${stations.size} failedStations=$failed rows=$rows " +
                "affectedDates=${affected.sorted()}",
            "INFO",
        )
        return RecentResult(stations.size, rows, affected, unreachable = failed == stations.size)
    }

    private class StationFetch(val rows: List<ObservationReading>, val failed: Boolean)

    private suspend fun fetchAndStore(
        host: Host,
        station: NwsApi.StationInfo,
        index: Int,
        latitude: Double,
        longitude: Double,
        startIso: String,
        endIso: String,
        storedDataVersion: StoredDataVersion,
        fallbackLogTag: String,
        stationLogTag: String,
    ): StationFetch =
        try {
            val result = host.fetchStation(station, index, latitude, longitude, startIso, endIso, fallbackLogTag)
            // A station past the web-fallback cap gets no web rows, so a timeout here lands as a bare
            // `rows=0` that reads exactly like a station with nothing to report (KSJC, 2026-08-21).
            // The failure marker is the only thing separating the two on this line.
            host.log(
                stationLogTag,
                "station=${station.id} rows=${result.rows.size} web=${result.usedWebFallback}" +
                    (result.apiFailure?.let { " apiFetchFailed=$it" } ?: ""),
                "INFO",
            )
            if (result.rows.isNotEmpty()) {
                host.store(result.rows, latitude, longitude)
                storedDataVersion.bump("nws_gap_fill station=${station.id}")
            }
            StationFetch(result.rows, failed = result.rows.isEmpty() && result.apiFailure != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("${stationLogTag}_FAIL", "station=${station.id} error=${e::class.simpleName}:${e.message}", "WARN")
            Log.e(TAG, "Observation backfill failed for ${station.id}", e)
            StationFetch(emptyList(), failed = true)
        }

    private suspend fun nwsDayKeys(host: Host, startDayMs: Long, endDayMs: Long, latitude: Double, longitude: Double): Set<Long> =
        host.dailyHistoryRows(startDayMs, endDayMs, latitude, longitude)
            .filter { it.source == WeatherSource.NWS.id }
            .mapTo(mutableSetOf()) { it.dateMs }

    /**
     * [dayKeys] whose own NWS station rows (the blend's synthetic row excluded) leave the day short: a
     * past day that stops before the afternoon, or any day, today up to [nowMs], whose official
     * stations leave a hole ([dayHasOfficialGap]). Personal stations reach the store through the
     * Synoptic fallback's 12 h window even after a night asleep, so they cannot close that hole.
     */
    private suspend fun incompleteDays(
        host: Host,
        dayKeys: Set<Long>,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
        today: LocalDate,
        nowMs: Long,
    ): Set<Long> =
        dayKeys.filterTo(mutableSetOf()) { key ->
            val date = LocalDate.ofEpochDay(key / MS_IN_A_DAY)
            if (date.isAfter(today)) return@filterTo false
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stamps = host.observations(dayStart, dayEnd, latitude, longitude)
                .filter { it.api == WeatherSource.NWS.id && it.stationId != NwsBlend.STATION_ID }
            val official = stamps.filter { !it.stationType.isDiscounted }.map { it.timestamp }
            pastDayLacksAfternoonCoverage(stamps.map { it.timestamp }, date, zone, today) ||
                dayHasOfficialGap(official, date, zone, nowMs)
        }

    private fun LocalDate.dayKey(): Long = toEpochDay() * MS_IN_A_DAY

    private fun Set<Long>.toDates(): List<LocalDate> = map { LocalDate.ofEpochDay(it / MS_IN_A_DAY) }.sorted()
}
