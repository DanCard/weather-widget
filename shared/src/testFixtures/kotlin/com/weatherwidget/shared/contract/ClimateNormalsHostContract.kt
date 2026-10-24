package com.weatherwidget.shared.contract

import com.weatherwidget.shared.util.ClimateNormalsRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What [ClimateNormalsRun.Host]'s cache means, on Android's Room Host and desktop's SQLite Host
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). The archive fetch is network, not
 * the database, so it is not part of this contract.
 */
abstract class ClimateNormalsHostContract {
    /** A fresh, empty database each call. */
    protected abstract fun open(): ClimateNormalsRun.Host

    private lateinit var host: ClimateNormalsRun.Host

    @Before
    fun openDatabase() {
        host = open()
    }

    @Test
    fun `nothing cached reads as two empty maps`() = runBlocking {
        assertEquals(emptyMap<Int, Float>() to emptyMap<Int, Float>(), host.cached(KEY_A))
    }

    @Test
    fun `stored months read back, a month missing either side is not stored`() = runBlocking {
        host.store(KEY_A, mapOf(1 to 55.5f, 7 to 80f, 8 to 81f), mapOf(1 to 40.25f, 7 to 60f, 9 to 59f))

        assertEquals(mapOf(1 to 55.5f, 7 to 80f) to mapOf(1 to 40.25f, 7 to 60f), host.cached(KEY_A))
    }

    @Test
    fun `storing a site drops every other site's normals`() = runBlocking {
        host.store(KEY_A, mapOf(1 to 55f), mapOf(1 to 40f))
        host.store(KEY_B, mapOf(2 to 50f), mapOf(2 to 35f))

        assertEquals(emptyMap<Int, Float>() to emptyMap<Int, Float>(), host.cached(KEY_A))
        assertEquals(mapOf(2 to 50f) to mapOf(2 to 35f), host.cached(KEY_B))
    }

    @Test
    fun `storing the same site again replaces its months`() = runBlocking {
        host.store(KEY_A, mapOf(1 to 55f), mapOf(1 to 40f))
        host.store(KEY_A, mapOf(1 to 56f), mapOf(1 to 41f))

        assertEquals(mapOf(1 to 56f) to mapOf(1 to 41f), host.cached(KEY_A))
    }

    companion object {
        const val KEY_A = "37.42,-122.09"
        const val KEY_B = "50.45,30.52"

        /** The contract never fetches. */
        val NO_FETCH: suspend (String, String) -> Nothing = { _, _ -> error("ClimateNormalsHostContract does not fetch") }
    }
}
