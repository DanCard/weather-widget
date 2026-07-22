# Forecast-line smoothing: desktop↔Android parity (draw raw, no value distortion)

**Date:** 2026-07-21
**Status:** Implemented 2026-07-21. Desktop `TemperatureGraph` now draws the forecast line from
raw `points.map { it.temperature }` (removed the `smoothValuesPreservingAllExtrema` pass +
now-unused `smoothIterations`; passes `smoothedForecasts = null`). Android renamed the misleading
`smoothedForecastTemps`/`smoothedExpectedTemps` → `forecastTemps`/`expectedTemps` (no behavior
change) with a comment locking in "raw, path-smoothing only." Shared `CurveMathTest` gained the
parity guard (node renders at its raw value — 84° stays 84°, not the old ~82.7° sag) + a
wide-zoom overshoot-bound test. All graph tests pass; both platforms compile clean. User confirmed
wide zoom looks good. Precip/cloud smoothing intentionally left (already in parity).

## Motivation

On all three Android devices the current-temp fetch dot sits **below** the forecast line;
on desktop the same dot (same value) sits **above** it. Investigation (DB dumps from all
platforms) proved this is **not** stale data — every platform holds identical NWS hourly
values (`86 / 84 / 78 / 74 / 72 / 70`). The divergence is a rendering difference:

- **Desktop** smooths the forecast *values* before drawing the curve
  (`TemperatureGraph.kt:187`): `smoothValuesPreservingAllExtrema(points.map { it.temperature },
  smoothIterations)` with `smoothIterationsFor(span)` = **1 / 3 / 4** iterations.
- **Android** applies **no** value smoothing. Its graph line draws raw hourly values
  (`TemperatureGraphRenderer.kt:186`, a variable misleadingly named `smoothedForecastTemps`
  that is literally `hours.map { it.temperature }`), and the shared header/current-temp path
  uses `HEADER_SMOOTH_ITERATIONS = 0` (a no-op).

At 7pm the raw node is `84°`, but `84` is a **non-extremum** on the monotonic decline
`86→84→78`, so desktop's smoothing sags it to ~**82.7°**. The observed reading `83.7°` sits
*between* those, so it renders below the raw Android line and above the sagged desktop line.

The desktop smoothing was almost certainly added by mistake: the comment at
`TemperatureGraph.kt:183-185` says it feeds the builder "matching Android's dense list by
construction" — but Android never smooths. The misnamed Android variable is what sold the
false premise.

### Why smoothing is the wrong tool here (the decision rationale)

- Visual smoothness already comes from **Catmull-Rom path smoothing** (shared `CurveMath` /
  `buildCurve` / `GraphRenderUtils`), which passes *through* every node. The value-smoothing
  pass buys no smoothness — it only moves data.
- It breaks the invariant **"the forecast line passes through the forecast value."** Desktop
  draws 7pm at ~82.7° when NWS says 84°. In an app whose headline feature is **forecast
  accuracy tracking**, silently bending predicted values away from what was forecast is a
  correctness problem, not a cosmetic one. The "dot above the line" is the visible symptom.

## Decision

Draw the temperature forecast line from **raw** hourly values on both platforms (the Android
behavior), relying on Catmull-Rom path smoothing for looks. Keep the value-smoothing behavior
identical across platforms and guard it with a shared parity test so it can't silently drift
again.

## Scope

**Temperature graph forecast line only.**

- **Out of scope (kept as-is, already in parity):** precip-probability and cloud-cover graphs
  smooth on *both* platforms (`PrecipitationGraph`/`CloudCoverGraph` desktop +
  `PrecipitationGraphRenderer`/`CloudCoverGraphRenderer` Android). They don't diverge and are
  noisier signals where trend-smoothing is defensible. If we later decide value-smoothing is
  wrong there too, that's a separate change. Called out so the reviewer knows it's deliberate.
