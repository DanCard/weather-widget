package com.weatherwidget.data.remote

/**
 * Strips credential values out of error text before it is logged or mailed.
 *
 * Ktor exception messages embed the full request URL, so a Synoptic timeout used to land in
 * `app_logs` as `…timeseries?token=<live token>&…` on both platforms and ride along in every
 * bug-report email (last 300 log lines). [redact] is the single chokepoint: [FetchOutcome.failed]
 * and the remote clients' `Log.w(…, "$e")` calls both run through it.
 *
 * Only the *values* of known credential query parameters are replaced — `token=<redacted>` — so
 * support can still see which parameter was present without seeing the secret.
 */
object ApiKeyRedaction {
    /**
     * Query-parameter names whose values are secrets. Longest-first so `apikey` wins over `key`.
     * The lookbehind (not `\b`) lets `access_token=` match — `_` is a word character — while
     * `interactionToken=` / `mykey=` stay untouched.
     */
    private val CREDENTIAL_PARAM = Regex(
        """(?i)(?<![a-z0-9])(token|appid|api_key|apikey|key)=([^&\s"'<>\])]+)""",
    )

    const val REDACTED = "<redacted>"

    /** Replaces the value of every credential query parameter in [text] with [REDACTED]. */
    fun redact(text: String): String =
        CREDENTIAL_PARAM.replace(text) { match -> "${match.groupValues[1]}=$REDACTED" }
}
