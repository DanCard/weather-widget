# Today-column overlay: replace grid-search placement with interval packing (maximal fix)

Alternative to `plans/260806-today-overlay-greedy-fragmentation.md`, which patches the symptom.
This one replaces the objective function and the search.

## Why the current algorithm is wrong, not just buggy

`TodayColumnOverlayPlanner` places each block independently, maximizing
`score = clearance - barPenalty` over a dense grid of candidate tops.

1. **The objective is inverted.** `clearance` = distance to the nearest obstacle, so the winner is
   whatever floats in the *middle* of a free run. Centring an item in a gap is the only placement
   that turns one usable run into two unusable ones. Every packer (first-fit, bottom-left) hugs an
   edge precisely to avoid this. The observed bug is the direct consequence: a 26 px block centred
   in an 86 px run left 26 px and 33 px fragments for a 54 px block that would have fit against
   either edge.

2. **`zonePreference` is unreachable.** The comparator is
   `compareBy { score }.thenBy { zonePreference(it.zone) }`; `score` is a continuous float, so exact
   ties essentially never occur and the `thenBy` never fires. ABOVE-over-BELOW is written down but
   not enforced. In the diagnosed case ABOVE won on clearance *coincidentally* (26.25 vs a BELOW
   ceiling of ~13.6).

3. **`-1000` is a lexicographic order wearing a weighted-sum costume.** It mixes pixels with a magic
   constant and works only because 1000 exceeds any real clearance.

4. **Brute force over 1-D interval arithmetic.** `candidateTops` enumerates ~`band/verticalStep`
   positions per zone per block, each O(obstacles) for intersection plus O(obstacles) for clearance.
   The free runs can be computed exactly by projecting and merging obstacles.

5. **One degree of freedom out of three.** `left` is hard-centred on the column with no width gate,
   so blocks overflow into neighbours and collide with *their* labels. Font scale is never traded
   against position.

6. **No notion that the blocks are one annotation.** They may be scattered across zones, producing
   the reported split (delta above the bars, temp/age over them) and an unstable reading order.

7. **Latent flapping risk.** Placement is a knife-edge max over a dense grid, and the obstacles are
   labels that move as temperatures change. A sub-pixel shift can flip a block between zones —
   the same unstable-selection-over-near-ties class as the `-13.7` delta bug
   (`plans/260806-today-column-stale-fragment-delta-opus.md`).

## Design

Reframing: **it is not "place N blocks", it is "lay out one ordered stack."**

### 1. Exact free intervals (replaces the grid search)

For a given horizontal extent, project every obstacle that overlaps it onto the y-axis, merge the
projections, and subtract from each zone band. Yields the exact free runs, continuous, no
quantization, `O(M log M)`.

```kotlin
internal fun freeRuns(band: ClosedRange<Float>, obstacles: List<Bounds>, x: ClosedRange<Float>): List<ClosedRange<Float>>
```

`candidateTops`, `clearance`-as-objective and `verticalStep` all disappear from the search path.

### 2. Lexicographic cost (replaces `clearance - 1000`)

A candidate layout is scored by an ordered tuple, each term strictly dominating the next:

| rank | term | rationale |
|---|---|---|
| 1 | `zoneRank` (ABOVE 0, BELOW 1, ON_COLUMN 2) | never draw over the bars if any zone avoids it |
| 2 | `rowsDropped` (variant index) | keep content once the zone is settled |
| 3 | `fontShrinkSteps` | shrink before splitting |
| 4 | `splitCount - 1` | a single stack reads better than two |
| 5 | `-clearance` | the existing aesthetic, demoted to final tie-break |

**Two adjacencies here are judgement calls, called out for review:**
- *zone before rows-dropped* (rank 1 vs 2): prefers dropping the `0m` age row over drawing all three
  across the bars. A floor prevents this going too far — the poorest variant always retains the
  delta row, so the worst case is "delta only, ON_COLUMN", never "nothing".
- *shrink before split* (rank 3 vs 4): assumes slightly smaller text reads better than a stack
  broken across two runs.

Both are single constants in the comparator and trivial to reorder after seeing it.

### 3. Clearance kept, demoted

The instinct behind maximizing clearance is sound — text floating in whitespace looks better than
text jammed against a label. It is only wrong as the *primary* objective. As the final tie-break it
still centres the stack within the chosen run whenever there is room to spare, so the common,
roomy case looks exactly as it does today.

### 4. Degradation ladder (replaces the cliff into ON_COLUMN)

Today the fallback is binary: fits, or draw over the bars. Replace with an ordered search over
`variant × scale × zone × runs`:

```
all rows, full size, ABOVE
  -> all rows, 0.9/0.8 scale, ABOVE
  -> all rows, split across two ABOVE runs
  -> same ladder in BELOW
  -> drop `age`, repeat ladder
  -> drop `age` + `station temp`, repeat ladder
  -> delta only, ON_COLUMN (last resort, as today)
```

**Row dropping stays out of the planner.** The planner must not know what an "age row" is. The
caller supplies pre-ordered *content variants* (richest first); the planner treats each as an opaque
list of blocks. Same for measurement: the planner cannot measure text, so the caller supplies

