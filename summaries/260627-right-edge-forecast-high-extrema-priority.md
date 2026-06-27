# Hourly temp graph: right-side forecast high now labeled (endpoint-declutter prioritizes extrema)

**Date:** 2026-06-27
**Branch:** main
**Status:** Changes in working tree (not committed). Shared unit tests green
(`:shared:test`). Android rebuilt+installed on emulator; user verified the crest is now
labeled on-device.

## Problem

On the hourly temperature forecast graph, the forecast curve rose to a crest on the
**right** side (e.g. a 68° peak easing down to a 66° endpoint), but only the 66°
endpoint label was drawn — the genuine 68° crest was unlabeled. Reported on the
emulator; confirmed from device logcat + screenshot (forecast tail `…66, 67, 68, 68,
67, 66`, crest at idx 164 of 167).

## Root cause

`TemperatureLabelResolver.checkEndpointSuppression` was a value-blind edge-proximity
declutter that dropped *secondary forecast extrema* (`FORECAST_HIGH/FORECAST_LOW/
PAST_FORECAST_HIGH/PAST_FORECAST_LOW`) whenever they fell within `edgeWindow` indices of
an edge. For this render: `forecastHighIndex=164`, `lastIndex=167` →
`edgeWindow = min(5, 167/15) = 5`, `edgeDist = min(164, 167-164) = 3`; since `3 <= 5`
and idx ≠ endpoint, the crest was suppressed before it ever reached the placement engine.

The rule's **priority was backwards**: it kept the positional START/END endpoint label
and threw away the more meaningful extremum.

The drop was also **silent** — the suppression/accept log guards only fired for
`HIGH/LOW/ACTUAL_*`, never `FORECAST_*`, so nothing appeared in logcat (the candidate
showed in `Deduplicated`/`Filtered`, then vanished with no trace).

## Fix

User directive: *"the endpoint-declutter rule should prioritize temperature extrema
rather than endpoints."*

1. **Deleted `checkEndpointSuppression`** (function + its call in `collectLabelCandidates`).
   Near-edge forecast extrema now always survive. When an endpoint merely echoes a nearby
   extremum's value, the **endpoint** is dropped instead — by the already-present
   `checkRedundantPairSuppression` START/END branch (value-redundant `< 2°`, pixel-near
   against `secondaryForecastTargets`). That is the extrema-win path; no new logic needed.

2. **Un-silenced `FORECAST_*` logging.** Added a shared `LOGGED_SUPPRESSION_ROLES` set
   (includes `FORECAST_*`/`PAST_FORECAST_*`) and used it at all six log guard sites
   (LEFT_EDGE / FETCH_DOT / REDUNDANT / TRANSITION suppressions + LabelAccepted). Future
   near-edge forecast-extremum drops are now visible in logcat (`Log.v`, logcat-only,
   never persisted — matches `feedback_permanent_debug_logging`).

Shared module, so Android widget + desktop app both fixed from one change.

### Why safe

- The crest isn't caught by any other gate: transition-boundary uses
  `window = min(3, 167/20) = 3`, `abs(164-154)=10 > 3`; `FORECAST_HIGH` redundancy only
  compares against `actualHighIndex` (far away); dense-filter keeps it (explicit/immovable
  anchor `forecastHighIndex`).
- The only existing endpoint test (`absolute actual low just inside the right edge is not
  endpoint-suppressed`) exercises `ACTUAL_LOW`, already exempt — still passes.

## Tests

`shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt`:
- `rightEdgeCrestHours(crest, endValue)` fixture — forecast crests just inside the right
  edge (idx 56, edgeDist=3); global daily HIGH (78) lives back at idx 8 so the crest's
  role is `FORECAST_HIGH`, not `HIGH`.
- `right-edge forecast crest is kept when it differs from the endpoint` (68 vs 66, the
  live case) → asserts a `FORECAST_HIGH` candidate at idx 56.
- `near-edge forecast extremum wins over a redundant endpoint` (67.8 vs 67.5) → asserts
  the crest survives **and** the END (idx 59) is the one suppressed.

## Verification performed

- `./gradlew :shared:test` — full suite green; both new tests present, 0 failures.
- `./gradlew :app:assembleDebug` + `adb install -r` on `emulator-5554`; user verified the
  68° crest is now labeled on the widget.

## Files touched

- `shared/.../graph/TemperatureLabelResolver.kt` (removed checkEndpointSuppression;
  added LOGGED_SUPPRESSION_ROLES, applied at all 6 log guards)
- `shared/.../test/.../TemperatureLabelSuppressionTest.kt` (fixture + 2 tests)

## Process note

An on-device verification detour used `adb shell monkey … -c LAUNCHER`, which spuriously
opened MainActivity (referrer `com.android.shell` in the `MAIN_LAUNCH` log). The widget —
not an activity — renders this graph; the correct non-intrusive re-render is an
`APPWIDGET_UPDATE` broadcast by widget ID. Not a regression; unrelated to this fix.

Plan file: `~/.claude/plans/emulator-hourly-temperature-forecast-bubbly-tome.md`
Memory: `endpoint_declutter_extrema_priority.md`
