package com.weatherwidget.data.remote

import io.ktor.client.plugins.ClientRequestException

/**
 * The short code a failed fetch is recorded and shown under ("HTTP_429", "DNS_ERROR", "QUOTA_DAILY", …)
 * — what the failure banner's detail line and the error page are worded from. One rule for both
 * platforms: Android classifies the live exception, desktop the class name and detail it logged
 * ([com.weatherwidget.data.local.desktop.CurrentTempStatusLog]).
 */
object FetchErrorCode {
    /**
     * [isBackgroundDataRestricted] is Android's "background data blocked" check: a refused connection
     * under that restriction is the OS, not the network. Desktop has no such state.
     */
    fun of(error: Throwable, isBackgroundDataRestricted: () -> Boolean = { false }): String = when (error) {
        // NWS 404 InvalidPoint: the site is outside its (US-only) coverage, not a transient failure.
        is NwsPointUnavailableException -> NO_COVERAGE
        // A daily quota cannot recover before its reset; the banner says when that is.
        is ApiAccessException ->
            if (GoogleQuota.isDailyQuotaExhausted(error)) {
                GoogleQuota.ERROR_CODE_DAILY
            } else {
                error.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"
            }
        is ClientRequestException -> "HTTP_${error.response.status.value}"
        else -> forClassName(error.javaClass.simpleName, isBackgroundDataRestricted)
    }

    /**
     * A failure known only by its logged exception [className] and [detail] (desktop's status rows,
     * whose detail carries `status NNN` for an [ApiAccessException]).
     */
    fun fromLogged(className: String, detail: String): String {
        if (className == NwsPointUnavailableException::class.simpleName) return NO_COVERAGE
        val statusCode =
            if (className in API_ACCESS_CLASS_NAMES) {
                STATUS.find(detail)?.groupValues?.get(1)?.toIntOrNull()
            } else {
                null
            }
        return when {
            className == GoogleDailyQuotaException::class.simpleName ||
                GoogleQuota.isDailyQuotaExhausted(statusCode, detail) -> GoogleQuota.ERROR_CODE_DAILY
            statusCode != null -> "HTTP_$statusCode"
            className in API_ACCESS_CLASS_NAMES -> "ACCESS_ERROR"
            else -> forClassName(className) { false }
        }
    }

    private fun forClassName(name: String, isBackgroundDataRestricted: () -> Boolean): String = when {
        name.contains("UnknownHost") || name.contains("UnresolvedAddress") -> "DNS_ERROR"
        name.contains("ConnectException") || name.contains("ConnectionRefused") ->
            if (isBackgroundDataRestricted()) "DATA_RESTRICTED" else "CONN_REFUSED"
        name.contains("Timeout") -> "TIMEOUT"
        name.contains("SSL") || name.contains("TLS") -> "SSL_ERROR"
        name.contains("SocketException") ->
            if (isBackgroundDataRestricted()) "DATA_RESTRICTED" else "SOCKET_ERROR"
        else -> name.take(20).ifBlank { "ERROR" }
    }

    const val NO_COVERAGE = "NO_COVERAGE"

    private val API_ACCESS_CLASS_NAMES = setOf(
        ApiAccessException::class.simpleName,
        GoogleDailyQuotaException::class.simpleName,
    )
    private val STATUS = Regex("""\bstatus\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
}
