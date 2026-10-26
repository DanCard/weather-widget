package com.weatherwidget.shared.actuals

/**
 * Where [DailyBlendRecomputeRun] remembers the observation signature each day was last reduced from.
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
