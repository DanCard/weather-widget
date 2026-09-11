package com.weatherwidget.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where [DailyActualsStore] remembers the observation signature each day was last reduced from.
 *
 * The signature test ("this day's observations are byte-identical to when its extremes were last
 * computed, so skip") was first cached only in memory: "losing it on process death costs exactly
 * one redundant recompute". Measured 2026-09-10 on the Pixel 7 Pro, that one recompute was 12 s of
 * cold, interpreted CPU on two settled days, in the same second as the user's first tap after an
 * install (`performance/260910-post-install-cold-start-storm.md`). Persisting a few hundred short
 * strings removes it.
 */
interface ReducedSignatureStore {
    operator fun get(key: String): String?

    operator fun set(key: String, signature: String)

    /** Bound the store; returns after every entry is gone. */
    fun clear()

    val size: Int
}

/** The original within-process cache; what tests and non-Hilt construction use. */
class InMemoryReducedSignatureStore : ReducedSignatureStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun set(key: String, signature: String) {
        map[key] = signature
    }

    override fun clear() = map.clear()

    override val size: Int get() = map.size
}

/**
 * Signatures that survive the process. Reads are served from memory after the first touch; each
 * write goes through to a dedicated preference file (`apply`, off the caller's thread). Keys are
 * `date|lat|lon` and values are short digest strings, so the file stays a few KB.
 */
@Singleton
class PersistedReducedSignatureStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ReducedSignatureStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var loaded = false

    private fun loadOnce() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for ((key, value) in prefs.all) {
                if (value is String) memory[key] = value
            }
            loaded = true
        }
    }

    override fun get(key: String): String? {
        loadOnce()
        return memory[key]
    }

    override fun set(key: String, signature: String) {
        loadOnce()
        memory[key] = signature
        prefs.edit().putString(key, signature).apply()
    }

    override fun clear() {
        loadOnce()
        memory.clear()
        prefs.edit().clear().apply()
    }

    override val size: Int
        get() {
            loadOnce()
            return memory.size
        }

    companion object {
        const val PREFS_NAME = "daily_recompute_signatures"
    }
}
