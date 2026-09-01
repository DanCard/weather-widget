# Desktop: hover the now dot to see the stations behind it

*2026-08-31*

## Goal

On the desktop hourly temperature graph, hovering the mouse over the "now" dot pops up an overlay
listing the stations currently feeding the blended actual temperature, with their weights.

## Why this is mostly assembly

Every substantial piece already exists:

| Piece needed | Already there |
|---|---|
| Station data at render time | `TemperatureGraph` receives `observations: List<ObservationReading>` |
| Per-station weights / ages / distances | `BlendBreakdown` + `BlendContribution` (`weight`, `weightShare`, `ageMs`, `distanceKm`, `sourceKind`, `isSynthetic`) |
| Formatting of that into a table | `BlendTableFormatter` -> `BlendTable` / `BlendTableRow`, shared, already used by the Blend tab |
| Now-dot pixel centre | `fetchDotXVal` / `fetchDotYVal`, `TemperatureGraph.kt:469-476` |
| Pointer plumbing on the graph | `hourlyGraphTapInput` (taps, drag-pan, scroll-zoom) |

The graph already asks the blend for attribution — `captureLatestDominantAtOrBeforeMs = now`
(line 246) is what produces the on-graph `knuq 68°` dominant-station label. This widens that from
"the top station" to "all of them".

## The cost question, measured

The blend runs **inside the Canvas draw lambda** and is not memoised — `BlendSeriesCache` is wired
to `ActualsAggregator` (the panel/current-status path), not to the graph. Hover fires on every mouse
move, so the obvious risk is re-running a blend per mouse event. The figure in project memory is
~350 ms, which would have killed the idea.

**That figure is for a different call.** Measured against the live desktop DB (67,791 observations),
timing `ActualTemperatureSeriesBuilder.build` directly, median of 15 runs after JIT warm-up:

| Window | Observations fed | Median | With dominant capture |
|---|---|---|---|
| 18 h back (default zoom) | 706 | **1.6 ms** | 1.0 ms |
| 72 h back | 2,817 | 4.7 ms | 4.7 ms |
| 144 h back (max zoom-out) | 9,651 | 13.0 ms | 10.6 ms |

At default zoom that is 1.6 ms against a 16 ms frame budget, and the attribution capture costs
nothing measurable. Hover is affordable. **The 350 ms number would have talked us out of a cheap
feature** — worth recording, because it is the kind of remembered constant that quietly vetoes
designs.

## Design

### 1. Do not let hover re-run the blend

The rule that makes this cheap rather than merely survivable: **the Canvas draw lambda must never
read hover state.** Structure as

```
Box(modifier) {
    Canvas(...)                        // reads dot geometry, writes hit target
    NowDotStationsPopup(hoverState, breakdown, ...)   // reads hover state INTERNALLY
}
```

Passing the `MutableState` down and reading `.value` only inside the child confines recomposition to
the child. If `TemperatureGraph`'s own body read `hoverState.value`, every mouse move would
invalidate it and re-run the blend.

### 2. Hoist the blend to composition scope

`val actualSeries = ActualTemperatureSeriesBuilder.build(...)` currently sits inside the draw lambda.
Every argument it takes is already available in composition scope, so it moves out unchanged and the
draw lambda closes over it. This is what makes the breakdown reachable by the popup at all. Kept as a
plain `val` (not `remember`) so invalidation semantics stay exactly as today — `now` is recomputed
per composition, so a `remember` key would churn anyway and only add the illusion of caching.

### 3. Hit target via a non-state holder

The dot's pixel centre is only known inside the draw scope. Writing it to a `MutableState` read
during composition would risk an invalidation loop, so it goes into a plain mutable holder
(`NowDotTarget`) written during draw and read by the pointer handler. Not reactive by design: the
pointer handler only ever runs after a draw has populated it.

### 4. Plumb `captureBreakdowns` through `build()`

