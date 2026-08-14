# Hourly source-snapshot race ("Cloud data unavailable" on API toggle) — subsystem code review & fix

**Date:** 2026-08-14
**Device:** Samsung SM-F936U1 (`RFCT71FR9NT`), widget 345 (10×5 GRAPH, CLOUD_COVER)
**Symptom (user report):** "silur api: cloud cover: says 'cloud data unavailable'. that is a bug."
Then, seconds later, "Oops it just updated with correct graph."

## Diagnosis (evidence-first, already collected)

The logs captured the failure completely, in both `logcat` and the persistent `app_logs` DB. It is
**not** a Silurian data gap — it is the hourly-side variant of the source-snapshot race fixed for
*actuals* in `plans/260801-silurian-history-actuals-stale-source-race.md`.

### Timeline (Samsung, pid 29067)

| Time | Event |
|------|-------|
| 09:32:23.968 | Toggle `NWS → OPEN_METEO` (widget 345) |
| 09:32:24.096 | `SYNC_START reason=toggle_api_stale` — worker snapshots hourly scope **`NWS\|OPEN_METEO\|Generic`** |
| 09:32:25.198 | `SilurianApi cloudCoverSummary kind=hourly total=361 present=361 missing=0` — API has full cloud data |
| 09:32:28.047 | `HourlyForecastLoader.load … sources=NWS\|OPEN_METEO\|Generic` → `stitched=466` (no Silurian) |
| 09:32:28.122 | `SYNC_SUCCESS Hourly=466` |
| 09:32:28.x  | Worker's post-fetch re-read of display sources = `NWS, OPEN_METEO` (Silurian not yet toggled) → `missingAtPaint` empty → **no reload** |
| 09:32:29.416 | Toggle `OPEN_METEO → SILURIAN` (after the post-fetch re-read) |
| 09:32:29.447 | `ACTUALS_SOURCE_RACE uncovered=SILURIAN loaded=NWS,OPEN_METEO` — the *actuals* paint-time guard fires |
| 09:32:29.495 | `HOURLY_SOURCE_MISS displaySource=SILURIAN unified=466 present=OPEN_METEO:240,NWS:226` |
| 09:32:29.497 | `CloudCoverViewHandler hourlyCount=0 source=SILURIAN sourceRows=0` → paints **"Cloud data unavailable"** |
| 09:32:29.601 | `CLOUD_COVER_GAPS missing=5 total=5 ranges=10a–2p sourceMissingFromLoad=true` |
| 09:33:19.033 | Next load: `sources=NWS\|SILURIAN\|Generic` |
| 09:33:19.317 | `CloudCoverViewHandler hourlyCount=240 source=SILURIAN sourceRowsWithCloudCover=240` → correct graph |

### Persisted proof (app_logs)

```
2026-08-14 09:32:29 | HOURLY_SOURCE_MISS | WARN | widget=345 view=CLOUD_COVER origin=WORKER_FETCH
                       displaySource=SILURIAN unified=466 present=OPEN_METEO:240,NWS:226 site=37.4168,-122.089
2026-08-14 09:32:29 | CLOUD_COVER_GAPS  | DEBUG | widget=345 source=SILURIAN missing=5 total=5
                       ranges=10a–2p reason=- sourceMissingFromLoad=true
```

Historical `app_logs` show the same signature recurring for days (`displaySource=SILURIAN …
present=OPEN_METEO:240,NWS:226`, plus the TOMORROW_IO variant), and many of those were
`view=DAILY` — so the DAILY view consumes hourly rows under the same stale scope.

### Root cause

`HourlyForecastLoader` scopes its SQL to the sources displayed **when the worker starts**, then
`WidgetRenderer.updateWidgetWithData` filters that list down to the widget's **current** display
source at paint time. A source toggle landing in the gap empties the list for the new source, and
the hourly view handlers honestly paint "Cloud data unavailable" (or a blank curve).

The post-fetch re-read in `FullSyncPipeline` (the `missingAtPaint` block) has a **TOCTOU gap**: it
re-reads once, right after the fetch, but a toggle landing *after* that re-read and *before* the
paint still slips through. That is exactly what happened at 09:32:29.416.

## Code review scope

Review the whole "source scope → load → repaint" subsystem, then implement findings and verify the
race is no longer reproducible. The actuals side (`resolveEffectiveActuals` /
`DailyActualsCoverage`) is the reference pattern for the hourly fix.

### Files

