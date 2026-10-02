# Session summary — Desktop Silurian daily forecast today-column high (Synoptic actuals)

**Date:** 2026-10-01 (local) / 2026-10-02 UTC  
**Scope:** Desktop daily forecast, Today column thermostat bar; shared actuals eligibility + today-line resolution; one instrumented-test flake.  
**Outcome:** Today’s high for Silurian comes from the configured Synoptic actuals feed; the middle temperature column rises to that high. Shared code now owns the load gate and bar-top formula on Android and desktop.

---

## User prompts

1. *“desktop: silurian api looks wrong. Daily forecast view. Today column. Temperature middle column should rise higher to show today's high temperature. Corrrect on emulator.”*
2. *“Actual temperatures is set to be retrieved from synoptic api. Should be getting actual high from synoptic api when silurian api is set.”*
3. *(Plan redirect)* *“Can we have android and desktop use shared code?”*
4. *(Approval)* *“Yes, implement (Recommended)”* — shared-code fix for Silurian today-column high.
5. *Emulator test log* — `LocationUpdaterIntegrationTest.applyToAllWidgets_writesConfiguredLocationForEveryWidget` failed.
6. *(Choice)* *“Race-safe assert (Recommended)”* — fix the flaky assertion only.
7. *“fix the silurian daily forecast issue now — today's high should come from the synoptic api”*
8. *“write session summary to summaries/ dir”*

---

## Root cause

### Silurian Today column (desktop)

Silurian is forecast-only (`supportsTemperatureActuals = false`) and **borrows** measured actuals (user setting: **Synoptic**). The pipeline already fetched Synoptic observations and could write `daily_history.computedHighTemp` from the borrowed blend, but desktop dropped those rows on read and never ran a live-today blend.

1. `DesktopWeatherRepository.loadDailyActuals` returned `emptyMap()` when `!supportsTemperatureActuals`, so Silurian’s borrowed highs never loaded (`ghostHigh` / actual high always null).
2. Desktop had no Android-equivalent of `DailyActualsLoader` — today’s high/low only came from `daily_history` after recompute, not from a live observation blend.
3. The drawn thermostat top was `solidHigh` / `barTopHigh` = current temp (or forecast fallback), so the solid middle bar never rose to the observed peak.

Android was correct because `DailyActualsStore` includes borrowers (`supportsTemperatureActuals || borrows(...)`), computes today live from observations, and draws a ghost (and, when current/actual highs are missing, falls back to the forecast high as `finalHigh`).

### LocationUpdaterIntegrationTest flake (unrelated)

```
expected: "Austin|30.2672|-97.7431"
but was:  "Austin|30.2672|-97.7431;South Van Ness Avenue, San Francisco, CA 94102|37.7749|-122.4193983"
```

An opportunistic GPS resample (emulator fix = South Van Ness, SF) appended another POI to `historical_pois` during the test. Exact full-string equality was racy.

---

## Changes

### Shared (`:shared`)

1. `ActualsProviderResolver.hasTemperatureActuals(source)` — true for own-product sources **and** borrowers (Silurian). Single load gate for Android `DailyActualsStore` and desktop `loadDailyActuals`.
2. `DailyDayValueResolver.TodayLineValues.barTopHigh` — `solidHigh ?: forecastHigh` (Android `DailyTodayResolver.finalHigh`). Mercury stays `currentTemp ?: actualHigh`; `ghostHigh` remains the observed peak.
3. `DailyDayValueResolver.resolveTodayLineValues` documents the shared formula (mercury, solid low, ghost, bar top).
4. Android `DailyActualsEstimator.calculateTodayTripleLineValues` resolves solid/ghost/low through `resolveTodayLineValues` and exposes `barTopHigh` (no second formula).

### Android (`:app`)

1. `DailyActualsStore` uses `ActualsProviderResolver.hasTemperatureActuals`.
2. `DailyTodayResolver` uses `tripleValues.barTopHigh` for `finalHigh` (same value as `solidLineHigh ?: dashedLineHigh` before).

### Desktop (`:desktop`)

