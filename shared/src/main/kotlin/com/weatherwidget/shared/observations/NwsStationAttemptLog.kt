package com.weatherwidget.shared.observations

/**
 * The per-station log lines of an NWS observation attempt, one format on both platforms (Android's
 * `NwsCurrentObservationUpdater`, desktop's observation bundles). Each platform writes the line to
 * its own app_logs; the verdict that picks the line is still each platform's flow (Android fetches
 * the latest reading here, desktop the latest plus the history window) — audit
 * plans/261023-duplicate-logic-audit-different-names.md §2.
 */
object NwsStationAttemptLog {
    data class Line(val tag: String, val message: String, val level: String)

    /** The API observation stored beside a web-won latest: the nearest station's real sky. */
    fun cloudCarrier(stationId: String, timestampMs: Long, cloudLow: Int?) = Line(
        "OBS_CLOUD_CARRIER",
        "station=$stationId timestamp=$timestampMs cloudLow=$cloudLow reason=preserve_independent_api_observation",
        "INFO",
    )

    /** The attempt finished but the station had nothing storable: its newest row's fetchedAt was touched. */
    fun touched(stationId: String) = Line("OBS_ATTEMPT_TOUCH", "station=$stationId reason=no_valid_observation", "INFO")

    /** Every upstream failed outright: fetchedAt stays frozen (a dead network is not a silent station). */
    fun failed(stationId: String, nwsReason: String?, webReason: String?) = Line(
        "NWS_STATION_FAIL",
        "station=$stationId nws=${nwsReason ?: "unknown"} web=${webReason ?: "not_tried"}",
        "WARN",
    )

    /** The station's request threw. */
    fun threw(stationId: String, e: Exception) =
        Line("NWS_STATION_FAIL", "station=$stationId error=${e::class.simpleName}:${e.message}", "WARN")
}
