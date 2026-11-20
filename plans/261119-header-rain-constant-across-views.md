# Header rain % stays the daily view's value in every view

User, 2026-11-19: "the header row rain chance changes based on what is being viewed … simpler if it
stayed constant to what is shown in daily forecast view."

## Evidence (code)

- `:shared` `HeaderContent.precipitation` (`HeaderContent.kt` ~L219) scans
  `referenceTime = if (view == View.DAILY) now else centerTime`, so the three hourly views
  (temperature, precip, cloud) scan the next N h from the **panned** hour.
- Android callers: `DailyHeaderResolver` passes `centerTime = now`. `PrecipViewHandler`,
  `CloudCoverViewHandler` and `TemperatureHeaderStateBuilder` pass the panned `centerTime`. Desktop
  `desktopHeaderContent` passes `targetHour`.
- Fallback differs too. Android hourly views pass `fallbackDailyPrecip = targetPrecip`, which is the
  **panned day's** daily row (`WidgetRenderer.kt` ~L495). Daily (both platforms) and desktop hourly
  pass today's row.
- This came from A3 in `plans/261021-header-shared-content-audit.md`. The default "follow the pan as
  Android" was Claude's proposal, approved with "proceed". It is not a stated user design.
- `arch/ARCHITECTURE.md` ~L233 documents the current split ("from `now` in the daily view and from
  the panned center hour in hourly views").

## Root cause

Two inputs depend on the view: the reference time (pan) and the fallback day. Either one is enough
to make the % change on a view switch or a pan.

## Proposed fix

1. `:shared` `HeaderContent.precipitation`: always use `now` and drop the `view`/`centerTime`
   inputs. The same applies to `precipLabels`, which the width reserve uses, so the reserved width
   matches what is drawn. `precipReserveWindowMs` drops `centerTime` (desktop already passes `now`).
2. Fallback: every view passes **today's** daily precip. On Android hourly, add a `todayPrecip`
   next to `targetPrecip` in `WidgetRenderer`/`WidgetViewModeDispatcher` for the header only. Keep
   `targetPrecip` for any non-header use (to be checked during implementation).
3. Android hourly row coverage: `GraphDataLoader.buildGraphQueryWindow` adds only the current
   **hour** when the pan is outside centre −72/+60 h. Widen that extra window to
   `now … now + HEADER_RAIN_LOOKAHEAD.max + 1 h`. Without this, a view panned ≥ 3 days out would
   see only one hour of "now" and show a different %. Desktop already holds the full forecast in
   `forecast.raw.hourly`.
4. Unchanged: rain-now dots, the 0 % hide rule, font scale, and tap routing.

## Tests

- `HeaderContentTest`: one discriminating case where rain is at the panned hour but not near `now`.
  All four views must return the daily value. It fails on the old code.
- Integration (Android, Robolectric + real Room DAO): seed hourly rows, pan the precip view 4 days
  ahead through `GraphDataLoader` and the handler's `HeaderContent.build` path, and assert the
  header % equals the daily header's. This also covers step 3.
- Desktop: `desktopHeaderContent` with a panned `centerTime` equals the daily-view value.

## Docs

- `arch/ARCHITECTURE.md` ~L233: "scans forward from `now` in every view".

## Verification

`./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`. On the emulator, check
that the daily → precip → pan +4 days header % is unchanged, using a screenshot and the
`HEADER_CONTENT` log. Do the same on desktop.

## Revision (user, 2026-11-19: "use the shared data and proceed")

Step 3 now uses the current temp's now-centred rows instead of widening the graph window.
- `HeaderContent.nowRowsWindowMs` is the current-temp resolution window stretched to the rain
  reserve at `HEADER_RAIN_LOOKAHEAD.max`, about 12 h back and 9 h ahead. The old window was +3 h.
- `GraphDataLoader.nowCentred` cuts to that window, and `loadCurrentTempInputHourlyForecasts` reads
  to its end. `currentTempInput` cuts the rows back to `CurrentTempRun.inputWindow` for `observe`, so
  the yesterday delta's inputs do not change.
- `HeaderContent.build(precipHourly = …)` is set by all four Android headers from those rows.
  `WidgetRenderer` passes `todayPrecip`, where `targetPrecip` was the panned day's.
- `CurrentTemperatureResolver.resolveQuick` now cuts to its window, as `resolve` already did, so the
  wider rows cannot move the temperature.
- Test: `HeaderRainConstantAcrossViewsRoboTest` covers real Room → loaders → daily and temperature
  headers, panned 4 days out, with the rain at +7 h only.