1. **`loadDailyActuals`**
   - Gate: `hasTemperatureActuals(displaySource)` (Silurian included).
   - Past: `daily_history` + `PreviousSiteHistory` (unchanged).
   - **Today:** live `ActualsAggregator.aggregate` over stored observations (Synoptic rows feed Silurian’s borrowed group when `actuals_provider` = SYNOPTIC). Live peak wins over a stale `daily_history` row — Android `DailyActualsLoader` parity.
2. **`DesktopDailyForecastModel`** — `barTopHigh` on `DesktopDailyDay` (today from shared resolver; past/future = solid bar top).
3. **`DailyForecastGraph` (Today thermostat)** — drawn bar top = `max(barTopHigh, ghostHigh)` so the middle column **rises to today’s high** (Synoptic/METAR peak). Ghost still paints between mercury top and peak.

### Test flake

1. `LocationUpdaterIntegrationTest.applyToAllWidgets_writesConfiguredLocationForEveryWidget` — assert Austin is present and **last** in `historical_pois`, not the entire string.

---

## Tests

### New / updated

1. Shared `DailyDayValueResolverTest` — `barTopHigh` current / observed-peak / forecast-only fallback.
2. Shared `ActualsProviderResolverTest` — `hasTemperatureActuals` for Silurian, NWS, Open-Meteo, METAR, etc.
3. Desktop `DesktopDailyForecastModelTest` — `barTopHigh` rises to forecast high with no current/actual; `ghostHigh` + drawn top = Synoptic peak when present.
4. Desktop `DesktopWeatherRepositoryTest`
   - `loadCached ignores cached silurian temperature actuals` (Silurian’s own include_past rows are not actuals).
   - `loadCached includes silurian borrowed actuals from daily_history`.
   - `loadCached computes today high from live synoptic observations for silurian` (preference SYNOPTIC; live peak wins).
5. `LocationUpdaterIntegrationTest` — race-safe POI assert (3/3 pass on `emulator-5554`).

### Verification

1. `./gradlew :shared:testShortShared :desktop:testShortDesktop :app:testShortDebugUnitTest` — green.
2. `:shared:testMediumShared :desktop:testMediumDesktop :app:testMediumDebugUnitTest` — green.
3. Focused emulator: `LocationUpdaterIntegrationTest` — 3 passed.
4. Full staggered emulator run (before flake fix): 92/95 pass, 1 fail = LocationUpdater race (then fixed).

---

## Design notes

1. **Borrowed actuals, not Silurian model output.** Silurian `include_past` stays forecast-only (`supportsHistoricalActualsBackfill = false`). Today’s high comes from Synoptic (or METAR default) via `ActualsProviderResolver.providerIdFor` + `ActualsAggregator`’s borrowed group.
2. **Live today beats stored today.** Matches Android: a persisted `daily_history` row can use a different aggregation method and must not override the time-aligned live blender.
3. **Bar top vs mercury.** Mercury = `currentTemp ?: actualHigh`. Drawn top on desktop = max(that, observed peak) so the column shows today’s high as requested. Shared `barTopHigh` remains Android’s `finalHigh` (`solidHigh ?: forecastHigh`) for platforms that keep mercury + ghost as the solid extent.
4. **Unrelated flake.** LocationUpdater test isolation vs opportunistic GPS; not caused by the Silurian work.

---

## Follow-ups (optional)

1. Restart desktop process (`weather-widget-desktop`) to pick up the binary changes.
2. Commit when requested (no mid-task commit per project rules). Suggested message should reference this summary path.
3. If Android should also draw a **solid** bar to the observed peak (not only the ghost), change Android `DailyBarRenderer` to use `max(finalHigh, ghostLineHigh)` as the solid top — desktop already does.
4. Live verification on a running desktop instance with Silurian + Synoptic (daily view, Today column height vs high label).

---

## Task log

1. **T1** — Fix desktop Silurian daily forecast today-column high temp rendering — **done**
2. **T1.1** — Shared `hasTemperatureActuals` + `resolveTodayLineValues` / `barTopHigh` — **done**
3. **T2** — Ensure Silurian today’s high comes from Synoptic API end-to-end — **done**
4. LocationUpdater race-safe assert — **done** (in session; not a tracked task id)
