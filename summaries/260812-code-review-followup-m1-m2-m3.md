# Code review follow-up — M2 (battery thresholds), M3 (periodic cadence), M1 (dead code), desktop sentinel

**Date:** 2026-08-12 · **Plan:** [plans/260812-code-review-followup-m1-m2-m3.md](../plans/260812-code-review-followup-m1-m2-m3.md)
**Target:** M1, M2, M3 from `plans/260812-code-review-refresh-coordination.md`, plus the desktop analog of H1.

H1 (silent "Mountain View" fallback) was already fixed on Android. This pass addresses the remaining
review findings that still made sense after those changes, and closes the same class of bug on the
desktop platform. All four findings were re-verified against the post-H1 code before acting.

---

## What shipped

| # | Finding | Change |
|---|---|---|
| 1 | M2 — scattered battery thresholds | `BatteryTier` (shared) is now the single owner of the percent thresholds; `ForecastFetchPolicy`, `BatteryStatePolicy`, `CurrentTempFetchPolicy` derive from it |
| 2 | M3 — cadence pinned to startup value | `WeatherWidgetWorker` re-pins the periodic interval after every full sync |
| 3 | M1 — dead overloads + deprecated constants | Deleted the two location-aware overloads and six unused `@Deprecated` constants from `WidgetStateManager` |
| 4 | Desktop — Google-HQ sentinel | Removed `FALLBACK_LATITUDE/LONGITUDE`; `DesktopWeatherService` requires a non-null config |

**Verified:** `:shared:test`, `:app:testShortDebugUnitTest`, `:desktop:testShortDesktop`, plus the
full `WeatherRepositoryTest` + `WidgetStateManagerTest` runs, all green · `:app:assembleDebug` and
`:desktop:compileKotlin` build clean. Changes are **uncommitted** (per repo convention).

---

## M2 — one source of truth, one preserved distinction

`BatteryTier` gained `TREAT_AS_CHARGING_THRESHOLD = 80`, `FULL_BATTERY_LEVEL = 100`,
`OPPORTUNISTIC_MIN_BATTERY_PERCENT = 65`, and `treatAsCharging(isCharging, batteryLevel)`.
`ForecastFetchPolicy`'s two inline `isCharging || batteryLevel >= 80` checks now call
`BatteryTier.treatAsCharging`; `BatteryStatePolicy.isEffectivelyCharging` reads
`BatteryTier.FULL_BATTERY_LEVEL`; `CurrentTempFetchPolicy.OPPORTUNISTIC_MIN_BATTERY_PERCENT` is a
`const val` alias to the shared value, so existing call sites keep working but the number lives in
one place.

The important nuance: `isEffectivelyCharging` (physically plugged/full, `>= 100`) was deliberately
**not** widened to `>= 80`. Those two checks answer different questions — `isEffectivelyCharging`
gates the charging current-temp/non-primary loops, and widening it to 80% off-charger would turn
those on at a battery level that does not justify them. Only the forecast *cadence* uses
`treatAsCharging`. The review's complaint was that the two layers disagreed about what a number
means; they still use different numbers, but now for explicitly-documented, non-overlapping reasons.

## M3 — self-correcting cadence, not a new receiver

`ACTION_BATTERY_CHANGED` cannot be manifest-registered (it's a sticky broadcast), so the review's
first suggestion (re-arm on a battery receiver) is not available. The chosen fix is its second
suggestion: after each non-UI-only full sync, `WeatherWidgetWorker` calls
`WidgetWorkScheduler.schedulePeriodicSync(context)` beside the existing `UIUpdateScheduler` call.
`schedulePeriodicSync` already uses `ExistingPeriodicWorkPolicy.UPDATE` (no cancellation — the
AGENTS.md crash trap is respected), so the interval re-pins to the battery tier observed at the end
of the run and self-corrects within one cycle of any level drift.

## M1 — the overloads were dead, and so were the constants

`getEffectiveVisibleSourcesOrder(lat, lon)` and `isSourceVisible(source, lat, lon)` had
`@Suppress("UNUSED_PARAMETER")` and silently delegated. `isSourceVisible(source, lat, lon)` had zero
callers; `getEffectiveVisibleSourcesOrder(lat, lon)` had two production callers
(`ForecastHistoryActivity`, `WeatherObservationsActivity`) that were routed to
`getVisibleSourcesOrder()`. The six `@Deprecated` source-name constants had no references anywhere
and were deleted outright rather than moved into a migration shim — a shim is for still-referenced
values, and nothing referenced these.

## Desktop — the sentinel was real, but unreachable for display

On inspection the desktop `FALLBACK_LATITUDE/LONGITUDE` were **not** a live wrong-weather bug the way
Android H1 was: the repository (the only thing that fetches/renders weather) is only built from a
non-null config, and `DesktopConfig.lat/lon` are non-null fields. The fallback only ever constructed
an unused `DesktopWeatherService`. Still the same misleading sentinel, so it was removed:
`FALLBACK_LATITUDE/LONGITUDE` are gone, the secondary constructor now takes a non-null
`DesktopConfig`, and `Main.kt` builds `weatherService`/`repository` only from a non-null config
(weatherService is now nullable, which downstream already tolerates).

---

## Test changes

- **New:** `BatteryTierTest` (shared, `@Category(ShortDuration::class)`) — `treatAsCharging`
  boundary and tier-interval behaviour.
- **Updated:** `WidgetStateManagerTest` (renamed the one test that hit the deleted overload),
  `WeatherRepositoryTest` (dropped the now-nonexistent `getEffectiveVisibleSourcesOrder(any, any)`
  mock stub).
- **Unchanged and still green:** `ForecastFetchPolicyTest`, `BatteryStatePolicyTest`, and
  `WeatherWidgetProviderEnqueuePolicyTest` — behaviour is preserved, so no assertions needed to move.

---

## Still open (deliberately deferred)

M4 (global last-full-fetch cooldown), M5 (`WeatherWidgetWorker` size, now 886 LOC), and L1–L4 are
still valid per the re-review but were out of scope for this pass. If acted on later, M5's suggested
`FullSyncPipeline` extraction is the highest-leverage structural item.
