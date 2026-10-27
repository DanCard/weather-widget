package com.weatherwidget.ui

import com.weatherwidget.data.remote.ProviderErrorDetails
import com.weatherwidget.data.remote.QuotaNoticeText
import com.weatherwidget.data.remote.SourceErrorDetails
import com.weatherwidget.data.remote.SourceErrorDetails.Explanation
import com.weatherwidget.data.remote.SourceErrorDetails.Label
import com.weatherwidget.shared.graph.FailureBannerLayout
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.experimental.categories.Category
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The desktop details card's English ([SourceErrorDetails.English]) equals Android's default
 * `strings.xml`, which stays the source of the localized page — so the two say the same thing
 * (`plans/261027-error-and-quota-banner-click-shows-details-both-platforms.md`). Pure JVM.
 */
@Category(ShortDuration::class)
class SourceErrorDetailsEnglishParityTest {
    private val strings: Map<String, String> by lazy {
        val resDir = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, "values/strings.xml"))
        val nodes = doc.documentElement.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'").replace("\\\"", "\"") }
    }

    private fun res(name: String, vararg args: String): String {
        val template = strings[name]
        assertNotNull("missing string resource $name", template)
        return args.foldIndexed(template!!) { i, s, arg -> s.replace("%${i + 1}\$s", arg) }
    }

    @Test
    fun `every row label matches its resource`() {
        for (label in Label.entries) {
            val name = "error_details_label_${label.name.lowercase()}"
            assertEquals(label.name, res(name), SourceErrorDetails.English.label(label))
        }
    }

    @Test
    fun `explanations, headlines and limits match their resources`() {
        val content = { e: Explanation ->
            SourceErrorDetails.Content(
                SourceErrorDetails.Headline.FAILING, SourceErrorDetails.Summary.Failing, e, 0L, emptyList(), null, false,
            )
        }
        val reset = QuotaNoticeText.formatResetTime(0L)
        assertEquals(res("error_details_explain_quota", reset), SourceErrorDetails.English.explanation(content(Explanation.QUOTA)))
        assertEquals(res("error_details_explain_rate_limit"), SourceErrorDetails.English.explanation(content(Explanation.RATE_LIMIT)))
        assertEquals(res("error_details_explain_auth"), SourceErrorDetails.English.explanation(content(Explanation.AUTH)))
        assertEquals(res("error_details_explain_data"), SourceErrorDetails.English.explanation(content(Explanation.DATA_RESTRICTED)))
        assertEquals(res("error_details_explain_generic"), SourceErrorDetails.English.explanation(content(Explanation.GENERIC)))
        assertEquals("X " + res("updates_paused"), SourceErrorDetails.English.headline("x", SourceErrorDetails.Headline.PAUSED))
        assertEquals("X " + res("updates_rate_limited"), SourceErrorDetails.English.headline("x", SourceErrorDetails.Headline.RATE_LIMITED))
        assertEquals("X " + res("updates_failing"), SourceErrorDetails.English.headline("x", SourceErrorDetails.Headline.FAILING))
        assertEquals(res("error_details_limit_per_day", "60"), QuotaNoticeText.limitText("60", ProviderErrorDetails.QuotaPeriod.DAY))
        assertEquals(res("error_details_limit_per_minute", "20"), QuotaNoticeText.limitText("20", ProviderErrorDetails.QuotaPeriod.MINUTE))
        assertEquals(res("error_details_none"), SourceErrorDetails.English.NONE)
        assertEquals(res("error_details_show_response"), SourceErrorDetails.English.SHOW_RESPONSE)
        assertEquals(res("error_details_hide_response"), SourceErrorDetails.English.HIDE_RESPONSE)
        assertEquals(res("error_details_api_key_settings"), SourceErrorDetails.English.API_KEY_SETTINGS)
    }

    @Test
    fun `error code phrases match their resources`() {
        val codes = mapOf(
            "HTTP_400" to "watermark_http_400", "HTTP_401" to "watermark_http_401", "HTTP_403" to "watermark_http_403",
            "HTTP_404" to "watermark_http_404", "HTTP_422" to "watermark_http_422", "HTTP_429" to "watermark_http_429",
            "ACCESS_ERROR" to "watermark_access_error", "DNS_ERROR" to "watermark_dns_error",
            "CONN_REFUSED" to "watermark_conn_refused", "DATA_RESTRICTED" to "watermark_data_restricted",
            "TIMEOUT" to "watermark_timeout", "SSL_ERROR" to "watermark_ssl_error", "SOCKET_ERROR" to "watermark_socket_error",
            "NO_COVERAGE" to "watermark_no_coverage",
        )
        for ((code, name) in codes) assertEquals(code, res(name), FailureBannerLayout.humanReadableErrorCode(code))
        assertEquals(res("watermark_server_error", "503"), FailureBannerLayout.humanReadableErrorCode("HTTP_503"))
    }
}
