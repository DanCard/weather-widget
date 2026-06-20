# Plan: Open station web history from the Current Observations screen

## Context

On the "Current Observations" screen (desktop `ObservationsWindow`, Android
`WeatherObservationsActivity`), each row represents a weather station. The user wants to click a
station and be taken to that station's **web observation history** when a link is known.

A "known link" only exists for **NWS OFFICIAL (METAR) stations** — these have real station IDs
(4-char `K/P/T` codes like `KSFO`) and a public NWS history page at
`https://forecast.weather.gov/data/obhistory/<ID>.html`. Every other source (Open-Meteo, Silurian,
WeatherAPI, etc.) identifies "stations" only by lat/lon, and NWS PERSONAL (PWS) stations have no
public page — so those rows have **no known link and clicking does nothing** (per user choice).

Both platforms should get the behavior. The synthetic `NWS_BLEND` row is already filtered out of
this screen, so it needs no special handling, but the URL helper guards against it anyway.

## Approach

### 1. Shared URL helper (single source of truth)
New file: `shared/src/main/kotlin/com/weatherwidget/util/StationHistoryUrl.kt`

```kotlin
package com.weatherwidget.util

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi

/** Web observation-history URL for a station, or null when no public page is known. */
object StationHistoryUrl {
    fun forStation(sourceId: String, stationId: String): String? {
        if (sourceId != WeatherSource.NWS.id) return null
        // Only OFFICIAL METAR stations have a public NWS history page (excludes PWS + NWS_BLEND).
        if (NwsApi.classifyStationType(stationId) != NwsApi.StationType.OFFICIAL) return null
        return "https://forecast.weather.gov/data/obhistory/$stationId.html"
    }
}
```

- Reuses existing `NwsApi.classifyStationType(id)` / `NwsApi.StationType.OFFICIAL`
  (`shared/.../data/remote/NwsApi.kt:28,139`) and `WeatherSource.NWS.id`
  (`shared/.../data/model/WeatherSource.kt`). No new classification logic.
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
- `NWS` + `KSFO` → `https://forecast.weather.gov/data/obhistory/KSFO.html`
- `NWS` + a PWS id (e.g. `"OPEN_METEO_..."`-style or `"AT166"`) → `null`
- `NWS` + `NWS_BLEND` → `null`
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
  Observations window from the tray, set source to NWS, click an OFFICIAL station row → browser
  opens the obhistory page; click a PERSONAL row or switch to Open-Meteo and click → nothing
  happens.
- Android: `./gradlew installDebug`, open the observations screen from the widget, NWS source, tap
  an OFFICIAL station → browser opens; tap a personal/`_HIST_` row → rename dialog (unchanged);
  non-NWS source tap → nothing.
