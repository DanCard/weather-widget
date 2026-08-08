package com.weatherwidget.shared.actuals

/**
 * Which code path last wrote a `daily_history` row. Persisted as `daily_history.lastWriter`.
 *
 * Purely diagnostic, and it earns its keep: several writers update the same row for different
 * reasons, and on 2026-08-08 working out *which one* had dropped a newly-added column took three
 * wrong guesses across an hour. `app_logs` in principle records each write, but it is tiered-retained
 * and correlating a row to a log line is manual. This puts the answer on the row.
 *
 * Distinct from [DailyActualsSource], which records where the *data* came from. The two diverge
 * exactly when it matters: a row whose values came from the station pull but was last *touched* by
 * the blend recompute reads `actualsSource=NWS_STATION_PULL, lastWriter=BLEND_RECOMPUTE`.
 */
enum class DailyHistoryWriter(val storedValue: String) {
    /** `DailyActualsStore.recomputeDailyExtremesForDay` — the IDW blend over stored observations. */
    BLEND_RECOMPUTE("blend_recompute"),

    /** `NwsApiDailyActualsFetcher` via `persistNwsDailyActuals` — the live per-day station pull. */
    NWS_STATION_PULL("nws_station_pull"),

    /** `persistCachedStationActuals` — station extreme derived from retained observations. */
    CACHED_STATION_FALLBACK("cached_station_fallback"),

    /** `persistOpenMeteoPastDayActuals` — Open-Meteo's own ERA5 past-day values. */
    OPEN_METEO_PAST_DAYS("open_meteo_past_days"),

    /** `DailyHistorySnapshotter` — frozen forecast overlay, rain chance, noon cloud. */
    FORECAST_FREEZE("forecast_freeze"),
    ;

    companion object {
        fun fromStored(value: String?): DailyHistoryWriter? =
            entries.firstOrNull { it.storedValue == value }
    }
}
