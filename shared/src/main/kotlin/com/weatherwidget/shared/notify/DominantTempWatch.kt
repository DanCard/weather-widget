package com.weatherwidget.shared.notify

import com.weatherwidget.shared.actuals.BlendContribution
import com.weatherwidget.shared.util.TempUtils
import java.util.Locale

/**
 * The one-shot "tell me when the dominant station's reading changes" watch, shared by Android and
 * desktop.
 *
 * The dominant station is the one holding the largest final IDW weight behind the currently
 * displayed temperature — the same [BlendContribution] the today-column overlay's station row and
 * the hourly graph's `knuq 73.4°` label name. This watch compares that station's reading between
 * evaluations and fires exactly once.
 *
 * Pure and platform-free on purpose: the decision is the part worth testing, and it is testable
 * here without a notification harness or a settings screen on either platform (see
 * testing-strategy — prefer pure-function extraction over mocking).
 */
data class DominantTempWatchState(
    val armed: Boolean = false,
    /** The station whose reading is the baseline. Null while armed but not yet primed. */
    val baselineStationId: String? = null,
    /**
     * The baseline reading, always in **Fahrenheit** — the app's storage unit.
     *
     * Stored raw rather than pre-formatted so a unit change between arming and firing cannot make
     * the message compare a Fahrenheit "was" against a Celsius "now".
     */
    val baselineTempF: Float? = null,
) {
    companion object {
        val DISARMED = DominantTempWatchState()
    }
}

/**
 * The user-visible wording, supplied by the caller.
 *
 * The decision lives in `:shared`, which has no Android resources — so the strings cannot live here
 * or the notification would be English on a device running in German. Android passes localized
 * resources; the desktop app has no localization layer at all and takes the defaults.
 */
data class DominantTempWatchStrings(
    val title: String = "Dominant station temperature changed",
    /** Same station, new reading. `%1$s` = station id, `%2$s` = the new reading, `%3$s` = the previous one. */
    val bodyFormat: String = "%1\$s %2\$s, was %3\$s",
    /**
     * A *different* station is now dominant. `%1$s` = new station id, `%2$s` = its reading,
     * `%3$s` = the previous station id, `%4$s` = its reading.
     *
     * Separate from [bodyFormat] because the previous station's name is the news here: without it
     * a handover at an identical temperature would read `KSJC 68°, was 68°`, which looks like
     * nothing happened.
     */
    val bodyStationChangedFormat: String = "%1\$s %2\$s, was %3\$s %4\$s",
)

sealed interface DominantTempWatchDecision {
    /** Not armed. No notification, no state write. */
    data object Idle : DominantTempWatchDecision

    /** Armed, but this evaluation cannot advance the watch. [reason] is for the logs. */
    data class Hold(val reason: String) : DominantTempWatchDecision

    /** Armed with nothing to compare against yet: persist [state] as the baseline and keep waiting. */
    data class Capture(val state: DominantTempWatchState) : DominantTempWatchDecision

    /** The reading changed: show the notification and persist [state] (disarmed). */
    data class Fire(
        val title: String,
        val body: String,
        val state: DominantTempWatchState,
    ) : DominantTempWatchDecision
}

object DominantTempWatch {

    /**
     * Decides what this evaluation should do.
     *
     * [dominant] is the dominant contribution behind the *current* displayed temperature, or null
     * when the blend produced none (no observations in the window, or the caller's `observedAt`
     * disagreed with the blend's — see `TodayColumnOverlayContentResolver`).
     */
    fun evaluate(
        state: DominantTempWatchState,
        dominant: BlendContribution?,
        useCelsius: Boolean,
        strings: DominantTempWatchStrings = DominantTempWatchStrings(),
    ): DominantTempWatchDecision {
        if (!state.armed) return DominantTempWatchDecision.Idle
        if (dominant == null) return DominantTempWatchDecision.Hold("no_dominant")
        // A synthetic row is the source's own hourly forecast re-filed as observations at
        // distanceKm=0, not a thermometer. Firing "WEATHER_API_MAIN 71.2°" could attribute provider
        // history to a station — the same misattribution DominantStationLabel.format refuses to print. Under
        // a forecast-only source it is the ONLY candidate, so the watch simply never fires there.
        if (dominant.isSynthetic) return DominantTempWatchDecision.Hold("synthetic")

        val stationId = dominant.stationId.trim().takeIf { it.isNotEmpty() }
            ?: return DominantTempWatchDecision.Hold("no_station_id")
        val newTempF = dominant.rawTemp
        if (!newTempF.isFinite()) return DominantTempWatchDecision.Hold("non_finite_temp")

        val baselineTempF = state.baselineTempF
            ?: return DominantTempWatchDecision.Capture(
                state.copy(baselineStationId = stationId, baselineTempF = newTempF),
            )

        // Two independent triggers, either of which is news:
        //
        //  - the reading changed, compared as DISPLAYED rather than as raw floats. A station
        //    re-reporting 69.87 → 69.92 renders identically at the app's precision, and
        //    "KNUQ 69.9°, was 69.9°" reads as a bug rather than as news.
        //  - a DIFFERENT station took over the blend. The displayed temperature can land on the same
        //    number through a completely different thermometer — a handover from an airport ASOS to a
        //    backyard PWS two streets away is a change in what the app is telling you even when the
        //    degrees agree.
        val tempChanged =
            formatTemp(newTempF, useCelsius) != formatTemp(baselineTempF, useCelsius)
        val stationChanged =
            state.baselineStationId != null && !state.baselineStationId.equals(stationId, ignoreCase = true)
        if (!tempChanged && !stationChanged) return DominantTempWatchDecision.Hold("unchanged")

        return DominantTempWatchDecision.Fire(
            title = strings.title,
            body = formatBody(
                stationId = stationId,
                newTempF = newTempF,
                oldTempF = baselineTempF,
                useCelsius = useCelsius,
                strings = strings,
                previousStationId = state.baselineStationId.takeIf { stationChanged },
            ),
            state = DominantTempWatchState.DISARMED,
        )
    }

    /**
     * `KNUQ 69.9°, was 68°`, or `KSJC 72°, was KNUQ 68°` when [previousStationId] is given. Both
     * temperatures come from °F storage values.
     *
     * The ids are upper-cased. [com.weatherwidget.shared.graph.DominantStationLabel] lowercases the
     * same callsign, and deliberately so — at 9sp on a graph an all-caps id shouts over the
     * temperatures around it. A notification has no such neighbours, and `KNUQ` is how the station
     * writes its own name.
     */
    fun formatBody(
        stationId: String,
        newTempF: Float,
        oldTempF: Float,
        useCelsius: Boolean,
        strings: DominantTempWatchStrings = DominantTempWatchStrings(),
        previousStationId: String? = null,
    ): String = if (previousStationId == null) {
        String.format(
            Locale.getDefault(),
            strings.bodyFormat,
            stationId.uppercase(Locale.ROOT),
            formatTemp(newTempF, useCelsius),
            formatTemp(oldTempF, useCelsius),
        )
    } else {
        String.format(
            Locale.getDefault(),
            strings.bodyStationChangedFormat,
            stationId.uppercase(Locale.ROOT),
            formatTemp(newTempF, useCelsius),
            previousStationId.uppercase(Locale.ROOT),
            formatTemp(oldTempF, useCelsius),
        )
    }

    private fun formatTemp(tempF: Float, useCelsius: Boolean): String =
        TempUtils.formatTemp(tempF, useCelsius) ?: "--°"
}
