package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import java.time.ZoneId

/**
 * Memo for [ActualTemperatureSeriesBuilder.blendObservationSeries].
 *
 * The blend is a pure function of its arguments (verified: no clock reads in its body), and every
 * caller on the current-status path hands it arguments that change far more slowly than the path
 * runs:
 *
 *  - [ActualsAggregator.resolveCurrentObservationInternal] snaps its window centre to a 30-minute
 *    boundary ([ActualsAggregator.alignedCenterMs]), so a minute tick recomputes an identical
 *    series ~30 times per distinct window.
 *  - [YesterdayDeltaCalculator.computeDelta] windows on an *observation* timestamp, which only
 *    moves when a new observation lands (~30 min at best).
 *
 * Meanwhile the observation/hourly lists themselves are rebuilt only by a fetch. On the desktop
 * daemon this was the single largest idle CPU cost: two full blends over ~4k observations every
 * 60 s, producing bit-identical output all but twice an hour.
 *
 * This cache is deliberately *transparent*: it returns the same value the builder would have, so
 * it can be dropped in without changing behaviour. What it is not is a general-purpose cache — it
 * holds a handful of entries for one location's current view. See
 * `plans/260817-desktop-idle-cpu-blend-memoization.md`.
 *
 * ### Keying
 *
 * The list arguments are compared by **reference identity** (`===`), not by content and not by
 * `identityHashCode`. Content comparison would cost as much as the blend it is avoiding, and an
 * identity *hash* can collide once the GC reuses an address. Holding the references pins no memory
 * that is not already live — the caller's snapshot holds the same lists.
 *
 * Thread-safe: every access is under the instance lock.
 */
class BlendSeriesCache(private val capacity: Int = DEFAULT_CAPACITY) {

    /**
     * Scalar half of the key. The list arguments are held separately in [Entry] because they must
     * be compared by identity, which `data class` equality would not do.
     */
    private data class ScalarKey(
        val displaySourceId: String,
        val userLat: Double,
        val userLon: Double,
        val startMs: Long,
        val endMs: Long,
        val personalStationWeight: Double,
        val zoneId: ZoneId,
    )

    private class Entry(
        val observations: List<ObservationReading>,
        val hourlyForecasts: List<HourlyForecast>,
        val scalars: ScalarKey,
        val value: BlendObservationResult,
    )

    private val lock = Any()

    /** Most-recently-used first. Bounded by [capacity]; the tail is dropped on insert. */
    private val entries = ArrayDeque<Entry>()

    /** Hit/miss counters, for tests and for diagnosing a key that never matches. */
    @Volatile var hits: Long = 0L
        private set

    @Volatile var misses: Long = 0L
        private set

    /**
     * Returns the memoized blend for these arguments, computing it via [compute] on a miss.
     *
     * [compute] must be the real builder call with exactly these arguments — the cache cannot
     * verify that, and a mismatch would serve one window's series for another's key.
     */
    fun getOrCompute(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        startMs: Long,
        endMs: Long,
        personalStationWeight: Double,
        zoneId: ZoneId,
        compute: () -> BlendObservationResult,
    ): BlendObservationResult {
        val scalars = ScalarKey(
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            startMs = startMs,
            endMs = endMs,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
        )

        synchronized(lock) {
            val index = entries.indexOfFirst {
                it.observations === observations &&
                    it.hourlyForecasts === hourlyForecasts &&
                    it.scalars == scalars
            }
            if (index >= 0) {
                // Promote to MRU so the two live windows (current + yesterday) never evict each other.
                val hit = entries.removeAt(index)
                entries.addFirst(hit)
                hits++
                return hit.value
            }
        }

        // Computed outside the lock: the blend is the expensive part, and holding the lock across it
        // would serialize the very calls this cache exists to make cheap. A concurrent duplicate
        // compute is possible and harmless — the results are equal, and the second insert dedupes.
        misses++
        val value = compute()

        synchronized(lock) {
            val alreadyPresent = entries.any {
                it.observations === observations &&
                    it.hourlyForecasts === hourlyForecasts &&
                    it.scalars == scalars
            }
            if (!alreadyPresent) {
                entries.addFirst(Entry(observations, hourlyForecasts, scalars, value))
                while (entries.size > capacity) entries.removeLast()
            }
        }
        return value
    }

    /** Drops every entry. Exists for tests and for a location/source change that invalidates all keys. */
    fun clear() {
        synchronized(lock) { entries.clear() }
        hits = 0
        misses = 0
    }

    /** Current entry count. Test seam. */
    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * One-line hit/miss summary for the periodic diagnostic log.
     *
     * Deliberately permanent: a cache that silently never hits is indistinguishable from the
     * un-memoized code it replaced — same results, same CPU — so without this the optimization is
     * unfalsifiable in production. A collapsing hit rate is the first symptom of a key that stopped
     * matching (e.g. a caller that rebuilds its observation list per tick).
     */
    fun stats(): String {
        val total = hits + misses
        val rate = if (total == 0L) 0.0 else hits.toDouble() * 100.0 / total
        return "hits=$hits misses=$misses hitRate=${"%.1f".format(rate)}% entries=$size"
    }

    companion object {
        /**
         * Two live windows (the current-temp blend and the 24h-ago delta blend) plus slack for a
         * source toggle mid-tick. Small on purpose: this is a memo, not a cache tier.
         */
        const val DEFAULT_CAPACITY = 4
    }
}
