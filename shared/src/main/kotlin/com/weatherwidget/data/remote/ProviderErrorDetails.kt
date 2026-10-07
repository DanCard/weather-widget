package com.weatherwidget.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a failed request said, pulled out of the stored failure message — the `require2xx` shape
 * "`<label> (<path>): status <code>. Detail: <body>`". Built for a person reading an error page: every
 * field is optional, and anything unparseable stays available as [rawBody].
 */
data class ProviderErrorDetails(
    /** The endpoint, e.g. `/forecast/hours:lookup`, when the message names one. */
    val request: String?,
    val httpStatus: Int?,
    /** The provider's own sentence (`error.message`), else the whole message when it has no body. */
    val providerMessage: String?,
    /** Google ErrorInfo: `ForecastHoursQueriesPerDay`. */
    val quotaName: String?,
    val quotaLimit: String?,
    /** [QuotaPeriod.DAY] for `1/d/{project}`, [QuotaPeriod.MINUTE] for `1/min/{project}`. */
    val quotaPeriod: QuotaPeriod?,
    /** Google ErrorInfo `quota_metric`, e.g. `weather.googleapis.com/forecast/hours`. */
    val quotaMetric: String?,
    val quotaWindowStartMs: Long?,
    /** The response body, pretty-printed when it is JSON. */
    val rawBody: String?,
) {
    enum class QuotaPeriod { MINUTE, DAY }

    companion object {
        private val STATUS = Regex("""\bstatus\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
        private val REQUEST = Regex("""\((/[^)\s]*)\)""")
        private const val BODY_MARKER = "Detail: "
        private val json = Json { prettyPrint = true }

        fun parse(failureMessage: String?): ProviderErrorDetails? {
            val message = failureMessage?.takeIf { it.isNotBlank() } ?: return null
            // A per-product quota block stores the bare 429 body (`QuotaRefusal.detail`), no marker.
            val bareBody = BODY_MARKER !in message && message.trimStart().startsWith("{")
            val head = if (bareBody) "" else message.substringBefore(BODY_MARKER)
            val body = (if (bareBody) message else message.substringAfter(BODY_MARKER, "")).trim().takeIf { it.isNotEmpty() }
            val parsed = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            val error = (parsed as? JsonObject)?.get("error") as? JsonObject
            val metadata = error?.get("details")
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { (it as? JsonObject)?.get("metadata") as? JsonObject }
                ?.firstOrNull()

            return ProviderErrorDetails(
                request = REQUEST.find(head)?.groupValues?.get(1),
                httpStatus = STATUS.find(head)?.groupValues?.get(1)?.toIntOrNull(),
                providerMessage = error?.string("message")
                    ?: body?.takeIf { parsed == null }
                    ?: head.trim().takeIf { body == null },
                quotaName = metadata?.string("quota_limit"),
                quotaLimit = metadata?.string("quota_limit_value"),
                quotaPeriod = metadata?.string("quota_unit")?.let(::period),
                quotaMetric = metadata?.string("quota_metric"),
                quotaWindowStartMs = metadata?.string("window_start_time")?.toLongOrNull()?.times(1000L),
                rawBody = parsed?.let { json.encodeToString(JsonElement.serializer(), it) } ?: body,
            )
        }

        private fun period(unit: String): QuotaPeriod? = when {
            unit.startsWith("1/d/") -> QuotaPeriod.DAY
            unit.startsWith("1/min/") -> QuotaPeriod.MINUTE
            else -> null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
