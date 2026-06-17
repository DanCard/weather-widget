# Note: Should we remove the `daily_extremes` table? (and would it fix location fragmentation?)

**Date:** 2026-06-17
**Context:** Debugging two related defects in the hourly temperature graph (see
`session-logs/260617-hourly-graph-nan-crash-pin-removal-5min-window-and-anchored-rerender.md`):
- Issue 1: daily-bar high/low (e.g. 72.4) ≠ hourly-graph high/low (72.9) for the same past day/source.
- Issue 2 symptom #1: the forecast line vanishes for the left ~65% of an anchored past-day view.

The question raised: *would getting rid of the `daily_extremes` table make the location-fragmentation
bug go away, and is removing the table a good idea?*

---

## TL;DR

- **No** — removing `daily_extremes` would **not** fix the location-fragmentation bug. That bug is in
  the **hourly_forecasts** render-time location filter, unrelated to `daily_extremes`.
- Removing `daily_extremes` *would* fix **Issue 1** (daily↔hourly divergence) by collapsing two
  computation paths into one — but it's a big, cross-cutting change and not the right tool for the bug
  in front of us.
- **Recommended:** fix the location fragmentation directly (proximity tolerance at the hourly filter);
  that likely resolves **both** issues without touching `daily_extremes`.

---

## Two separate things, conflated

### The location-fragmentation bug (forecast line missing — Issue 2 symptom #1)
Lives at `app/.../widget/WidgetRenderer.kt:139`:
```kotlin
val unifiedHourlyForecasts =
    hourlyForecasts.filter { it.locationLat == bestHourlyMatch.first && it.locationLon == bestHourlyMatch.second }
```
An **exact-float lat/lon filter over `hourly_forecasts`** (the forecast curve data). On the device,
yesterday's morning forecast rows exist but only at `37.4168434,-122.0889969`, while the pinned
`bestHourlyMatch` (≈ configured location) is `37.4168014,…`. Same physical spot, different GPS
precision (~tens of meters) → morning rows dropped → NaN forecast → no curve on the left.
`daily_extremes` is **not** in this path. Fix = small proximity tolerance (~100m) at this filter so
sub-precision fragments merge while genuinely different markers (e.g. `37.422`) stay excluded. Mirror
in `WeatherWidgetWorker.fetchHourlyForecasts` (its `bestPair` pin uses the same exact-equality).

### The daily↔hourly divergence (Issue 1)
The daily bar reads a **persisted, separately-computed cache** (`daily_extremes`, written by a
background aggregate), while the hourly graph **recomputes live**. Two code paths for one quantity ⇒
divergence + stale-cache bugs (the 72.4 seen on-device was written under old code, and the recompute
was gated off by the "rows exist, skip backfill" check). This is the part `daily_extremes` is actually
implicated in.

---

## What `daily_extremes` is / blast radius

Persisted per-(date, source, location) cache of actual high/low + day/night precip. Prefers official
provider extremes when available, else falls back to highest/lowest stored observation. Consumers
(~10): `DailyViewHandler` (the bars), `AccuracyCalculator` (30-day stats), `ForecastHistoryActivity`,
`CurrentTempRepository`, `ForecastRepository`, `WidgetIntentRouter`, `TemperatureStateResolver`, and
the **desktop** app (`DesktopWeatherDao`, `DesktopWeatherRepository`).

### Removing it — pros
- Single source of truth: daily bar and hourly graph compute the same way ⇒ Issue 1 gone by
  construction.
- Kills the stale-cache + recompute-gating class of bugs.
- Less schema/migration surface.

### Removing it — cons
- **Durability:** it survives raw-observation cleanup; live compute needs raw obs to still exist for
  every shown day (daily view looks back up to 30 days).
- **Accuracy/stats:** `AccuracyCalculator` reads it; those would recompute from obs.
- **Precip:** day/night precip sums move to live compute.
- **Cross-cutting:** ~10 consumers incl. desktop parity.
- **Perf:** probably fine (the hourly graph already blends one day live; ~9 days is ~tens of ms), so
  this is the weakest objection.
- **Does NOT fix the location-fragmentation bug** — that's a separate hourly-forecasts filter fix.

---

## Recommendation

1. **Fix the fragmentation at `WidgetRenderer.kt:139`** (proximity tolerance instead of exact-float
   equality), and the matching pin in `WeatherWidgetWorker.fetchHourlyForecasts`. This is the targeted
   cure for the forecast line.
2. **Expect it to also resolve Issue 1**: the daily aggregate already reads observations through a
   proximity box; only the *hourly render* re-filters by exact float. Once the hourly render uses the
   same proximity box, both paths see the same data and converge — **without removing `daily_extremes`**.
3. Treat **removing `daily_extremes`** as a separate, larger architectural simplification to weigh on
   its own merits (single source of truth, no stale cache) — not as a fix for the current bug. Only
   pursue if the divergence keeps causing pain after (1)+(2) and the durability/accuracy/desktop
   implications are acceptable.

Related memories: `desktop_coordinate_fragmentation`, `shared_location_match_predicate`,
`daily_vs_hourly_actual_extrema_mismatch`, `widget_fetch_location_decoupled`.
