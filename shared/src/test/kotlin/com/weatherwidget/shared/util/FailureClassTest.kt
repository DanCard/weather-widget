package com.weatherwidget.shared.util

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the transport-vs-rejection split that decides whether a Synoptic failure earns the gentle
 * 5-minute wait or the 30-min-to-6-h doubling. Mutation check: collapsing the two classes makes
 * the transport cases here fail (they would return REJECTION).
 */
@Category(ShortDuration::class)
class FailureClassTest {

    @Test
    fun `timeout exceptions are transport`() {
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("HttpRequestTimeoutException: timeout after 30000 ms"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("SocketTimeoutException: connect timed out"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("ConnectTimeoutException: Connect timeout"))
    }

    @Test
    fun `io and dns exceptions are transport`() {
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("IOException: unexpected end of stream"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("UnknownHostException: api.synopticdata.com"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("ConnectException: Connection refused"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("SSLException: Connection reset"))
    }

    @Test
    fun `api rejection is rejection`() {
        assertEquals(FailureClass.REJECTION, FailureClass.of("synoptic: RESPONSE_CODE=2 (no message)"))
        assertEquals(FailureClass.REJECTION, FailureClass.of("synoptic: Invalid token"))
        assertEquals(FailureClass.REJECTION, FailureClass.of("synoptic: null"))
    }

    @Test
    fun `null or blank api message is still rejection`() {
        // 2026-09-08: RESPONSE_CODE=2 with no message — the API spoke, even if it said nothing.
        assertEquals(FailureClass.REJECTION, FailureClass.of("synoptic: RESPONSE_CODE=2 (no message)"))
    }

    @Test
    fun `parse of a received body is rejection not transport`() {
        // Synoptic answered; we could not read it. Not a network stall.
        assertEquals(FailureClass.REJECTION, FailureClass.of("parse: Unexpected JSON token at offset 12"))
    }

    @Test
    fun `unknown failure defaults to rejection`() {
        assertEquals(FailureClass.REJECTION, FailureClass.of("something went wrong"))
    }

    @Test
    fun `synoptic api message containing the word timeout is still rejection`() {
        // The API spoke at the application level; do not let a single word reclassify it.
        assertEquals(FailureClass.REJECTION, FailureClass.of("synoptic: request timeout on our side"))
    }

    @Test
    fun `a parse error mentioning an unclosed string is not transport`() {
        assertEquals(FailureClass.REJECTION, FailureClass.of("parse: Unexpected JSON token: unclosed string literal"))
        assertEquals(FailureClass.TRANSPORT, FailureClass.of("IOException: Connection closed by peer"))
    }
}
