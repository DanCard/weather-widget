# Session summary — Phase 4 of desktop/Android duplication review: mechanical leftovers

**Date:** 2026-09-09 · **Plan:**
[plans/260909-desktop-android-duplication-and-complexity-review.md](../plans/260909-desktop-android-duplication-and-complexity-review.md) ·
**Status:** implemented, all tests green; committed with this summary

## User prompts

> run all tests after each phase. For all tests considering doing something like
> scripts/staggered_tests.sh . proceed Consider updating agents.md with how to run all tests.

> write above phase 1 summary to summaries/ dir. After that commit and proceed to next phase

## Goal

Phase 4 of the review: clear the mechanical leftovers (unit conversions, dp helpers, age formatters,
the Tomorrow.io doc/code mismatch) and scope the `WeatherDatabase` migration work as its own plan.

## What changed

1. **A7 — one `Float.dp`.** Eight byte-identical `private fun Float.dp(density: Float)` declarations
   in `DailyGraphPaintCache`, `DailyHighLabelPlanner`, `DailyColumnRenderer`,
   `DailyForecastGraphRenderer`, `DailyGraphLayoutResolver`, `DailyForecastHeaderRenderer`,
   `TodayColumnOverlayRenderer` and `DailyBarRenderer` were deleted; a single
   `internal fun Float.dp` now lives in `app/.../widget/Dp.kt`.
2. **A5 — conversions through `TempUtils`.** The remaining inline `if (useCelsius) x / 1.8 else x`
   (and `fahrenheitToCelsius` variants) now call `TempUtils.displayDelta` / `TempUtils.display` in
   `StatisticsWindow`, `ForecastHistoryWindow`, `ForecastHistoryViewLogic`, `ForecastDeltaLabel`,
   `TemperatureLabelResolver`, `CurrentTemperatureResolver` and `BlendTableFormatter`. What remains
   is `TempUtils`' own definition plus genuine non-conversion thresholds.
3. **A6 — no change needed.** The two `formatAgeLabel` wrappers already delegate to
   `FetchDotLabel.formatAgeLabel`; `StaleObservationFallback.formatAge` ("45min"/"6h"/"3d") and
   `BlendTableFormatter.formatAgeMs` ("5m") are intentionally different, documented styles.
4. **C4 — doc now matches code.** `AGENTS.md` no longer says Tomorrow.io is "debug-only": it is not
   default-visible (Android debug-only default, desktop never) but is user-selectable on both
   platforms via `WeatherSourceOrdering.ALL_CONFIGURABLE`.
5. **New `plans/260909-weatherdatabase-migration-strategy.md`.** Proposal only: remove the
   destructive fallback, adopt Room auto-migrations for `ADD COLUMN` steps, keep hand-written ones
   for data rewrites, close the 9 untested migrations + add a full-chain 44→70 test, and document
   the "adding a migration" checklist.

## Verification

- `./scripts/staggered-tests.sh`: **4057 unit + 95 instrumented (2 skipped) — all passed.**
- `:app:compileDebugKotlin`, `:desktop:compileKotlin`, `:shared:compileKotlin` clean.
- No behavior change intended; the conversions are arithmetic-equivalent.

## Phase 3 note (deferred)

Phase 3 (unify the desktop NWS fetch with Android's `NwsForecastMapper`/`NwsObservationSource`) was
analysed and **deferred**: the mapping algorithms are already single-sourced, and
`NwsDailyMapper.buildDailyForecasts` documents parity with Android. The remaining difference is
output shape / mapping depth (Room entities + the richer accumulator pipeline vs the shared
`RawFetch`), so unifying is behavior-changing and needs its own before/after plan. Recorded in the
parent plan.

## Next (pending)

- `plans/260909-weatherdatabase-migration-strategy.md` phases A–D.
- Phase 3, if the forecast payloads are to be converged.
