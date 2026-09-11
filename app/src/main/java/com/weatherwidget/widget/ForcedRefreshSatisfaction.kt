package com.weatherwidget.widget

/** Whether a forced refresh made at [requestedAtMs] has since been satisfied by a fetch at [lastSuccessMs]. */
internal object ForcedRefreshSatisfaction {
    fun isSatisfied(requestedAtMs: Long, lastSuccessMs: Long): Boolean =
        requestedAtMs > 0L && lastSuccessMs > requestedAtMs
}
