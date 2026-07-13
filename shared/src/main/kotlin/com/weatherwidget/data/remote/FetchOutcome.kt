package com.weatherwidget.data.remote

/**
 * Outcome of a remote lookup that must distinguish "the source definitively has nothing usable"
 * from "we never got a usable answer". Collapsing both into null is what let transport failures
 * masquerade as silent stations in the observations pipeline (see
 * plans/260713-observation-fetch-error-hardening.md).
 */
sealed class FetchOutcome<out T> {
    data class Success<T>(val value: T) : FetchOutcome<T>()

    /** The conversation with the source completed and it definitively has no usable data. */
    data object NoData : FetchOutcome<Nothing>()

    /** Transport/HTTP/parse failure — nothing was learned about the source. */
    data class Failed(val reason: String) : FetchOutcome<Nothing>()

    fun valueOrNull(): T? = (this as? Success)?.value

    companion object {
        fun failed(e: Throwable): Failed = Failed("${e::class.simpleName}: ${e.message}")
    }
}

/**
 * Whether a station's newest observation row should record a completed fetch attempt
 * (fetchedAt touch) after a fetch stored nothing: true when at least one upstream completed
 * with a definitive [FetchOutcome.NoData]. All-failed means we learned nothing about the
 * station — leave fetchedAt alone and report the failure instead, so a dead network can never
 * masquerade as a silent station.
 */
fun shouldTouchObservationFetchedAt(primary: FetchOutcome<*>, fallback: FetchOutcome<*>?): Boolean =
    primary is FetchOutcome.NoData || fallback is FetchOutcome.NoData
