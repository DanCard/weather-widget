# Settings source list stale after a location change retires NWS

**Date:** 2026-09-13 · **Follows:** `plans/260912-*` / commit 61086b37 (Retire NWS outside its coverage)

## Report

On emulator-5554, setting the location to Lviv from Settings → "Set Location…" left NWS ticked in
the Weather Data Sources list. Expected: NWS retired (Lviv is outside api.weather.gov coverage).

## Evidence

- `SOURCE_ORDER: Setup order changed: [NWS, OPEN_METEO, …] -> [OPEN_METEO, …]` at 07:27:17.952,
  the moment ConfigActivity saved Lviv.
- `widget_state_prefs.xml` afterwards: `visible_sources_order` without NWS, `nws_auto_retired=true`.
- So the retirement worked; the **Settings screen** was showing a stale list.

## Root cause

**Android** — `SettingsActivity.rebuildSourceRows()` ran only in `onCreate`. `onResume()` refreshed
the location label but not the rows, so the list built before "Set Location…" survived the round
trip. Display-only: the toggle/up/down handlers re-read the store, so the stale row could not write
NWS back.

**Desktop** — same class of bug, worse outcome. Settings stays open while the picker runs; the
picker save arrives as a new `config` baseline and `SettingsWindow`'s rebase did
`config.withSettingsFrom(currentConfig)` — keeping the draft's *whole* settings, including stale
`visibleSources` (with NWS) and `nwsAutoRetired=false`. That made the window dirty, and 5 s later the
auto-save wrote NWS back under source `"settings"`, which `resolveConfigSave` treats as a user edit
and clears `nwsAutoRetired`. The retirement was silently undone and could never be restored.

## Fix

- Android: `onResume()` also calls `rebuildSourceRows(...)`.
- Desktop: `DesktopConfig.rebaseSettingsDraft(previous, draft)` — a three-way merge for exactly the
  settings fields non-settings writers may change (`weatherSource`, `visibleSources`,
  `nwsAutoRetired`, `actualsProviders`, the same set `mergeNonSettingsSave` admits). Where the
  draft equals the previous baseline (no edit in flight) the new baseline wins; a pending edit is
  kept. `SettingsWindow` remembers the previous baseline and uses it in the `LaunchedEffect(config)`
  rebase. This also fixes the popup header's source toggle being rewound by an open Settings window.

## Tests

- `SettingsActivityRobolectricTest.source rows follow a retirement written while the screen was paused`
  — pause via `ActivityScenario.moveToState(STARTED)`, call `retireNwsOutsideCoverage(49.842, 24.032)`,
  resume, assert NWS unticked. Fails without the fix.
- `SettingsDraftRebaseTest` — picker retirement adopted by an untouched draft; in-flight edit still
  wins; popup source toggle adopted.
- `SettingsWindowBaselineChangeTest.aLocationPickerSaveThatRetiresNws_isAdoptedAndNotAutoSavedBack`
  — Compose window, frozen clock: push a retired baseline, assert checkbox off, window clean, and no
  auto-save after 5× the delay. Fails without the fix.

## Verification

emulator-5554: ticked NWS on, Set Location… → USE COORDINATES 49.842/24.032, returned to the same
Settings instance: NWS unticked, `nws_auto_retired=true`. Desktop suite 392/0/0.
