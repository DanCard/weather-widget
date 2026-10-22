package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource

/**
 * The actuals-source picker's text, both platforms (Android `WeatherObservationsActivity`, desktop
 * `ObservationsWindow`), so the rows and the "what is read" value cannot drift apart.
 */
object ActualsPickerLabels {

    /** `"NWS  (default, measured)"`, plus `" — not available at this location"` / `" — needs API key"`. */
    fun row(candidate: ActualsProviderResolver.Candidate): String {
        val marks = buildList {
            if (candidate.isDefault) add("default")
            add(
                when (candidate.tier) {
                    ActualsProviderResolver.Tier.MEASURED -> "measured"
                    ActualsProviderResolver.Tier.DERIVED -> "derived"
                },
            )
        }
        val reason = when (candidate.unavailable) {
            ActualsProviderResolver.Unavailable.OUTSIDE_COVERAGE -> " — not available at this location"
            ActualsProviderResolver.Unavailable.NO_KEY -> " — needs API key"
            null -> ""
        }
        return "${candidate.source.displayName}  (${marks.joinToString(", ")})$reason"
    }

    /**
     * The row's value: the feed actually read, and the stored choice when that one cannot serve
     * here — `"METAR (NWS not available here)"`.
     */
    fun value(readId: String, overridden: WeatherSource?): String {
        val read = WeatherSource.fromId(readId).displayName
        return if (overridden == null) read else "$read (${overridden.displayName} not available here)"
    }
}
