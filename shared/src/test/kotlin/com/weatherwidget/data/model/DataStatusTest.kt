package com.weatherwidget.data.model

import org.junit.Assert.*
import org.junit.Test

class DataStatusTest {

    private val now = 1000L

    @Test
    fun `no cache and not failed is Loading`() {
        val status = deriveDataStatus(cachePresent = false, lastFetchMs = null, refreshFailed = false, failureIsOffline = false, now = now)
        assertEquals(DataStatus.Loading, status)
    }

    @Test
    fun `no cache and failed is NoData`() {
        val status = deriveDataStatus(cachePresent = false, lastFetchMs = null, refreshFailed = true, failureIsOffline = false, now = now)
        assertEquals(DataStatus.NoData, status)
    }

    @Test
    fun `cache present and not failed is Live`() {
        val status = deriveDataStatus(cachePresent = true, lastFetchMs = 500L, refreshFailed = false, failureIsOffline = false, now = now)
        assertEquals(DataStatus.Live(500L), status)
    }

    @Test
    fun `cache present and failed with offline is Stale-OFFLINE`() {
        val status = deriveDataStatus(cachePresent = true, lastFetchMs = 500L, refreshFailed = true, failureIsOffline = true, now = now)
        assertEquals(DataStatus.Stale(500L, StaleReason.OFFLINE), status)
    }

    @Test
    fun `cache present and failed with source error is Stale-SOURCE_ERROR`() {
        val status = deriveDataStatus(cachePresent = true, lastFetchMs = 500L, refreshFailed = true, failureIsOffline = false, now = now)
        assertEquals(DataStatus.Stale(500L, StaleReason.SOURCE_ERROR), status)
    }

    @Test
    fun `cache present with null lastFetch uses now`() {
        val status = deriveDataStatus(cachePresent = true, lastFetchMs = null, refreshFailed = false, failureIsOffline = false, now = now)
        assertEquals(DataStatus.Live(now), status)
    }

    @Test
    fun `isOfflineException recognizes ConnectException by class name`() {
        val e = createExceptionWithClass("java.net.ConnectException")
        assertTrue(isOfflineException(e))
    }

    @Test
    fun `isOfflineException recognizes UnknownHostException by class name`() {
        val e = createExceptionWithClass("java.net.UnknownHostException")
        assertTrue(isOfflineException(e))
    }

    @Test
    fun `isOfflineException recognizes UnresolvedAddressException despite null message`() {
        // Ktor CIO's DNS failure: class-name match is the only path (message is null).
        val e = java.nio.channels.UnresolvedAddressException()
        assertNull(e.message)
        assertTrue(isOfflineException(e))
    }

    @Test
    fun `isOfflineException recognizes connection refused by message`() {
        val e = RuntimeException("Connection refused: connect")
        assertTrue(isOfflineException(e))
    }

    @Test
    fun `isOfflineException recognizes failed to connect by message`() {
        val e = RuntimeException("Failed to connect to api.weather.gov")
        assertTrue(isOfflineException(e))
    }

    @Test
    fun `isOfflineException rejects HTTP 500`() {
        val e = RuntimeException("HTTP 500: Internal Server Error")
        assertFalse(isOfflineException(e))
    }

    @Test
    fun `isOfflineException rejects parse error`() {
        val e = kotlinx.serialization.SerializationException("Unexpected JSON token")
        assertFalse(isOfflineException(e))
    }

    private fun createExceptionWithClass(className: String): Exception {
        return when (className) {
            "java.net.ConnectException" -> java.net.ConnectException("Connection refused")
            "java.net.UnknownHostException" -> java.net.UnknownHostException("Unknown host")
            else -> Exception("generic")
        }
    }
}