| File | Role in the race |
|------|------------------|
| `widget/HourlyForecastLoader.kt` | SQL source scoping; `hourlySourceIds()`, `currentDisplaySourceIds()`, `sourcesMissingFromLoad()` |
| `widget/WidgetDataBundleLoader.kt` | `WidgetDataBundle.activeSourceIds`; bundle load |
| `widget/FullSyncPipeline.kt` | Worker pipeline; post-fetch `missingAtPaint` reload (TOCTOU) |
| `widget/WidgetPaintCoordinator.kt` | `updateAllWidgets`; paint-time actuals guard (`resolveEffectiveActuals`); **hourly guard missing** |
| `widget/WidgetRenderer.kt` | `updateWidgetWithData`; unify; `sourceFilteredHourly`; `HOURLY_SOURCE_MISS` |
| `widget/WeatherWidgetWorker.kt` | Dispatcher; `refreshWidgetsFromCache` call sites |
| `widget/handlers/GraphDataLoader.kt` | Non-racy per-widget DB read used by USER_INTERACTION path |
| `widget/handlers/GraphInteractionRenderer.kt` | Toggle-tap render path (reads DB fresh) |
| `widget/handlers/WidgetIntentActionHandler.kt` | `toggleApi` → forced refresh, what starts the next worker |
| `widget/handlers/CloudCoverViewHandler.kt` | Consumer; `sourceMissingFromLoad` flag; `CLOUD_COVER_GAPS` |
| `widget/handlers/TemperatureViewHandler.kt`, `PrecipViewHandler.kt` | Consumers; verify same flag propagation |
| `widget/DailyActualsCoverage.kt` + `plans/260801-…` | Actuals-side reference pattern (already fixed) |

### Review questions

1. Enumerate every snapshot→paint window for the hourly scope. Is `FullSyncPipeline`'s post-fetch
   re-read the *only* guard, and where precisely does it leave a gap?
2. Why did the hourly side not get the paint-time re-read the actuals side got in 260801?
   (`WidgetPaintCoordinator.updateAllWidgets` has `resolveEffectiveActuals` but no hourly analog.)
3. Does `sourceMissingFromLoad` propagate correctly into **all three** hourly view handlers
   (temperature / precipitation / cloud cover), and does each suppress the wasted
   `shouldRefreshMissingData` forced sync when true? (CloudCoverViewHandler guards it; verify the
   other two.)
4. The historical `HOURLY_SOURCE_MISS view=DAILY` rows: which daily consumers read hourly rows
   under the stale scope (noon cloud shading, others)? Does the DAILY path need the same guard?
5. `refreshWidgetsFromCache` (WORKER_CACHE) — same gap? It passes `bundle.activeSourceIds` for
   actuals but has no hourly guard.
6. USER_INTERACTION / GraphInteraction paths read the DB fresh (no race) — confirm there is no
   cross-path clobber (the worker's stale push overwriting a fresh user-interaction push, as seen
   at 09:32:29.608→.615 where the empty worker paint landed last).
7. Any other source-scoped loaders (snapshots, precip, actuals) with the same stale-scope pattern?

## Implementation plan (mirror the actuals fix)

1. **`WidgetPaintCoordinator.updateAllWidgets`** — add a paint-time hourly reconciliation symmetric
   to `resolveEffectiveActuals`:
   - New params `loadedHourlySourceIds: Collection<String>`, `lat: Double?`, `lon: Double?`.
   - New `resolveEffectiveHourly(...)`: re-read current display sources for all widgets, call
     `HourlyForecastLoader.sourcesMissingFromLoad(loadedHourlySourceIds, paintSourceIds)`; if any
     missing, reload once via `hourlyForecastLoader.load(lat, lon, hourlyForecastLoader.hourlySourceIds())`
     and log `HOURLY_SOURCE_RACE` (keep the original list if the reload returns empty).
   - Feed the resolved list into every `WidgetRenderer.updateWidgetWithData(...)` call.
2. **`FullSyncPipeline.run`** — pass `loadedHourlySourceIds` (the scope the final
   `renderHourlyForecasts` was actually loaded under) plus `location.first/location.second`.
3. **`WidgetPaintCoordinator.refreshWidgetsFromCache`** — pass `loadedHourlySourceIds = bundle.activeSourceIds`
   and the resolved location.
4. **Any review findings** from the questions above (e.g. DAILY-view hourly consumers, missing
   `sourceMissingFromLoad` guards in Temperature/Precip handlers).
