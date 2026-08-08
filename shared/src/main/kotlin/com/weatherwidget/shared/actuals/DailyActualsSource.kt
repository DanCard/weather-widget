package com.weatherwidget.shared.actuals

/**
 * Where a `daily_history` row's NWS actuals came from. Persisted as
 * `daily_history.actualsSource`.
 *
 * Also the freeze marker: before this existed, `persistExtremes` keyed its "don't rebuild a past
 * day's blend from the stored pool" guard on `apiStationId != null`, which conflated provenance
 * with station identity and would have misfired the moment a second writer set that column.
 */
enum class DailyActualsSource(val storedValue: String) {
    /** A live per-day `api.weather.gov/stations/{id}/observations` request. */
    NWS_STATION_PULL("nws_station_pull"),

    /**
     * Derived from our retained `observations` rows, because the endpoint could no longer serve a
     * complete day. Its retention is a rolling window from now, so the oldest in-range day arrives
     * sliced off at the current wall-clock hour — measured 2026-08-08 09:00, every station's
     * 2026-08-01 series began at hour 09 while our stored copy still spanned 00:15–23:55.
     */
    CACHED_OBSERVATIONS("cached_observations"),
    ;

    companion object {
        fun fromStored(value: String?): DailyActualsSource? =
            entries.firstOrNull { it.storedValue == value }
    }
}
