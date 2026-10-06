# Daily view drew another source's forecast under the selected source's label

## Symptom (Pixel 7 Pro, 2026-10-06 11:24:46)
Right after the debug install switched every widget to Google Weather (no Google rows yet), the
daily graph labelled "Google" showed: Mon blank, Today an empty bar, Wed–Sat **87/63, 85/60, 80/61,
73/59** — exactly NWS's grid values (`NWS_GRID_TEMP_PRIMARY` in the same log). It corrected itself at
11:26:31 once the forced fetch landed (Wed–Sat then 82/59, 81/59, 73/60, 68/52).

## Root cause
`DailyViewLogic.prepareGraphDays` (`app/.../widget/handlers/DailyViewLogic.kt:425-426`), for every
non-today column:

```kotlin
weatherByDate[date]
    ?: forecastSnapshots[date]?.firstOrNull { allowGapFallback || it.source != WeatherSource.GENERIC_GAP.id }
```

`weatherByDate` is display-source-only, but the snapshot fallback filters only *climate-normal* rows
— any other real source's snapshot passes. So whenever the displayed source lacks a row for a date,
the column draws a different provider's forecast as if it were the selected one. Today's column goes
through `DailyTodayResolver.resolveTodayRow`, which is source-scoped — hence Today blank, Wed–Sat NWS.

Pre-existing (not introduced by Google); Google made it visible because enabling it creates exactly
the "selected source has no rows" state. It also contradicts the standing rule *no cross-source
fallback* (memory `no_cross_source_fallback`). Desktop has no equivalent fallback.

Reproduced in Robolectric: Google displayed, NWS rows in `weatherList` + `forecastSnapshots` →
Wed–Sat solid lines 87/63…73/59; with empty snapshots → null (blank).

## Fix
Scope the fallback to the display source (plus climate normals where already allowed):

```kotlin
?: forecastSnapshots[date]?.firstOrNull {
    it.source == displaySource.id || (allowGapFallback && it.source == WeatherSource.GENERIC_GAP.id)
}
```

A column with no row for the selected source renders missing (as Today already does) — honest, and
it fills in when the fetch lands.

## Tests
- Regression (Robolectric, `DailyViewHandler.updateWidget` with the real `DailyViewLogic`): Google
  displayed, only NWS rows in weather + snapshots → no future column carries NWS values; with Google
  snapshots present for a date the fallback still uses them; a >today+2 date still takes GENERIC_GAP.
- Full `:app` unit suite + emulator suite.

## Not in this fix (noted)
The switch-to-data gap was ~80 s (20 s startup deferral, a UI-only sync, then the forced fetch). With
the fix the interim paint is blank instead of wrong. Shortening it (an expedited targeted fetch when a
source becomes primary, as the API-toggle path does) is a separate change.

## Outcome (2026-10-06)
- The rule lives in `:shared` as `DailyColumnSource` (`mayDraw`, `allowsClimateNormal`), used by
  `DailyViewLogic` (the fixed fallback) and `DailyViewHandler` (the `weatherByDate` gap check), which
  had each hand-written the today+2 cutoff.
- Tests: `DailyColumnSourceTest` (:shared) and `DailyViewCrossSourceSnapshotIntegrationTest` (:app,
  real DailyViewHandler → DailyViewLogic). Both fail on the old line (87 drawn under "Google").
- **Desktop now uses the same rule** (user's call): `DesktopWeatherRepository.appendClimateNormalGaps`
  filters `ClimateNormals.fillGaps` through `DailyColumnSource.allowsClimateNormal`, so desktop no
  longer shows climate averages for an uncovered today/+1/+2 (it used to, e.g. right after enabling a
  source). `fillGaps` itself is unchanged: Android's gap filler and the shared
  `CurrentTemperatureResolver` still read near-term gap rows. Test:
  `DesktopWeatherRepositoryTest` "loadCached never fills today through plus two with climate normals".