It exists on `blendObservationSeries` but `build()` forwards only
`captureLatestDominantAtOrBeforeMs`. Add the parameter, forward it, and surface the result on
`ActualTemperatureSeriesResult`. Capture is set to 1 (the newest point only). `captureMeta` is
already enabled by the dominant capture the graph requests, so the marginal cost is retaining one
deque entry.

### 5. Reuse the shared formatter, not a second one

The popup renders `BlendTableFormatter.format(...)` output — the same call the Blend tab uses — so
the two surfaces can never disagree about weights. This follows the single-source rule that
`DominantStationLabel` and `ValueLabelEngine` already follow. A popup-sized presentation (fewer
columns, capped rows) is a *view* over that data, never a recomputation of it.

## What will change

- `ActualTemperatureSeriesBuilder`: `build()` gains `captureBreakdowns`, forwards it,
  and `ActualTemperatureSeriesResult` gains `blendBreakdowns`.
- `TemperatureGraph.kt`: blend hoisted out of the draw lambda; `Box` wrapper; dot target holder
  written during draw; hover pointer input.
- **New** `desktop/.../NowDotStationsPopup.kt`: the overlay, plus `NowDotTarget` and the
  `Modifier.nowDotHoverInput` extension.
- `HourlyGraphInput.kt`: hover modifier lives alongside the existing pointer modifiers.

## Tests

| # | Kind | Test | Asserts |
|---|---|---|---|
| 1 | Unit | `build` surfaces breakdowns when asked | `captureBreakdowns = 1` yields one `BlendBreakdown` whose contributions match the blended point |
| 2 | Unit | `build` stays free when not asked | default call returns `blendBreakdowns` empty (no cost regression for other render paths) |
| 3 | Unit | hit test accepts a point inside the dot radius | pointer at the centre, and at the radius edge, both hit |
| 4 | Unit | hit test rejects a point outside, and rejects when no dot | just outside the radius misses; a null target never hits |
| 5 | Unit | popup rows come from the shared formatter | rows equal `BlendTableFormatter.format(...)` for the same breakdown — pins the single-source rule |
| 6 | Unit | popup is empty-safe | no breakdown / no contributions produces no popup rather than an empty frame |

## Verification

**Implemented 2026-08-31. User confirmed working.**

| Check | Result |
|---|---|
| `NowDotStationsPopupTest` (new) | 9 tests, 0 skipped, 0 failed |
| `ActualTemperatureSeriesBuilderTest` (2 new) | passes |
| `:shared:test` full suite | BUILD SUCCESSFUL |
| `:desktop:test` full suite | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` full suite | BUILD SUCCESSFUL |
| Compiler warnings | 3 "condition is always true" before and after — none introduced |
| Mutation probe | Forcing `nowDotHitTest` true and removing the row cap fails 2 tests |
| Live app | `scripts/buildStart-desktop.sh`, healthy 2-process launch, user confirmed the overlay |

### Changed after first review

The user asked for two things once they saw it:

1. **Show the raw reading.** The overlay carried only `fed to blend`. Both now show, because they are
   genuinely different numbers whenever a stale station was carried forward by the forecast —
   displaying only the fed value lets the overlay imply a thermometer read something it never did.
   Two tests pin the distinction (extrapolated: `64.0` raw vs `68.40 E` fed, tinted amber; observed:
   both agree, untinted).
2. **Too much space between the first two columns.** The station column was a fixed 74 dp, sized for
   the longest id that could appear (`TOMORROW_IO_REALTIME`), which left a visible gap after a 4-char
   ICAO code like `KNUQ` on every ordinary row. Switched to proportional `Modifier.weight` columns,
   which keep the table aligned while spending width where the content is. A header row was added at
   the same time, from `BlendTableFormatter.COLUMN_HEADERS`, since `raw` and `fed to blend` are
   otherwise indistinguishable adjacent numbers.

### Note for future work

The blend is still **uncached on the graph path** — `BlendSeriesCache` covers `ActualsAggregator`
only. That is fine at the measured 1.6–13 ms and the hover design deliberately avoids re-running it,
but it stays a per-redraw cost worth knowing about before anything else is added to that draw path.
