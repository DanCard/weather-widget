# Desktop genmon panel temperature diverged from the popup

**Date:** 2026-08-13

The genmon panel readout and the desktop popup header both resolve the current temperature via the
same `resolveCurrentTempInMemory` path, but they were fed different observation sets. The daemon
(which serves the panel) re-resolved from `forecastState.rawObservations` — the freshly-fetched
*network* list — while the popup (a separate UI process) re-reads observations from the DB via
`loadCached`. The two lists could differ slightly (e.g. a reading outside `loadCached`'s query
window), so the IDW blend diverged and the panel and popup showed different temperatures.

---

## What changed

`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt`:
- `refreshObservations()` now returns `rawObservations = cached?.rawObservations
  ?: result.rawObservations` instead of preferring the network `result.rawObservations`.
- `cached.rawObservations` is exactly what `loadCached()` hands the UI process, so the daemon's
  in-memory snapshot now agrees with the popup instead of carrying a subtly different observation
  set. Falls back to the network list only when `loadCached()` returned null (no cache yet).
- Added an explanatory comment pinning the contract (DB-derived, not network).

`desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopRefreshObservationsTest.kt` (new):
- Regression test: a mocked `fetchObservationsOnly()` returns an in-range reading plus a future
  reading outside `loadCached`'s query window; asserts `refreshObservations()` returns only the
  DB-derived in-range reading (the future one is dropped).

## Evidence

- `app_logs` / runtime log before the fix, same instant: daemon `obs=66.89758 → display=66.79`
  ("66.8°") vs UI `obs=66.83174 → display=66.72` ("66.7°").
- After the fix (rebuilt + restarted), both daemon and UI resolve `obs=66.83 → display=66.59/66.60`
  ("66.6°"), and the panel socket serves "66.6°" matching the popup.

## Verified

- `./gradlew :desktop:test` — full suite BUILD SUCCESSFUL, including the new
  `DesktopRefreshObservationsTest`.
- Runtime: rebuilt `:desktop:createDistributable`, restarted the daemon + UI, queried
  `genmon-weather-bin` (66.6°) and confirmed the daemon log resolves the same value as the UI.

## Notes

- The full-forecast path (`refresh()` → `loadCached() ?: result`) already returned the DB-derived
  snapshot; only the observation-refresh path had the drift.
- `currentCondition` / `currentObservedAt` still prefer the network result, but those only drive the
  tooltip "measured/interpolated" text and the condition icon, not the displayed temperature.
