package com.weatherwidget.shared.util

/**
 * How loudly a source-failure banner speaks, by how long it has been up (user's call 2026-10-06: tiny at 8 s, 50 % transparent at 24 s):
 * full for [TINY_AFTER_MS], then one tiny line, then faint from [FADED_AFTER_MS]. The banner never
 * disappears on its own — a failure is still true — it just stops shouting.
 */
enum class FailureBannerStage {
    FULL,
    TINY,
    FADED,
    ;

    companion object {
        const val TINY_AFTER_MS = 8_000L
        const val FADED_AFTER_MS = 24_000L

        /** Opacity of the [FADED] stage. */
        const val FADED_ALPHA = 0.5f

        /** A negative age (clock stepped back) shows the full banner rather than hiding news. */
        fun at(ageMs: Long): FailureBannerStage = when {
            ageMs < TINY_AFTER_MS -> FULL
            ageMs < FADED_AFTER_MS -> TINY
            else -> FADED
        }

        /** Delays from the banner's start at which a static surface must repaint to change stage. */
        val STAGE_CHANGE_DELAYS_MS = longArrayOf(TINY_AFTER_MS, FADED_AFTER_MS)
    }
}
