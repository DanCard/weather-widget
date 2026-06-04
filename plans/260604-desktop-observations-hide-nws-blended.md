# Desktop Observations: hide synthetic "NWS Blended" station

## Context

The desktop app's **Weather Observations & Logs** window shows a station card titled
**"NWS Blended"** (`stationId=NWS_BLEND`, `stationType=BLENDED`). This is a synthetic,
internal aggregate — not a real station — and should never be shown to the user. The Android
widget's equivalent screen already hides it; the desktop port was supposed to behave the same
but omitted the filter.

### Root cause (verified)
- `ObservationsWindow.kt` builds its list with only `.filter { it.api == currentSource.id }`
  (`desktop/.../ObservationsWindow.kt:82`). The synthetic row carries `api = "NWS"`, so it
  passes the filter and renders.
- Android's screen has an explicit guard that the desktop never copied:
  `WeatherObservationsActivity.WeatherObservationsSupport.matchesObservationSource()`
  (`app/.../ui/WeatherObservationsActivity.kt:347`) →
  `WeatherSource.NWS -> stationId != "NWS_BLEND" && …`.
- The visible row is **stale data**: live DB `~/.local/share/weather-widget/weather.db` shows
  `NWS_BLEND` last written `2026-06-03 21:07`, while today's `09:15` refresh inserted only real
  stations (KHWD/KNUQ/KPAO/KRHV/KSJC). `git log -S 'NWS_BLEND' -- desktop/ shared/` finds
  **nothing** — current desktop/shared code does not create blended rows (that synthesis only
  exists in `:app`'s `ObservationRepository.kt`). The row is an orphan from an earlier binary,
  still inside the 24h display window (`getRecentObservations`, `DesktopWeatherDao.kt:473`).

### Intended outcome
The observations list shows only genuine stations, matching the Android widget — regardless of
any orphaned synthetic rows already in the DB or any that a future code path might insert.

## Change

**File:** `desktop/src/main/kotlin/com/weatherwidget/desktop/ObservationsWindow.kt`

In `loadData` (~line 81-85), add a synthetic-row exclusion to the existing filter chain,
mirroring Android's `matchesObservationSource`:

```kotlin
val obs = weatherDao.getRecentObservations(sinceMs)
    .filter { it.api == currentSource.id }
    .filter { it.stationId != NWS_BLEND_STATION_ID && it.stationType != "BLENDED" }
    .groupBy { it.stationId }
    .map { it.value.first() }
    .sortedBy { it.distanceKm }
```

- `stationId != NWS_BLEND_STATION_ID` mirrors Android exactly; `stationType != "BLENDED"` is a
  defensive catch-all for any blended synthetic regardless of source/id.
- Define the constant once rather than a magic string. Mirror Android's
  `ActualPrecipSource.NWS_BLEND_STATION_ID = "NWS_BLEND"`: add
  `const val NWS_BLEND_STATION_ID = "NWS_BLEND"` to the `companion object` of
  `DesktopObservationEntity` in `shared/.../data/local/desktop/DesktopEntities.kt` (shared home,
  reusable by both desktop UI and any future filtering), and import it in `ObservationsWindow.kt`.

No DB mutation. Per CLAUDE.md ("never clear app data without consent"), the stale `NWS_BLEND`
row is left in place — the new filter hides it immediately, and it ages out of the 24h window on
its own. (Optional, only if the user asks: `DELETE FROM observations WHERE stationId='NWS_BLEND';`.)

## Out of scope (noted, not fixed)
- Two other oddities in the live DB: `stationId="stations"` (looks like an NWS station-list
  parsing bug, name "AE6EO MOUNTAIN VIEW") and `AW020` (a real CWOP/personal weather station).
  Android shows real personal stations too, so only `"stations"` is suspect. Mention to user;
  separate investigation if desired.

## Verification
1. Build/run the desktop app dev path: `./gradlew :desktop:run` (stop the daily distributable
   first — single-instance lock; see CLAUDE.md and `desktop_test_running_app_conflict` memory).
2. Open the observations window, cycle source to **NWS**. Confirm the **"NWS Blended"** card is
   gone and only real stations (KHWD, KNUQ, KPAO, KRHV, KSJC, AW020) remain.
3. Optional sanity on data: re-query the DB to confirm no *new* `NWS_BLEND` rows appear after a
   refresh (they won't — current code doesn't create them):
   `sqlite3 ~/.local/share/weather-widget/weather.db "SELECT DISTINCT stationId,stationType FROM observations;"`
4. Add/adjust a unit test if a desktop UI test seam exists (`desktop/.../DesktopUiTest.kt`):
   feed a list including an `NWS_BLEND`/`BLENDED` entity through the same filter predicate and
   assert it is excluded. If the filter is inline-only, consider extracting the predicate to a
   small testable function to mirror Android's `matchesObservationSource` testability.
