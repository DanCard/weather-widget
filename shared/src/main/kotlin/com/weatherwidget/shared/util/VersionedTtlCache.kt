package com.weatherwidget.shared.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * A short-lived cache of reads from stored rows: an entry is served until [ttlMs] after it was stored
 * or until [version] is bumped, whichever comes first.
 *
 * The TTL alone is not enough once it is long enough to see: a screen's fetch followed by a UI-only
 * repaint drew the rows from before the fetch ([StoredDataVersion]). An entry carries the version
 * read *before* its load began, so a write that lands mid-load leaves it stale at once.
 *
 * Expired entries are dropped lazily on [get]; the per-key mutexes only grow with distinct keys.
 */
class VersionedTtlCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val version: StoredDataVersion = StoredDataVersion.process,
) {
    private class Entry<V>(val value: V, val storedAtMs: Long, val version: Long)

    private val entries = ConcurrentHashMap<K, Entry<V>>()
    private val loadMutexes = ConcurrentHashMap<K, Mutex>()

    /** The entry for [key] if it is within [ttlMs] of [nowMs] and no write has landed since its load. */
    fun get(key: K, nowMs: Long): V? {
        val e = entries[key] ?: return null
        if (nowMs - e.storedAtMs > ttlMs || e.version != version.current()) {
            entries.remove(key, e)
            return null
        }
        return e.value
    }

    /** [loadedAtVersion]: the version read before [value] was loaded; defaults to now. */
    fun put(key: K, value: V, nowMs: Long, loadedAtVersion: Long = version.current()) {
        entries[key] = Entry(value, nowMs, loadedAtVersion)
    }

    /**
     * A fresh entry, or exactly one load for [key]: concurrent callers for one key await the same
     * load. The TTL clock is read after [loader] returns, so a slow load does not use up the lifetime.
     */
    suspend fun getOrLoad(key: K, nowMs: () -> Long, loader: suspend () -> V): V {
        get(key, nowMs())?.let { return it }
        val mutex = loadMutexes.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            get(key, nowMs())?.let { return@withLock it }
            val loadedAt = version.current()
            loader().also { put(key, it, nowMs(), loadedAt) }
        }
    }

    fun clear() {
        entries.clear()
        loadMutexes.clear()
    }
}
