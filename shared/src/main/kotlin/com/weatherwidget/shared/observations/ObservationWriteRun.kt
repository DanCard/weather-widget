package com.weatherwidget.shared.observations

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.StationType
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.TomorrowIoActuals

/**
 * What a fetch's readings become in `observations`, in one order on both platforms (user 2026-10-25;
 * `plans/261024-shared-observation-persist-rules.md`):
 *
 * 1. Each reading keyed on the quantized site and keeping its own `fetchedAt`; the write time only when
 *    it has none (O5).
 * 2. Inserted, replacing a stored row with the same key.
 * 3. Every written api's earlier rows of these stations re-tagged to the type just fetched, so a
 *    reclassification reaches stored history whichever path fetched it (O2).
 * 4. Personal stations thinned to [PersonalStationThinning.BUCKET_MS] over the stored rows, not just the
 *    batch (O1). A batch-only floor leaks: every incremental fetch keeps its station's newest row, which
 *    then sits beside its bucket's keeper — 7.2 rows/h per station on the Pixel against a 6/h floor
 *    (2026-10-25).
 * 5. A Tomorrow.io fetch's [TomorrowIoActuals.FETCH_TAG] line, whatever source is displayed (O4).
 * 6. Retired products of every written api cleaned up ([com.weatherwidget.shared.actuals.RetiredProductCleanup], O6).
 */
class ObservationWriteRun(private val host: Host) {
    /** One site; every read and delete is on its exact quantized key. */
    interface Host {
        val latitude: Double
        val longitude: Double

        /** Stored rows of [api]'s [stationIds] at this site with `timestamp` in [startMs, endMs). */
        suspend fun storedKeys(api: String, stationIds: Set<String>, startMs: Long, endMs: Long): List<StoredKey>

        /** Inserts [rows] as given, replacing rows with the same key. */
        suspend fun insert(rows: List<ObservationReading>)

        /** Deletes [api]'s [stationId] rows at this site at [timestamps]; returns how many went. */
        suspend fun delete(api: String, stationId: String, timestamps: List<Long>): Int

        /** Sets [stationType] on every stored [api] row of [stationIds], every site; returns rows changed. */
        suspend fun retag(api: String, stationIds: Set<String>, stationType: StationType): Int

        /** [com.weatherwidget.shared.actuals.RetiredProductCleanup] for [api] at this site, logged. */
        suspend fun retireProducts(api: String)

        suspend fun log(tag: String, message: String, level: String)
    }

    data class StoredKey(val stationId: String, val timestamp: Long, val stationType: StationType)

    /**
     * [tomorrowIoWindowHours]: set when this write is a Tomorrow.io fetch — its lookback, for the
     * [TomorrowIoActuals.FETCH_TAG] line. The line is written even when the fetch brought no rows.
     */
    data class Write(
        val readings: List<ObservationReading>,
        val tomorrowIoWindowHours: Int? = null,
    )

    data class Result(val written: Int, val retagged: Int, val thinned: Int, val replacements: Int)

    suspend fun write(write: Write, nowMs: Long): Result {
        val siteLat = LocationMatch.quantize(host.latitude)
        val siteLon = LocationMatch.quantize(host.longitude)
        val rows = write.readings.map { reading ->
            reading.copy(
                locationLat = LocationMatch.quantize(reading.locationLat),
                locationLon = LocationMatch.quantize(reading.locationLon),
                fetchedAt = reading.fetchedAt.takeIf { it > 0L } ?: nowMs,
            )
        }

        val fiveMinute = rows.filter {
            it.api == WeatherSource.TOMORROW_IO.id && it.stationId == TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID
        }
        val replacements = if (write.tomorrowIoWindowHours == null || fiveMinute.isEmpty()) {
            0
        } else {
            val stored = host.storedKeys(
                WeatherSource.TOMORROW_IO.id,
                setOf(TomorrowIoActuals.FIVE_MINUTE_HISTORY_STATION_ID),
                fiveMinute.minOf { it.timestamp },
                fiveMinute.maxOf { it.timestamp } + 1L,
            ).mapTo(HashSet()) { it.timestamp }
            fiveMinute.count { it.locationLat == siteLat && it.locationLon == siteLon && it.timestamp in stored }
        }

        if (rows.isNotEmpty()) host.insert(rows)

        var retagged = 0
        rows.groupBy({ it.api to it.stationType }, { it.stationId }).forEach { (key, ids) ->
            retagged += host.retag(key.first, ids.toSet(), key.second)
        }

        val thinned = thin(rows.filter { it.locationLat == siteLat && it.locationLon == siteLon })

        write.tomorrowIoWindowHours?.let { hours ->
            host.log(
                TomorrowIoActuals.FETCH_TAG,
                TomorrowIoActuals.fetchLine(
                    hours,
                    fiveMinute.size,
                    fiveMinute.minOfOrNull { it.timestamp },
                    fiveMinute.maxOfOrNull { it.timestamp },
                    replacements,
                ),
                "INFO",
            )
        }

        val apis = rows.mapTo(LinkedHashSet()) { it.api }
        if (write.tomorrowIoWindowHours != null) apis += WeatherSource.TOMORROW_IO.id
        apis.forEach { host.retireProducts(it) }

        if (thinned > 0 || retagged > 0) {
            host.log(LOG_TAG, "apis=${apis.joinToString("|")} rows=${rows.size} retagged=$retagged thinned=$thinned", "INFO")
        }
        return Result(written = rows.size, retagged = retagged, thinned = thinned, replacements = replacements)
    }

    /**
     * Thins each written personal station's stored rows from [THIN_LOOKBACK_MS] before the batch onward.
     * The lookback reaches the previous fetch's newest row: kept then as the newest, it shares a bucket
     * with that bucket's keeper once this batch is stored, and is the row a batch-only floor leaks. The
     * read runs to the end of time so the station's newest stored row — which [PersonalStationThinning]
     * always keeps — is in it even when this write is older history.
     */
    private suspend fun thin(rows: List<ObservationReading>): Int {
        var deleted = 0
        rows.filter { it.stationType.isDiscounted }.groupBy { it.api }.forEach { (api, personal) ->
            val stations = personal.mapTo(HashSet()) { it.stationId }
            val from = Math.floorDiv(personal.minOf { it.timestamp } - THIN_LOOKBACK_MS, PersonalStationThinning.BUCKET_MS) *
                PersonalStationThinning.BUCKET_MS
            val stored = host.storedKeys(api, stations, from, Long.MAX_VALUE)
            val kept = PersonalStationThinning.thin(stored, { it.stationId }, { it.stationType }, { it.timestamp }).toSet()
            stored.filter { it !in kept }.groupBy({ it.stationId }, { it.timestamp }).forEach { (station, timestamps) ->
                deleted += host.delete(api, station, timestamps)
            }
        }
        return deleted
    }

    companion object {
        /** Logged only when a write re-tagged or thinned something. */
        const val LOG_TAG = "OBS_WRITE"

        /**
         * How far before a batch thinning re-reads. A day covers every fetch cadence; after a longer
         * gap at most one row per station stays, until retention.
         */
        const val THIN_LOOKBACK_MS = 24 * 60 * 60 * 1000L
    }
}