- **Not touched:** the header/current-temp resolver — `HEADER_SMOOTH_ITERATIONS = 0` already
  means raw; `computeSmoothedForecasts` stays (it's the shared plumbing, just a no-op at 0).

## Changes

### 1. Desktop: draw the line from raw values (`desktop/.../TemperatureGraph.kt`)

- L159: remove `val smoothIterations = DesktopGraphUtils.smoothIterationsFor(totalSpanHours)`
  (becomes unused in this file — verified only L159/L187 reference it).
- L186-190: replace the smoothed `forecastTemps` + `smoothedForecastsMap` with raw values:
  ```kotlin
  val forecastTemps = points.map { it.temperature }   // raw; Catmull-Rom smooths the PATH, not the data
  ```
- L207: pass `smoothedForecasts = null` to `ActualTemperatureSeriesBuilder.build(...)`. The
  builder already falls back to raw (`smoothedForecasts?.get(hourMs) ?: forecast?.temperature`,
  `ActualTemperatureSeriesBuilder.kt:139`), so `forecastTemp` in the actual series becomes the
  raw value — consistent with the line. (Drop the now-empty `smoothedForecastsMap`.)

### 2. Android: kill the misnomer that caused this (`app/.../TemperatureGraphRenderer.kt`)

- L186: rename `smoothedForecastTemps` → `forecastTemps` (it's raw). Update the ~2 downstream
  references (`smoothedExpectedTemps` derivation, `forecastPoints`, the ctx field). No behavior
  change — this is the anti-drift fix: the lie in the name is what made desktop "match" it wrong.

### 3. Shared parity guard (anti-regression)

Add a shared test asserting the forecast **curve values equal the raw node values** (no
distortion). Pure, platform-free, lives with the shared curve math so both platforms are
covered by construction. This fails immediately if either platform re-introduces value
smoothing on the temperature line.

## Test coverage (fuller, up front)

- **Regression encoding THIS bug** (shared): nodes `[86,84,78,74]`, observed `83.7` → assert
  the forecast value at the 84-node is exactly `84.0` (not sagged), so dot-below-line holds.
- **Invariant** (shared): for any node list, the prepared forecast values equal the input
  (identity) — the "line passes through the forecast" guarantee.
- **Catmull-Rom overshoot bound** (shared, the wide-zoom safety net): the drawn path's sampled
  y between two monotonic nodes stays within `[min(node_i, node_{i+1}) − ε, max(...) + ε]` — i.e.
  raw + path-smoothing doesn't wiggle past the data by more than a small tolerance. This is the
  automated proxy for the manual wide-zoom check; if it fails we learn the smoothing was doing
  real overshoot-taming work and revisit (see Contingency).
- **Android builder** (unit): with `HEADER_SMOOTH_ITERATIONS = 0`, `forecastTemps` equals raw
  input (documents the intent behind the rename).

## Wide-zoom check (the manual verification the smoothing ostensibly protected)

Desktop's `smoothIterationsFor` scales with span **because desktop has a 30-day zoom Android
never had** — that zoom is why the smoothing was added. So removing it must be validated at
wide zoom specifically:

1. Capture desktop **before** (current build) at 3 zooms: ~1-day, ~3-day, ~30-day. Save JPGs.
2. Apply the change; capture **after** at the same 3 zooms.
3. Compare: confirm the raw curve doesn't become unacceptably jagged/wiggly at 3-day/30-day.
   Note: at 30-day, ~720 hourly points across ~1000px is <1.5px/point, so per-segment overshoot
   is tiny; 3-day (~72 points) is the most likely place to see any wiggle. Extrema (daily
   highs/lows) are unchanged either way, so day high/low **labels won't move**.

**Contingency** (only if wide zoom regresses): rather than value-smoothing (which distorts),
prefer a display-only tame — e.g. reduce Catmull-Rom tension or lightly **downsample** points
at extreme zoom — neither of which moves a node's value. Decide with the user if it comes to it.

## Verification

1. `./gradlew :shared:test` — new parity/overshoot tests green.
2. `./gradlew :desktop:test :app:testDebugUnitTest` (relevant classes) — green.
3. `./gradlew :desktop:compileKotlin :app:compileDebugKotlin` — clean (repo keeps warnings at 0;
   ensure no unused-variable warning from the removed `smoothIterations`).
4. Rebuild + relaunch desktop (`scripts/buildStart-desktop.sh`); screenshot the popup — confirm
   the fetch dot now sits **below** the forecast line (matching Android) for the current data.
5. Reinstall Android on one device (`./gradlew installDebug`); screenshot — confirm the dot
   position is unchanged (Android behavior was already correct) and the rename didn't regress.
6. Wide-zoom before/after JPvGs attached to this plan's follow-up.

## Risks / notes

- `smoothedForecasts` on the desktop builder path also influenced `forecastTemp` in the actual
  series (the past-day forecast overlay / label values). Switching to raw makes those raw too —
  intended and more correct, but eyeball the past-day yellow overlay once after the change.
- Precip/cloud intentionally still smooth; don't "fix" them in this change.
- Keep `smoothValuesPreservingAllExtrema` in shared + `GraphRenderUtils` (still used by
  precip/cloud); this change only removes the temperature-line *caller*.
