# Plan: Open station web history from the Current Observations screen

## Context

On the "Current Observations" screen (desktop `ObservationsWindow`, Android
`WeatherObservationsActivity`), each row represents a weather station. The user wants to click a
station and be taken to that station's **web observation history** when a link is known.

A "known link" exists for **all NWS stations** (both OFFICIAL METAR codes like `KSFO` and PERSONAL
PWS codes like `AW020`) via the NWS Western Region time-series tool:
`https://www.weather.gov/wrh/timeseries?site=<stationId>`. The stored `stationId` is exactly the
`site=` value. Every other source (Open-Meteo, Silurian, WeatherAPI, etc.) identifies "stations"
only by lat/lon and has **no known link**, so those rows do nothing when clicked (per user choice).

Both platforms should get the behavior. The synthetic `NWS_BLEND` row is already filtered out of
this screen, so it needs no special handling, but the URL helper guards against it anyway.

## Approach

### 1. Shared URL helper (single source of truth)
New file: `shared/src/main/kotlin/com/weatherwidget/util/StationHistoryUrl.kt`

```kotlin
package com.weatherwidget.util

import com.weatherwidget.data.local.desktop.DesktopObservationEntity.Companion.NWS_BLEND_STATION_ID
import com.weatherwidget.data.model.WeatherSource

/** Web observation-history URL for a station, or null when no public page is known. */
object StationHistoryUrl {
    fun forStation(sourceId: String, stationId: String): String? {
        if (sourceId != WeatherSource.NWS.id) return null
        // Only real NWS stations — exclude the synthetic IDW blend and blanks.
        if (stationId.isBlank() || stationId == NWS_BLEND_STATION_ID) return null
        // WRH time-series tool accepts both OFFICIAL METAR and PERSONAL (PWS) site codes.
        return "https://www.weather.gov/wrh/timeseries?site=$stationId"
    }
}
```

- Uniform link for every NWS station (official + personal); no type classification needed.
- Reuses `WeatherSource.NWS.id` (`shared/.../data/model/WeatherSource.kt`) and the existing
  `NWS_BLEND_STATION_ID` constant (`shared/.../data/local/desktop/DesktopEntities.kt:27`). If
  importing that const reads awkwardly from `:app`, inline the literal `"NWS_BLEND"` instead — it's
  already the value used by both screens' filters.
- Both `:app` and `:desktop` depend on `:shared`, so both call this one function.

### 2. Desktop wiring
- New file `desktop/src/main/kotlin/com/weatherwidget/desktop/UrlOpener.kt` with
  `fun openInBrowser(url: String)`: try `java.awt.Desktop.browse(URI(url))` when supported, fall
  back to `ProcessBuilder("xdg-open", url).start()` (matches existing `ProcessBuilder` usage in
  `PhoneLocator.kt`/`DesktopProcess.kt`). Run on a short-lived thread so the Compose UI thread never
  blocks. The window runs in the **UI process** (the daemon is headless), so browsing is safe here.
- In `ObservationsWindow.kt` `ObservationList()` (the `items(observations)` `Card`, ~line 315):
  compute `val historyUrl = StationHistoryUrl.forStation(obs.api, obs.stationId)` and add
  `Modifier.clickable(enabled = historyUrl != null) { historyUrl?.let(::openInBrowser) }` to the
  Card modifier. New imports: `androidx.compose.foundation.clickable`,
  `com.weatherwidget.util.StationHistoryUrl`. No-link rows stay inert (do nothing).

### 3. Android wiring
In `WeatherObservationsActivity.kt`, extend the existing `ObservationAdapter` click lambda
(currently lines 87–91, which only handles `_HIST_` rename). `_HIST_` (personal) and NWS-official
are mutually exclusive, so add an else branch:

```kotlin
adapter = ObservationAdapter { entity ->
    if (entity.stationId.contains("_HIST_")) {
        showRenameDialog(entity)
    } else {
        StationHistoryUrl.forStation(currentSource.id, entity.stationId)?.let { openStationHistory(it) }
    }
}
```

Add helper:
```kotlin
private fun openStationHistory(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "No browser to open $url", e)
    }
}
```
New import: `com.weatherwidget.util.StationHistoryUrl` (`Intent` already imported). Rows with no
known URL fall through and do nothing.

### 4. Test
New file `shared/src/test/kotlin/com/weatherwidget/util/StationHistoryUrlTest.kt` (plain JUnit, no
mocking — consistent with repo testing strategy):
- `NWS` + `KSFO` (official) → `https://www.weather.gov/wrh/timeseries?site=KSFO`
- `NWS` + `AW020` (personal) → `https://www.weather.gov/wrh/timeseries?site=AW020`
- `NWS` + `NWS_BLEND` → `null`
- `NWS` + blank → `null`
- non-NWS source id (e.g. `OPEN_METEO`) + any id → `null`

## Critical files
- `shared/src/main/kotlin/com/weatherwidget/util/StationHistoryUrl.kt` (new)
- `shared/src/test/kotlin/com/weatherwidget/util/StationHistoryUrlTest.kt` (new)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/UrlOpener.kt` (new)
- `desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt` (clickable card)
- `app/src/main/java/com/weatherwidget/ui/WeatherObservationsActivity.kt` (click lambda + helper)

## Verification
- Unit test: `./gradlew :shared:test --tests "com.weatherwidget.util.StationHistoryUrlTest"`
- Desktop end-to-end: `scripts/buildStart.sh` (rebuilds distributable + restarts), open the
  Observations window from the tray, set source to NWS, click any station row (official or personal)
  → browser opens the `wrh/timeseries?site=<ID>` page; switch to Open-Meteo and click a row →
  nothing happens.
- Android: `./gradlew installDebug`, open the observations screen from the widget, NWS source, tap
  any station → browser opens the timeseries page; tap a non-NWS `_HIST_` row → rename dialog
  (unchanged); other non-NWS source tap → nothing.
