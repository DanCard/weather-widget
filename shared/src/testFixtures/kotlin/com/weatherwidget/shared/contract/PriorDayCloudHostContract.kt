package com.weatherwidget.shared.contract

import com.weatherwidget.shared.graph.PriorDayCloudRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [PriorDayCloudRun.Host.store] and what the cloud graph then reads back (each platform's
 * `getPriorDayCloudForecast`), on Room and desktop SQLite
 * (plans/261024-host-contract-tests-room-and-desktop-sqlite.md). The fetch is network, not part of it.
 */
abstract class PriorDayCloudHostContract {
    interface Db {
        fun host(latitude: Double, longitude: Double, lastAttempt: PriorDayCloudRun.LastAttempt = PriorDayCloudRun.LastAttempt()): PriorDayCloudRun.Host

        /** The platform's prior-day cloud read: hour → percent over [startMs, endMs). */
        suspend fun read(latitude: Double, longitude: Double, startMs: Long, endMs: Long): Map<Long, Int>
    }

    /** A fresh, empty database each call. */
    protected abstract fun open(): Db

    private lateinit var db: Db

    @Before
    fun openDatabase() {
        db = open()
    }

    @Test
    fun `stored hours read back over a start-inclusive, end-exclusive window`() = runBlocking {
        db.host(LAT, LON).store(mapOf(H0 to 40, H1 to 60), NOW)

        assertEquals(mapOf(H0 to 40, H1 to 60), db.read(LAT, LON, H0, H1 + HOUR))
        assertEquals(mapOf(H0 to 40), db.read(LAT, LON, H0, H1))
    }

    /** Desktop merged every site in the ±0.1° box (user, 2026-10-24: Android's rule, this site only). */
    @Test
    fun `a neighbouring town's rows in the read box are not this site's`() = runBlocking {
        db.host(NEIGHBOUR_LAT, LON).store(mapOf(H0 to 90, H1 to 90), NOW)
        db.host(LAT, LON).store(mapOf(H1 to 40), NOW)

        assertEquals(mapOf(H1 to 40), db.read(LAT, LON, H0, H1 + HOUR))
    }

    @Test
    fun `a jitter fragment of this site counts, the site's own row wins the hour`() = runBlocking {
        db.host(FRAGMENT_LAT, LON).store(mapOf(H0 to 55, H1 to 56), NOW)
        db.host(LAT, LON).store(mapOf(H1 to 30), NOW)

        assertEquals(mapOf(H0 to 55, H1 to 30), db.read(LAT, LON, H0, H1 + HOUR))
    }

    @Test
    fun `storing an hour again replaces it`() = runBlocking {
        db.host(LAT, LON).store(mapOf(H0 to 40), NOW)
        db.host(LAT, LON).store(mapOf(H0 to 70), NOW + HOUR)

        assertEquals(mapOf(H0 to 70), db.read(LAT, LON, H0, H0 + HOUR))
    }

    @Test
    fun `the throttle's last attempt outlives the Host`() {
        val lastAttempt = PriorDayCloudRun.LastAttempt()
        db.host(LAT, LON, lastAttempt).lastAttemptMs = NOW

        assertEquals(NOW, db.host(LAT, LON, lastAttempt).lastAttemptMs)
    }

    companion object {
        const val LAT = 37.417
        const val LON = -122.089
        /** Inside [com.weatherwidget.data.local.LocationMatch.SAME_SITE_TOLERANCE_DEG]. */
        const val FRAGMENT_LAT = 37.418
        /** Inside the ±0.1° read box, another site. */
        const val NEIGHBOUR_LAT = 37.467
        const val HOUR = 3_600_000L
        val H0: Long = LocalDate.of(2026, 10, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 15 * HOUR
        val H1: Long = H0 + HOUR
        val NOW: Long = H1 + 2 * HOUR
    }
}
