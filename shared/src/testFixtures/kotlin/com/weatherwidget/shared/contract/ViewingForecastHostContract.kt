package com.weatherwidget.shared.contract

import com.weatherwidget.shared.util.ViewingForecastRefresh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [ViewingForecastRefresh.Host] reads, on Android's prefs Host and the desktop daemon's SQLite Host:
 * nothing at first, and a fetch recorded the platform's own way is the one the 2-hour limit is measured
 * from. Were the two to disagree, a viewed source would refetch on every look or never.
 */
abstract class ViewingForecastHostContract {
    protected abstract class Fixture(val host: ViewingForecastRefresh.Host) {
        val fetches = mutableListOf<String>()
        /** Records a successful forecast fetch of [sourceId] as the platform's fetch path does. */
        abstract fun recordFetch(sourceId: String, atMs: Long)
        /** Records a successful provider check that wrote no new forecast, where the platform keeps that apart. */
        abstract fun recordCheck(sourceId: String, atMs: Long)
    }

    /** A Host on fresh storage at battery 85 %, unplugged, showing one source. */
    protected abstract fun open(): Fixture

    private val Fixture.source get() = host.viewedSourceIds().single()

    @Test
    fun `a source never fetched or requested reads null`() {
        val fixture = open()
        assertNull(fixture.host.lastFetchMs(fixture.source))
        assertNull(fixture.host.lastRequestMs(fixture.source))
    }

    @Test
    fun `a request reads back`() {
        val fixture = open()
        fixture.host.markRequested(fixture.source, T0)
        assertEquals(T0, fixture.host.lastRequestMs(fixture.source))
    }

    @Test
    fun `a recorded fetch holds the source fresh until the limit, then the runner fetches it once`() = runBlocking {
        val fixture = open()
        val fetchedAt = System.currentTimeMillis()
        fixture.recordFetch(fixture.source, fetchedAt)

        assertEquals(emptyList<String>(), ViewingForecastRefresh.run(fixture.host, "contract", fetchedAt + HOUR))
        assertEquals(emptyList<String>(), fixture.fetches)

        ViewingForecastRefresh.run(fixture.host, "contract", fetchedAt + 2 * HOUR + MINUTE)
        assertEquals(listOf(fixture.source), fixture.fetches)
    }

    @Test
    fun `a newer successful check holds the source fresh, however old its last fetch record`() = runBlocking {
        val fixture = open()
        val checkedAt = System.currentTimeMillis()
        fixture.recordFetch(fixture.source, checkedAt - 3 * HOUR)
        fixture.recordCheck(fixture.source, checkedAt)

        assertEquals(emptyList<String>(), ViewingForecastRefresh.run(fixture.host, "contract", checkedAt + HOUR))
        assertEquals(emptyList<String>(), fixture.fetches)
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val T0 = 1_792_900_800_000L
        const val LAT = 37.42
        const val LON = -122.08
    }
}
