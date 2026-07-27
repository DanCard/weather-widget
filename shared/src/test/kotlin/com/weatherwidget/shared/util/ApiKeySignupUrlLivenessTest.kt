package com.weatherwidget.shared.util

import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Guards the Settings "Get key…" signup links against going stale.
 *
 * The structural test always runs. The liveness test performs real network requests, so it
 * SKIPS (JUnit assumption) rather than fails when the machine is offline — a red suite on a
 * plane would teach people to ignore it. When online, a dead or moved link (>=400 after
 * redirects, or unresolvable host) fails with every broken URL listed.
 *
 * Moved from `:app` to `:shared` so both clients (Android + desktop) exercise the same URLs;
 * the [ApiKeySignupUrls] object it guards also lives in `:shared`.
 */
@Category(LongDuration::class)
class ApiKeySignupUrlLivenessTest {

    @Test
    fun everyKeyRequiringSourceHasAnHttpsSignupUrl() {
        for (source in ApiKeySignupUrls.sourcesRequiringKeys) {
            val url = ApiKeySignupUrls.signupUrl(source)
            assertTrue(
                "signup URL for ${source.id} must be https, got: $url",
                url.startsWith("https://"),
            )
        }
    }

    @Test
    fun signupUrlsAreLive() {
        assumeTrue("network unavailable — skipping liveness check", isNetworkAvailable())

        val failures = mutableListOf<String>()
        for (source in ApiKeySignupUrls.sourcesRequiringKeys) {
            val url = ApiKeySignupUrls.signupUrl(source)
            val result = fetchWithRetries(source.id, url)
            if (result.code !in 200..399) {
                failures += "${source.id}: $url -> ${result.describe()}"
            }
        }
        assertTrue("stale signup links:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private data class FetchResult(val code: Int, val error: String? = null, val attempts: Int = 1) {
        fun describe(): String =
            (error?.let { "error: $it" } ?: "HTTP $code") +
                if (attempts > 1) " (after $attempts attempts)" else ""
    }

    /**
     * Retries only error-shaped results (code -1: connect/read timeout, DNS hiccup) — a one-off
     * network blip shouldn't red the suite. A real HTTP status (404/410/5xx) is the server's
     * answer and fails on the first try, keeping genuine stale links loud. Each retry is printed
     * so a pass-after-retry is distinguishable from a clean pass in the test log — creeping
     * provider flakiness stays visible instead of being silently absorbed.
     */
    private fun fetchWithRetries(sourceId: String, url: String, pausesMs: List<Long> = listOf(1_000, 2_000)): FetchResult {
        val maxAttempts = pausesMs.size + 1
        var result = fetchFinalStatus(url)
        var attempt = 1
        for (pause in pausesMs) {
            if (result.code != -1) break
            println("signupUrlsAreLive: $sourceId attempt $attempt/$maxAttempts failed (${result.describe()}) — retrying in ${pause}ms: $url")
            Thread.sleep(pause)
            attempt++
            result = fetchFinalStatus(url)
        }
        return result.copy(attempts = attempt)
    }

    /**
     * GET (some hosts reject HEAD) with manual redirect following so cross-host/protocol hops
     * count as success paths rather than surprises. Browser UA: several providers sit behind
     * CDNs that reject default Java/curl agents.
     */
    private fun fetchFinalStatus(startUrl: String, maxHops: Int = 5): FetchResult {
        var url = startUrl
        repeat(maxHops) {
            val conn = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
                    )
                }
            } catch (e: Exception) {
                return FetchResult(-1, e.toString())
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return FetchResult(code, "redirect without Location")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                return FetchResult(code)
            } catch (e: Exception) {
                return FetchResult(-1, e.toString())
            } finally {
                conn.disconnect()
            }
        }
        return FetchResult(-1, "too many redirects (> $maxHops) from $startUrl")
    }

    /** Cheap connectivity probe: TCP to a public resolver, no DNS dependency. */
    private fun isNetworkAvailable(): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2_000) }
            true
        } catch (e: Exception) {
            false
        }
}