```kotlin
measureAt: (variantIndex: Int, scale: Float) -> List<Line>
```

Both platforms already have this shape (`TodayColumnOverlayRenderer.fittedPaint`,
`DailyForecastGraph.layoutAt`).

**The ladder is lazy and short-circuits at the first acceptable candidate**, so the common case
costs exactly one measure pass — no more than today. Only genuinely cramped columns pay for extra
measurement.

### 5. Hysteresis (kills the flapping risk)

The caller passes the previous frame's zone per block key plus an epsilon. If the previous layout is
still valid (fits, no obstacle intersection) and its cost tuple is within epsilon of the new
optimum, keep it. Prevents label jitter from migrating text between zones between renders.

Storage: Android already persists per-widget presentation state (`WidgetPresentationStateStore`);
desktop can hold it in the view model. If persistence proves awkward, in-memory per-process is
sufficient — the flapping being prevented is between consecutive renders.

### API shape

`place(lines, input)` is replaced by a stack-oriented entry point; both call sites are ours, so no
compatibility shim.

```kotlin
fun layout(
    variants: List<List<Line>>,          // richest first; caller-ordered, planner-opaque
    scales: List<Float>,                 // e.g. [1f, 0.9f, 0.8f]
    measureAt: (variantIndex: Int, scale: Float) -> List<Line>,
    input: Input,                        // + rowSpacing, previousZones, hysteresisEpsilon
): Layout                                // placements + chosen variant/scale, for the renderer
```

The renderer needs the chosen scale back so it paints at the size the planner assumed — today the
paint is built before placement, which is part of why font scaling was never viable.

## What stays the same

- `Zone`, `Bounds`, `Line`, `Placement` types and the `Bounds.intersects` semantics.
- `TodayColumnOverlayBlocks` (block/row selection) — untouched.
- `TodayColumnOverlayStyle` constants — untouched; no visual redefinition beyond placement.
- Horizontal behaviour: still column-centred, still no width gate. **Font shrinking here is
  vertical-fit only** — it is not a re-introduction of the width fitting that was deliberately
  removed (`"No horizontal fit gate"`). Narrowing to stop neighbour-column collisions is a separate
  question, deliberately out of scope.

## What gets deleted

- `candidateTops` and the grid search in `findBest`.
- `clearance` as an objective (kept as a tie-break helper).
- The `-1000` `barPenalty` constant.
- The `combined` retry in `TodayColumnOverlayRenderer` (~12 lines) — it exists solely to paper over
  fragmentation and it merges blocks into one spec, losing per-block structure.

Net: the replacement is **smaller** than what it replaces, and interval arithmetic is far more
testable than probing a grid for emergent behaviour.

## Testing

Pure tests on the shared planner (`@Category(ShortDuration::class)`), each proven to fail first where
it describes a behaviour change. Existing `TodayColumnOverlayPlannerTest` (shared, 38 lines; app, 93
lines) gets rewritten against the new entry point.

### Interval arithmetic (new, exactly testable)

1. `freeRuns` with no obstacles returns the whole band.
2. Obstacle fully inside the band splits it into two runs with exact boundaries.
3. Obstacles that do **not** overlap the stack's x-extent are ignored.
4. Overlapping/adjacent obstacles merge into one exclusion (no zero-width phantom runs).
5. Obstacle covering the band returns no runs.
6. Obstacle straddling a band edge clips rather than splits.

### The reported regression

7. **Verbatim emulator geometry** — `graph=51.20..359.60`, `bars=173.15..290.52`, padding 7.875,
   delta `68.12x26.15`, temp_age `50.0x53.61`, today's high label capping the band at ~145. Assert
   both blocks land `ABOVE`, in input order, non-overlapping. Fails today (temp_age → `ON_COLUMN`,
   score −976.375).
8. **Fragmentation invariant** — for any input where the summed stack height plus spacing fits the
   largest ABOVE run, no placement may have `zone == ON_COLUMN`. Guards the class, not the case.
9. **Order independence** — blocks supplied reversed produce the same zones and the same relative
   stacking order.

### Cost ordering

10. Given a choice, ABOVE beats BELOW even when BELOW offers strictly greater clearance — the
    assertion that fails against today's dead `zonePreference`.
11. A shrunken full-content layout is preferred over a full-size layout that would drop a row.
12. A split layout in ABOVE is preferred over a single stack in BELOW.
13. Dropping the age row is preferred over placing all three ON_COLUMN.
14. **Floor**: the delta row is never dropped; a column too short for anything still emits delta,
    ON_COLUMN.
15. Clearance still decides between two otherwise-equal candidates (centring preserved in roomy
    columns) — pins that the aesthetic did not regress.

### Ladder and laziness

16. A roomy column calls `measureAt` exactly once (variant 0, scale 1.0) — the no-regression
    performance assertion.
17. Each rung is exercised by shrinking the band: full → shrunk → split → dropped → ON_COLUMN,
    asserting the chosen variant/scale reported back to the renderer.

### Hysteresis

