package com.weatherwidget.util

import com.weatherwidget.shared.observations.NwsBlend
import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.model.WeatherSource

/**
 * Maps an observation-screen station to its public web history page, or null when no such page is
 * known. Shared by the Android ([WeatherObservationsActivity]) and desktop ([ObservationsWindow])
 * observation screens so both link identically.
 *
 * Only NWS stations have a public per-station page: the NWS Western Region "time series" tool, which
 * accepts both OFFICIAL METAR codes (e.g. KSFO) and PERSONAL/PWS codes (e.g. AW020) as its `site`
 * parameter. Every other source identifies stations only by lat/lon, so there is nothing to link.
 *
 * [providerId] is the provider that filed the station's readings (the row's `api`), never the
 * displayed source: with actuals redirected (Open-Meteo shown, NWS actuals) the two differ, and
 * keying on the displayed source left every NWS station unlinked.
 */
object StationHistoryUrl {
    fun forStation(providerId: String, stationId: String): String? {
        if (providerId != WeatherSource.NWS.id) return null
        // Exclude the synthetic IDW blend (not a real station) and blanks.
        if (stationId.isBlank() || stationId == NwsBlend.STATION_ID) {
            return null
        }
        return "https://www.weather.gov/wrh/timeseries?site=$stationId"
    }
}
