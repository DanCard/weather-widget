package com.weatherwidget.shared.util

/**
 * Why a Synoptic fetch failed — the two classes that deserve different recovery.
 *
 * A 30-second network stall and an all-day token rejection both arrive as [com.weatherwidget.data.remote.FetchOutcome.Failed],
 * but they are not the same event: the stall will clear on the next attempt, while a rejected
 * token will not. [TRANSPORT] gets a fixed short wait and no escalation; [REJECTION] keeps the
 * 30-min-to-6-h doubling built for the 2026-09-08 quota outage.
 */
enum class FailureClass {
    /** Timeouts, IOException, DNS — the network, not Synoptic. Gentle retry. */
    TRANSPORT,

    /** `RESPONSE_CODE != 1`, quota, auth, or an unparseable body Synoptic did answer. */
    REJECTION;

    companion object {
        /**
         * Classifies a [com.weatherwidget.data.remote.FetchOutcome.Failed] reason string.
         *
         * The reason is the redacted form of either `"ExceptionClass: message"` (from
         * `FetchOutcome.failed`) or an API-level string like `"synoptic: RESPONSE_CODE=2 …"`.
         * Anything that is not clearly transport is treated as a rejection — the conservative
         * choice, matching the behaviour this classification was carved out of.
         */
        fun of(reason: String): FailureClass {
            val lower = reason.lowercase()
            // Synoptic answered at the API level (or we asked for a credential check).
            if (lower.contains("response_code=") || lower.startsWith("synoptic:")) return REJECTION
            // Transport markers: Ktor/Java exception class names and the obvious message phrases.
            if (
                lower.contains("timeout") ||
                lower.contains("ioexception") ||
                lower.contains("connect") ||
                lower.contains("unknownhost") ||
                lower.contains("socket") ||
                lower.contains("dns") ||
                lower.contains("unreachable") ||
                lower.contains("network is unreachable") ||
                lower.contains("connection reset") ||
                lower.contains("connection refused") ||
                lower.contains("ssl") ||
                // Connection dropped mid-body. Specific phrases, not a bare "closed", which also
                // appears in parse errors ("unclosed string") that are not transport.
                lower.contains("connection closed") ||
                lower.contains("channel closed") ||
                lower.contains("stream closed") ||
                lower.contains("eofexception")
            ) {
                return TRANSPORT
            }
            return REJECTION
        }
    }
}
