package com.weatherwidget.data.remote

import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Android classifies the live exception; desktop classifies what it logged. Both must land on the same
 * code, or the two platforms' failure pills say different things for the same failure.
 */
@Category(ShortDuration::class)
class FetchErrorCodeTest {

    private fun apiError(status: Int, body: String = "{}") = ApiAccessException(
        source = WeatherSource.GOOGLE_WEATHER,
        statusCode = status,
        detail = body,
        message = "Google: status $status. Detail: $body",
    )

    private val dailyQuotaBody = """{"error":{"code":429,"details":[{"metadata":{"quota_unit":"1/d/{project}"}}]}}"""

    private val cases: List<Pair<Throwable, String>> = listOf(
        apiError(401) to "HTTP_401",
        apiError(403) to "HTTP_403",
        apiError(429) to "HTTP_429",
        apiError(503) to "HTTP_503",
        apiError(429, dailyQuotaBody) to GoogleQuota.ERROR_CODE_DAILY,
        GoogleDailyQuotaException(resetAtMs = 0L, detail = dailyQuotaBody) to GoogleQuota.ERROR_CODE_DAILY,
        NwsPointUnavailableException("InvalidPoint") to FetchErrorCode.NO_COVERAGE,
        UnknownHostException("api.weather.gov") to "DNS_ERROR",
        ConnectException("refused") to "CONN_REFUSED",
        SocketTimeoutException("read timed out") to "TIMEOUT",
        SSLHandshakeException("bad cert") to "SSL_ERROR",
        SocketException("reset") to "SOCKET_ERROR",
        IllegalArgumentException("parse") to "IllegalArgumentExcep",
    )

    @Test
    fun `live exception maps to its code`() {
        cases.forEach { (error, expected) ->
            assertEquals("${error::class.simpleName}", expected, FetchErrorCode.of(error))
        }
    }

    @Test
    fun `desktop's logged failure maps to the same code as the live exception`() {
        cases.forEach { (error, expected) ->
            val logged = CurrentTempStatusLog.failure("google", error)
            val code = FetchErrorCode.fromLogged(
                CurrentTempStatusLog.parseFailureClassName(logged),
                CurrentTempStatusLog.parseFailureDetail(logged),
            )
            assertEquals("logged: $logged", FetchErrorCode.of(error), code)
            assertEquals(expected, code)
        }
    }

    @Test
    fun `background data restriction only applies on the live path that can see it`() {
        assertEquals("DATA_RESTRICTED", FetchErrorCode.of(ConnectException("x")) { true })
        assertEquals("DATA_RESTRICTED", FetchErrorCode.of(SocketException("x")) { true })
        assertEquals("DNS_ERROR", FetchErrorCode.of(UnknownHostException("x")) { true })
    }
}
