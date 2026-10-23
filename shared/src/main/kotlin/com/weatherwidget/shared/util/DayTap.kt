package com.weatherwidget.shared.util

/**
 * What a daily-view day tap does once its view is chosen ([DayClickResolver]) and its source's
 * coverage judged ([com.weatherwidget.data.remote.HourlyOnDemand.hoursToCover], [NoHourlyChecker]),
 * and its two log lines — both platforms. The day's hourly view opens in every case; the outcome only
 * says what is shown over it. Was Android's `WidgetDayClickCoordinator.navigateToHourlyView` and the
 * desktop popup's `handleDayClick`: desktop never wrote [NO_HOURLY_TAG], and the two [TAG] lines
 * carried different fields (audit plans/261023-duplicate-logic-audit-different-names.md §3).
 */
object DayTap {
    const val TAG = "CLICK_DAILY"
    const val NO_HOURLY_TAG = "CLICK_DAILY_NO_HOURLY"

    sealed interface Outcome {
        /** Fetch the source on demand [hours] deep under the "Fetching…" banner. */
        data class Fetch(val hours: Int) : Outcome

        /** No fetch can help and the day has no hourly: say where the data ends. */
        data object NoData : Outcome

        /** The day is covered: open it, clearing any old banner. */
        data object Open : Outcome
    }

    /** [onDemandHours]: `hoursToCover` for the day (null: nothing to fetch). [hasHourly]: the view has hours for it. */
    fun outcome(onDemandHours: Int?, hasHourly: Boolean): Outcome = when {
        onDemandHours != null -> Outcome.Fetch(onDemandHours)
        !hasHourly -> Outcome.NoData
        else -> Outcome.Open
    }

    /** The [TAG] line; [extra] carries the platform's own fields (Android: index, history flags; desktop: zone, icon). */
    fun line(date: String, targetView: String, offset: Int, precipGate: String, clickSource: String, extra: String = ""): String =
        "date=$date targetView=$targetView offset=$offset precipGate=$precipGate clickSource=$clickSource" +
            if (extra.isNotEmpty()) " $extra" else ""

    /** The [NO_HOURLY_TAG] line with the banner text shown, or null for [Outcome.Open]. */
    fun noHourlyLine(outcome: Outcome, date: String, targetView: String, hasHourly: Boolean, message: String?): String? = when (outcome) {
        is Outcome.Fetch -> "phase=pending date=$date mode=$targetView hasHourly=$hasHourly onDemandHours=${outcome.hours} -> \"$message\""
        Outcome.NoData -> "phase=no_data date=$date mode=$targetView -> \"$message\""
        Outcome.Open -> null
    }
}