5. **Tests** — `sourcesMissingFromLoad` is already covered by `HourlyForecastLoaderSourceScopeTest`;
   add coverage for the new paint-time decision if it is extracted as a pure helper, or a focused
   Robolectric test of `resolveEffectiveHourly` if it can be constructed cheaply. Otherwise verify
   via the log + manual toggle (matching how the actuals fix was verified).

## Verification / reproduction protocol

Before and after the fix, reproduce on the Samsung (`RFCT71FR9NT`) by hand (screenshots and
`uiautomator dump` fail on this Fold):

1. Open the cloud-cover hourly view, then step the API toggle through NWS → OPEN_METEO → SILURIAN
   quickly, timing one toggle to land mid-fetch (the toggle itself enqueues the next worker, so
   rapid stepping reliably opens the window).
2. Watch logcat + `app_logs` for:
   - **Before fix:** `HOURLY_SOURCE_MISS` + `CLOUD_COVER_GAPS missing=… sourceMissingFromLoad=true`
     and "Cloud data unavailable" on screen.
   - **After fix:** `HOURLY_SOURCE_RACE` present, `HOURLY_SOURCE_MISS`/`CLOUD_COVER_GAPS` absent
     (or only for genuine upstream gaps), correct graph painted.
3. Confirm the common path stays cheap: no `HOURLY_SOURCE_RACE` when no source is toggled during a
   run.
4. Run the relevant unit buckets (`:app:testShortDebugUnitTest`) and an emulator-only pass where
   applicable.

## Code review findings (2026-08-14)

Review answered all seven questions; one defect confirmed, no new defects found:

1. **Snapshot→paint windows:** the hourly scope is snapshotted in `FullSyncPipeline`
   (`hourlySourceIdsAtLoad`) and re-read only once, post-fetch (`missingAtPaint`). The gap between
   that re-read and `updateAllWidgets` is unguarded — the defect.
2. **Why hourly lacks the actuals guard:** `WidgetPaintCoordinator` has `resolveEffectiveActuals`
   (added in 260801) but no hourly counterpart. Pure omission.
3. **`sourceMissingFromLoad` propagation:** already correct in all three hourly handlers
   (Temperature/Precip/CloudCover) and each suppresses the wasted forced sync — no change needed.
4. **DAILY consumers:** `WidgetRenderer` passes the *unfiltered* `unifiedHourlyForecasts` to
   `DailyViewHandler`, but daily noon-cloud shading filters to the display source internally, so the
   stale scope also degrades the daily bar's cloud split. The paint-time hourly reload covers this
   too (it feeds the same list).
5. **`refreshWidgetsFromCache`:** same gap; now covered by the same guard.
6. **USER_INTERACTION path:** `GraphInteractionRenderer.render` reads the DB fresh via
   `GraphDataLoader` (no race) — it painted 133 correct Silurian rows at 09:32:29.564 while the
   worker's stale empty paint landed after it (.615) and clobbered it. The worker-side fix is the
   right place; the interaction path needs no change.
7. **Other stale-scope loaders:** none found beyond hourly + actuals; snapshots/precip/current-temp
   paths resolve location and sources at their own use sites.

## Implementation & verification status

Implemented the mirror of the actuals guard:

- `WidgetPaintCoordinator.updateAllWidgets` — new `loadedHourlySourceIds`/`hourlyLat`/`hourlyLon`
  params and `resolveEffectiveHourly(...)`: re-reads display sources at paint time, reloads hourly
  once on any uncovered source, logs `HOURLY_SOURCE_RACE`, and keeps the original list if the reload
  is empty.
- `FullSyncPipeline.run` — passes `renderHourlySourceIds` (the scope the final list was loaded
  under) plus the resolved location.
- `WidgetPaintCoordinator.refreshWidgetsFromCache` — passes `bundle.activeSourceIds` + location.

Verified so far:
- `:app:compileDebugKotlin` clean.
- `:app:testShortDebugUnitTest` for `HourlyForecastLoaderSourceScopeTest` and
  `DailyActualsCoverageTest` — all pass.
- `:app:assembleDebug` + `adb install -r` on `RFCT71FR9NT` — post-install `onUpdate` repainted
  345/349/352 with `WIDGET_RENDER_OK`, and no `HOURLY_SOURCE_MISS`/`HOURLY_SOURCE_RACE` on the
  common path (no toggle in flight).
- Outstanding: live confirmation that a mid-fetch toggle now logs `HOURLY_SOURCE_RACE` and paints
  the correct graph instead of `CLOUD_COVER_GAPS` + "Cloud data unavailable". Manual on this Fold.
