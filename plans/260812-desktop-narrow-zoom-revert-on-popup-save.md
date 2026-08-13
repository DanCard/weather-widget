# Desktop: narrow zoom span reverts after a Settings save

## Report

> Desktop: I changed narrow view hours to 4 hours in settings but it didn't take. When I went
> back it was still 6 hours. Add logging if that makes this issue easier to diagnose in the future.

## Evidence (from the autostart log `~/.local/state/weather-widget/autostart-20260812-115503.log`)

1. `18:21:15.876 I/SettingsWindow: SETTINGS_EDIT narrowZoomSpanHours: 6 -> 5`
2. `18:21:16.107 I/SettingsWindow: SETTINGS_EDIT narrowZoomSpanHours: 5 -> 4`
3. `18:21:17.635 I/SettingsWindow: SETTINGS_SAVE narrowZoomSpanHours: 6 -> 4`
4. `18:21:17.636 I/Main: CONFIG_SAVE source=settings settings-fields-changed: narrowZoomSpanHours: 6 -> 4`
5. `18:21:23.109 W/Main: CONFIG_SAVE source=popup settings-fields-changed: narrowZoomSpanHours: 4 -> 6`

The user's Save **did** persist 4. ~5.5s later the popup (source=popup) saved a **stale**
`DesktopConfig` (its own `config` parameter, which still carried `narrowZoomSpanHours=6` from
before the Settings save) and wrote the whole object back, clobbering the change. The second user
attempt at `18:21:58.342` stuck only because the popup was not interacted with during the 5s window.

## Root cause

`WidgetPopup` computes every update with `config.copy(...)` from its own `config` parameter.
That parameter can lag the persisted config: Settings and the popup are separate `Window`
compositions, and a Settings save updates `config` in `Main` while the popup's captured snapshot
still holds the pre-save values. `Main.onUpdateConfig` then calls `saveConfigAndNotify(newConfig,
"popup")` with no merge, so the stale snapshot re-persists.

The same class of bug affects the observations/history windows (they also write their whole
`config` snapshot) and the location picker (its `toConfig()` builds a config with **default**
settings fields, which would reset user settings on a location change).

## Fix

1. Make `saveConfigAndNotify` the defensive choke point: for every non-settings source, merge the
   incoming config onto the latest persisted config so settings-owned fields can only be changed
   by the Settings window. `weatherSource` is the one exception — the popup header toggles it and
   the location picker sets a sensible per-region default.
2. Extract the merge as a pure, testable function `mergeNonSettingsSave(persisted, draft,
   allowWeatherSourceChange)`.
3. Persist the `CONFIG_SAVE` settings-change breadcrumb to the queryable DB log (`app_logs`) via
   `weatherDao.log`, plus a `merged-away-stale-settings` breadcrumb when the merge corrected a
   stale write. Previously these only went to the console/autostart file.

## Tests

- Unit tests for `mergeNonSettingsSave`:
  - a stale popup save (narrowZoomSpanHours 6 vs persisted 4) is corrected to 4 while popup fields
    (zoomFactor/hourlyOffset) pass through;
  - `weatherSource` passes through when `allowWeatherSourceChange=true` and is preserved when false;
  - settings-window saves (not merged) are unchanged (the function is not used for those sources).
- Run `./gradlew :desktop:test` (or the Short bucket) and `./gradlew :desktop:createDistributable`.

## Verify

- Build passes, tests pass.
- Re-run the scenario if possible; otherwise rely on the new DB breadcrumbs next time.
