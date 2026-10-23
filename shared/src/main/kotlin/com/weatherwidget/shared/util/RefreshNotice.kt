package com.weatherwidget.shared.util

/**
 * What a user's refresh action did, for the two-second toast every surface shows (user, 2026-10-15):
 * which kind of refresh it queued, or why it queued nothing. One action gets one notice — a widget
 * source toggle runs two staleness checks and [merge]s their results.
 *
 * Only user actions announce; background, cadence and gap-fill fetches never do. Android maps a notice
 * to string resources (`RefreshToast`); desktop uses [text]. See
 * plans/261015-refresh-queued-toast-all-surfaces.md.
 */
sealed interface RefreshNotice {
    /** FORECAST: a full sync (it also backfills that source's actuals). OBSERVATIONS: current/recent observations only. */
    enum class Kind { FORECAST, OBSERVATIONS, BOTH }

    /** Ordered by how specific the reason is: [merge] keeps the higher one. */
    enum class Reason { UP_TO_DATE, ALREADY_QUEUED, IN_PROGRESS, QUOTA, NO_LOCATION }

    data class Queued(val kind: Kind) : RefreshNotice

    data class NotQueued(
        val reason: Reason,
        /** The source's display name; UP_TO_DATE and QUOTA name it. */
        val sourceName: String? = null,
        /** What was found up to date (UP_TO_DATE only). */
        val subject: Kind = Kind.FORECAST,
        /** How old the data is (UP_TO_DATE only). */
        val ageMs: Long? = null,
        /** When the quota block ends (QUOTA only). */
        val untilMs: Long? = null,
    ) : RefreshNotice

    /**
     * At most one notice per [DURATION_MS] ([shouldShow]) — the one throttle both platforms keep
     * (Android `RefreshToast`, desktop `RefreshNoticeState`).
     */
    class Gate(private val clock: () -> Long) {
        private var lastShownMs: Long? = null

        /** True when a notice may show now; it then counts as shown. */
        fun admit(): Boolean = synchronized(this) {
            val now = clock()
            shouldShow(lastShownMs, now).also { if (it) lastShownMs = now }
        }

        /** A refresh's ending answers the same tap as its notice: it replaces it and restarts the window. */
        fun restart() = synchronized(this) { lastShownMs = clock() }
    }

    companion object {
        /** Every notice, shown, suppressed or silent, writes one row under this tag ([line], [outcomeLine]). */
        const val TAG = "REFRESH_TOAST"

        /** [outcome]: "shown", "suppressed", "silent", or a platform's variant ("shown banner=2"). */
        fun line(origin: String, notice: RefreshNotice, outcome: String): String =
            "origin=$origin ${logDetail(notice)} $outcome"

        /** A Forecast History Refresh's ending, shown ([RefreshOutcome.text]). */
        fun outcomeLine(origin: String, outcome: RefreshOutcome): String =
            "origin=$origin done=${outcome::class.simpleName}${RefreshOutcome.logDetail(outcome)} shown"

        /**
         * Four seconds (user, 2026-10-15: two was easy to miss). Widget banner and desktop use it exactly;
         * an Android Toast cannot, so app screens use `LENGTH_LONG`, the platform's longest (3.5 s).
         */
        const val DURATION_MS = 4_000L

        /** At most one notice per [DURATION_MS]: fast cycling through stale sources must not stack toasts. */
        fun shouldShow(lastShownMs: Long?, nowMs: Long): Boolean =
            lastShownMs == null || nowMs < lastShownMs || nowMs - lastShownMs >= DURATION_MS

        /** A queued refresh beats a reason for none; two queued kinds add up; of two reasons the more specific wins. */
        fun merge(a: RefreshNotice?, b: RefreshNotice?): RefreshNotice? = when {
            a == null -> b
            b == null -> a
            a is Queued && b is Queued -> Queued(if (a.kind == b.kind) a.kind else Kind.BOTH)
            a is Queued -> a
            b is Queued -> b
            else -> if ((b as NotQueued).reason > (a as NotQueued).reason) b else a
        }

        /** Source toggles drop this one: switching sources is browsing, and "up to date" is noise there. */
        fun isUpToDate(notice: RefreshNotice): Boolean =
            notice is NotQueued && notice.reason == Reason.UP_TO_DATE

        /** "23m", "2h", "2h 10m"; under a minute reads "1m", never "just now ago". */
        fun formatAge(ageMs: Long): String = AgeFormatter.formatDuration(ageMs.coerceAtLeast(60_000L))

        /** Desktop text (English). Android's strings are the same sentences, translated. */
        fun text(notice: RefreshNotice, formatTime: (Long) -> String): String = when (notice) {
            is Queued -> when (notice.kind) {
                Kind.FORECAST -> "Forecast refresh queued"
                Kind.OBSERVATIONS -> "Observations refresh queued"
                Kind.BOTH -> "Forecast and observations refresh queued"
            }
            is NotQueued -> when (notice.reason) {
                Reason.UP_TO_DATE -> {
                    val what = if (notice.subject == Kind.OBSERVATIONS) "observations" else "forecast"
                    val subject = notice.sourceName?.let { "$it $what" } ?: what.replaceFirstChar { it.uppercase() }
                    notice.ageMs?.let { "$subject up to date · ${formatAge(it)} ago" } ?: "$subject up to date"
                }
                Reason.ALREADY_QUEUED -> "Refresh already queued"
                Reason.IN_PROGRESS -> "Refresh already in progress"
                Reason.QUOTA -> {
                    val name = notice.sourceName ?: "Source"
                    notice.untilMs?.let { "$name limit reached · next refresh after ${formatTime(it)}" }
                        ?: "$name limit reached"
                }
                Reason.NO_LOCATION -> "No location to refresh"
            }
        }

        /** The [TAG] log detail, both platforms. */
        fun logDetail(notice: RefreshNotice): String = when (notice) {
            is Queued -> "queued=${notice.kind}"
            is NotQueued -> buildString {
                append("none=${notice.reason}")
                notice.sourceName?.let { append(" source=$it") }
                if (notice.reason == Reason.UP_TO_DATE) append(" subject=${notice.subject}")
                notice.ageMs?.let { append(" ageMin=${it / 60_000L}") }
                notice.untilMs?.let { append(" until=$it") }
            }
        }
    }
}