18. Previous zone still valid and within epsilon → retained even when a marginally better slot
    appeared.
19. Previous zone no longer valid (obstacle moved into it) → abandoned, new optimum chosen.
20. Epsilon exceeded → new optimum chosen, so hysteresis cannot pin a badly stale layout.
21. **Anti-flap regression**: replay two renders whose obstacle geometry differs by a sub-pixel
    amount; assert identical zones. Fails against today's knife-edge grid max.

### Degenerate inputs

22. Zero blocks → empty layout, no crash.
23. Single block → grouping is a no-op; same result as a direct placement.
24. Block taller than every zone and every scale → floor behaviour, no exception.
25. `columnRight <= columnLeft` / `graphBottom <= graphTop` → empty, matching today's guard.

### Platform-level

26. `TodayOverlaySettingsRoboTest` extended: all three toggles on, tall column → every emitted
    `TodayOverlayPlacementDebug.zone` is `ABOVE`, and the reported `mainTextSizePx` matches the
    scale the planner chose (guards the renderer honouring the returned scale).
27. Desktop shares the planner; no separate desktop unit test, but visual confirmation is required
    (see Verification) because the desktop renderer must also honour the returned scale.

## Verification

- Full `:shared:test` + `:app:testDebugUnitTest`.
- Emulator: `TodayColumnOverlay` log shows `zone=ABOVE` for both blocks with positive scores, and a
  single measure pass in the roomy case.
- Desktop: rebuild via `scripts/buildStart-desktop.sh`; all three texts above the bar top, none
  outlined over the bars.
- Samsung Fold: check 1x3, 2x3 and the large daily size — the small sizes exercise the ladder, and
  this is where a regression would show as newly-dropped rows or newly-shrunk text.
- Flap check: leave the widget through a few update cycles and confirm the zone does not migrate.

## Risks

- **Visually sensitive area on two platforms.** Mitigated by keeping clearance as the tie-break, so
  roomy columns should render identically to today; any diff there is a bug.
- **The two judgement-call adjacencies** in the cost tuple may need reordering after a look. Both are
  one-line changes.
- **Font shrinking is new behaviour on both renderers** — the renderer must paint at the returned
  scale. Test 26 pins this; if either renderer proves awkward, ship with `scales = [1f]` (ladder
  intact, shrink rung disabled) and enable it separately.
- Rollback is clean: the change is confined to the planner plus the two call sites.

## Status

**Implemented and verified 2026-08-06 on Samsung Fold, emulator, and desktop.**

Shipped items 1-5. `place(lines, input)` was kept as a thin convenience wrapper over `layout(...)`
rather than removed, so the pre-existing planner tests still exercise the new engine as regression
coverage (all four passed unchanged).

Two defects surfaced during implementation that the plan had not anticipated:

* **Exact-fit float shortfall.** The reported geometry produced `stack=82.385 band=82.384995` — a
  7.6e-6 px deficit that rejected the ABOVE band and drew both blocks across the bars. Added
  `FIT_EPSILON = 0.01f`. Same knife-edge class the rewrite exists to remove.
* **Zero-height lines were being filtered out.** Robolectric has no font engine, so a one-row block
  measures 0 high; the old code dropped those too but the `combined` retry silently recovered them —
  deleting the retry exposed it as a dropped row. Zero-size lines are now kept; only non-finite or
  negative metrics are rejected.

Two further desktop bugs were reported during verification and fixed here. **Both predate this
change** — confirmed by `git diff`: neither `LargeTodayOverlayPolicy.kt` nor
`DesktopDailyForecastModel.kt` was touched by the rewrite.

* **Overlay missing entirely on desktop.** `LargeTodayOverlayPolicy.resolve` took an
  `extraHistoryColumns` parameter marked `@Suppress("unused")` — plumbed through, never wired up. So
  eligibility asked "is today visible?" against a range excluding the zoom-out history columns while
  the display included them. At `dateOffset=3` + `dailyExtraHistory=3` the candidate range was
  `today+2..today+12`, today was judged off-screen, and the overlay switched itself off while sitting
  in column 2. Fixed at the call site; the misleading parameter is gone.
* **Desktop overlay font ~2x too large.** `TEXT_SIZE_DP = 17` is tuned against Android's 24dp
  temperature labels (ratio 0.71). Desktop's labels are 12, so the raw constant rendered at 1.42x.
  Added `TEXT_SIZE_FRACTION_OF_TEMP_LABEL` so the relationship is single-sourced, and desktop now
  scales from its own 12sp base.

Tests: `TodayColumnOverlayPlannerLayoutTest` (26 cases — interval arithmetic, the reported
regression, cost ordering, ladder laziness, hysteresis incl. the sub-pixel anti-flap replay,
degenerates). Full `:shared:test` + `:app:testDebugUnitTest` green.

Not done: item 7's renderer-level `TodayOverlaySettingsRoboTest` extension, and the desktop
`onZonesResolved` hysteresis is per-composition rather than persisted. Supersedes
`plans/260806-today-overlay-greedy-fragmentation.md` (minimal fix) if adopted; that plan remains as
the fallback if this proves too large.
