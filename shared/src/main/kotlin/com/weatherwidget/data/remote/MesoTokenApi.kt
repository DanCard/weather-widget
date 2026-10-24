package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MesoTokenApi(
    private val httpClient: HttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var lastFailureMs: Long = 0L

    suspend fun token(forceRefresh: Boolean = false): String? = mutex.withLock {
        val now = nowMs()
        if (!forceRefresh) {
            if (cachedToken != null) return@withLock cachedToken
            if (now - lastFailureMs < FAILURE_TTL_MS) return@withLock null
        }
        val token = try {
            parse(httpClient.get(TOKEN_URL).body<String>()).also {
                if (it == null) Log.w(TAG, "weather.gov apiKey.js did not contain mesoToken")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to retrieve weather.gov mesoToken: ${ApiKeyRedaction.redact("$e")}")
            null
        }
        if (token != null) {
            cachedToken = token
            lastFailureMs = 0L
        } else {
            if (forceRefresh) cachedToken = null
            lastFailureMs = now
        }
        cachedToken
    }

    val hasCachedToken: Boolean get() = cachedToken != null

    companion object {
        internal const val TOKEN_URL = "https://www.weather.gov/source/wrh/apiKey.js"
        private const val TAG = "MesoTokenApi"
        internal const val FAILURE_TTL_MS = 2 * 60 * 1000L
        private val TOKEN_PATTERN = Regex(
            """\bmesoToken\s*=\s*["']([0-9a-fA-F]{32})["']""",
        )

        internal fun parse(source: String): String? =
            TOKEN_PATTERN.findAll(source).lastOrNull()?.groupValues?.get(1)
    }
}
