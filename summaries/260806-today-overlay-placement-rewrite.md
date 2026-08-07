# Today-Column Overlay: Interval-Packing Placement Rewrite

**Date:** August 6, 2026
**Platforms:** Samsung Galaxy Z Fold (SM-F936U1), Android emulator, Linux desktop
**Plan:** [plans/260806-today-overlay-placement-rewrite-maximal-opus.md](file:///home/dcar/projects/weather-widget/plans/260806-today-overlay-placement-rewrite-maximal-opus.md)
**Superseded alternative:** [plans/260806-today-overlay-greedy-fragmentation-opus.md](file:///home/dcar/projects/weather-widget/plans/260806-today-overlay-greedy-fragmentation-opus.md) (minimal fix, not taken)
**Status:** Implemented and verified on all three platforms. Not yet committed.

---

## 1. Problem

In the daily view's today column, all three optional texts rendered, but only the forecast delta sat
in the headroom above the forecast bars. The station temperature and reading age were drawn **over**
the bars with heavy outlines, despite visibly ample empty space above the bar top.

Reported on desktop and emulator with the Meteo source.

---

## 2. Root cause

`TodayColumnOverlayPlanner` placed each block independently, maximizing
`score = clearance - barPenalty` over a dense grid of candidate tops.

**The objective was inverted.** `clearance` is distance to the nearest obstacle, so the winner is
whatever floats in the *middle* of a free run — the one position that turns one usable gap into two
unusable ones. Every real packer hugs an edge precisely to avoid this.

Emulator diagnostics (density 2.625, `VERTICAL_PADDING_DP=3` = 7.875 px):

```
column=126.27..205.19  graph=51.20..359.60  bars=173.15..290.52
delta:     68.12 x 26.15  -> zone=ABOVE      bounds=131.67,85.32,199.79,111.47  score=26.25
temp_age:  50.00 x 53.61  -> zone=ON_COLUMN  bounds=140.73,204.65,190.73,258.26  score=-976.375
```

| | |
|---|---|
| ABOVE band | `59.07 .. 165.28` |
| capped by today's own high label at | ~145 |
| effective free run | ~85.9 px |
| delta placed at | `85.32 .. 111.47` — dead centre |
| fragments left | 26.25 px above, ~33.5 px below |
| temp/age needs | 53.61 px |

Total free space (59.8 px) exceeded the requirement, but neither fragment did individually.
`score=-976` is the `clearance - 1000` bar penalty: the last-resort zone.

Three further structural faults were confirmed while diagnosing:

- **`zonePreference` was unreachable.** `compareBy { score }.thenBy { zonePreference }` — `score` is
  a continuous float, so exact ties essentially never occur. ABOVE-over-BELOW was written down but
  never enforced; in the diagnosed case ABOVE won on clearance coincidentally.
- **`-1000` was a lexicographic order wearing a weighted-sum costume**, mixing pixels with a magic
  constant.
- **No notion that the blocks are one annotation**, so they could scatter across zones — the reported
  symptom — with an unstable reading order.

A minimal fix was planned first, then rejected in favour of replacing the objective and the search.

---

## 3. What changed

### Core (`shared/…/graph/TodayColumnOverlayPlanner.kt`)

The overlay is now treated as **one ordered stack**:

1. **Exact free intervals.** Obstacles overlapping the stack's horizontal extent are projected onto
   the y-axis, merged, and subtracted from each zone band — `O(n log n)`, continuous. Deletes
   `candidateTops`, the grid search and the `verticalStep` sampling.
2. **Lexicographic cost**, honoured by iteration order so the first success wins:
   `(overlaps bars, rows dropped, font shrink, group count, zone preference, −clearance)`.
   No weighted sum, no magic constant.
3. **Clearance kept, demoted** to the final tie-break. Once the stack moves as a unit there is
   nothing left to fragment, so centring it within the chosen run is free and preserves the original
   look in roomy columns.
4. **Degradation ladder** replacing the cliff into `ON_COLUMN`:
   full → shrink (1.0/0.9/0.8) → split across two runs → drop `age` → drop `temp` → delta-only
   `ON_COLUMN`. The ladder is **lazy and short-circuits**, so the common case costs exactly one
   measurement pass.
5. **Hysteresis.** Callers pass the previous frame's zones; a same-strength layout reproducing them
   wins. It can only override the weak terms (zone preference, clearance), never retain a materially
   worse layout.

Row-dropping and measurement stay **outside** the planner — it never learns what an "age row" is and
cannot measure text (Android uses `Paint`, desktop uses `TextMeasurer`). Content variants arrive via
`TodayColumnOverlayBlocks.variants(...)` and sizes via a `measureAt(variantIndex, scale)` callback.

`place(lines, input)` was **kept** as a thin wrapper over `layout(...)` rather than deleted, so the
four pre-existing planner tests exercise the new engine as free regression coverage. All passed
unchanged.

### Renderers

| File | Change |
|---|---|
| `shared/…/TodayColumnOverlayBlocks.kt` | new `variants()` — richest-first content ladder, delta never dropped |
| `app/…/TodayColumnOverlayRenderer.kt` | drives the ladder; rebuilds the paint at the **returned** scale; `combined` retry deleted |
| `app/…/DailyForecastGraphRenderer.kt` | `TodayOverlayRenderData.previousZones` |
| `app/…/handlers/DailyGraphRenderer.kt` | per-widget in-memory zone memo (`rememberOverlayZones`) |
| `desktop/…/DailyForecastGraph.kt` | same ladder; `remember`ed zone memo; `combined` retry deleted |

The renderer receiving the chosen scale back is what made font shrinking viable at all — previously
the paint was built *before* placement.

---

## 4. Two defects the plan did not anticipate

**Exact-fit float shortfall.** The reported geometry produced `stack=82.385` against
`band=82.384995` — a **7.6e-6 px** deficit that rejected the ABOVE band and drew both blocks across
the bars. Added `FIT_EPSILON = 0.01f`. This is the same knife-edge class the rewrite exists to
remove, so it belonged in the planner rather than in a relaxed test assertion.

**Zero-height lines were being filtered out.** Robolectric has no font engine, so
`fontDescent - fontAscent` is 0 and a one-row block measures 0 high — the delta row in
`DailyLargeTodayLayoutRoboTest`. The old code discarded those too, but `placements.size < specs.size`
then fired the `combined` retry, whose merged block had non-zero height and survived. **The hack
being deleted was the only thing making that test pass.** Zero-size lines are now kept; only
non-finite or negative metrics are rejected.

---

## 5. Two pre-existing desktop bugs, fixed here

Both were reported during verification and **predate this change** — confirmed by `git diff`:
neither `LargeTodayOverlayPolicy.kt` nor `DesktopDailyForecastModel.kt` was touched by the rewrite,
and the desktop log showed `enabled=false` decided upstream of the planner.

### 5.1 Overlay missing entirely on desktop

`LargeTodayOverlayPolicy.resolve` accepted an `extraHistoryColumns` parameter marked
`@Suppress("unused")` — plumbed through and never wired up. Eligibility therefore asked "is today
visible?" against a range that **excluded** the zoom-out extra-history columns, while the rendered
window **included** them (`historyOffsets` prepends them).

With `dateOffset=3` and `dailyExtraHistory=3` the candidate range was `today+2 .. today+12`; today
was judged off-screen and the whole overlay switched itself off — while sitting in column 2.

Fixed at the call site by widening the candidate range by `dailyExtraHistory`. The misleading
parameter is deleted, since it read as if the policy handled extra history when it did not.

### 5.2 Desktop overlay font ~2x too large

| | temp label | overlay | ratio |
|---|---|---|---|
| Android | `24f` (`TEMP_LABEL_TEXT_SIZE_DP`) | `17f` | **0.71** |
| Desktop | `12f` | `17f` | **1.42** |

The shared `TEXT_SIZE_DP = 17` was tuned against Android's 24dp labels. Desktop's labels are 12, so
the raw constant rendered the overlay at twice its intended relative size. Added
`TodayColumnOverlayStyle.TEXT_SIZE_FRACTION_OF_TEMP_LABEL` so the relationship is single-sourced;
desktop now scales from its own 12sp base.

---

## 6. Recurring theme

Three separate bugs today traced to **a written claim that nothing verified**:

- the `HourlyProximityQueryAllowlistTest` entry asserting `HourlyForecastLoader` had the "same
  sameSite filter + stitcher logic" (it did not — that shipped the `-13.7` delta);
- `LargeTodayOverlayPolicy`'s `@Suppress("unused") extraHistoryColumns`, implying the policy handled
  extra history;
- `TEXT_SIZE_DP` implying a platform-neutral size when it encodes an Android-specific ratio.

In each case the fix was to make the relationship executable or delete the claim.

---

## 7. Tests

`shared/…/TodayColumnOverlayPlannerLayoutTest` — 26 cases:

| group | cases |
|---|---|
| Free-interval arithmetic | 7 — split, merge, adjacency, horizontal filtering, clipping, full cover |
| The reported regression | 3 — verbatim emulator geometry, fragmentation invariant, order independence |
| Cost ordering | 4 — ABOVE over higher-clearance BELOW, shrink over drop, drop over bars, centring preserved |
| Ladder laziness | 1 — roomy column measures exactly once |
| Hysteresis | 4 — retained, abandoned when invalid, never weaker, sub-pixel anti-flap replay |
| Degenerates | 7 — zero/single block, oversized block, degenerate bounds, zero-height, non-finite |

Plus the four pre-existing planner tests, unchanged, now covering the new engine.

---

## 8. Verification

1. **Unit** — full `:shared:test` + `:app:testDebugUnitTest`: `BUILD SUCCESSFUL` (1824+ tests).
2. **Samsung Fold** — confirmed by user.
3. **Emulator** — confirmed by user.
4. **Desktop** — rebuilt via `scripts/buildStart-desktop.sh`; log shows
   `enabled=true` and `zone=ABOVE` for **both** blocks; all three rows stack in the headroom at a
   size proportionate to the neighbouring labels.

---

## 9. Follow-ups (not done)

- **Plan item 7**: the renderer-level `TodayOverlaySettingsRoboTest` extension asserting all-`ABOVE`
  zones and that the reported `mainTextSizePx` matches the planner's chosen scale.
- **Desktop hysteresis is per-composition**, not persisted; Android's is per-widget in-memory. Both
  reset on restart by design, but the desktop one is weaker.
- **Horizontal fitting remains out of scope.** Blocks still overflow narrow today columns and collide
  with neighbouring columns' labels — that collision is what caps the ABOVE band in the first place,
  so narrowing the text is likely the next worthwhile change.
- **Not committed.** Changes are in the working tree only.
