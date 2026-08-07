# Today-column overlay: greedy placement fragments the headroom, pushing temp/age onto the bars

Observed on desktop and emulator, Meteo source, daily view.

## Problem

All three optional today-column texts render, but only the delta sits in the headroom above the
forecast bars. The station temperature and reading age are drawn **over** the bars with heavy
outlines, even though the column has visibly ample empty space above the bar top.

Emulator diagnostics (`TodayColumnOverlay`, density 2.625 → `VERTICAL_PADDING_DP=3` = 7.875 px):

```
column=126.27..205.19  graph=51.20..359.60  bars=173.15..290.52  obstacles=4
delta:     68.12 x 26.15  -> zone=ABOVE      bounds=131.67,85.32,199.79,111.47  score=26.25
temp_age:  50.00 x 53.61  -> zone=ON_COLUMN  bounds=140.73,204.65,190.73,258.26  score=-976.375
```

Desktop shows the same shape (`-0.1 fcst` high in the headroom, `62.8°` / `0m` over the bars) —
both platforms share `TodayColumnOverlayPlanner`.

## Root cause

`TodayColumnOverlayPlanner.place` walks the blocks and calls `findBest` for each **independently**,
appending each result to `occupied`:

```kotlin
lines.forEach { line ->
    findBest(line, input, occupied)?.let { placement ->
        placements += placement
        occupied += placement.bounds
    }
}
```

`findBest` maximizes `score = clearance - barPenalty`, where `clearance` is the minimum distance to
the band edges and to any obstacle overlapping horizontally. Maximizing clearance parks a block in
the **middle** of the free run.

Working the numbers for the emulator case:

| | |
|---|---|
| ABOVE band | `59.07 .. 165.28` (106.2 px) |
| today's own high label (`74.6°`) caps it at | ~145 |
| effective free run | `59.07 .. ~145` (~85.9 px) |
| delta placed at | `85.32 .. 111.47` — dead centre |
| free fragment above delta | 26.25 px |
| free fragment below delta | ~33.5 px |
| temp_age needs | 53.61 px |

Total free (59.8 px) comfortably exceeds the 53.61 px needed, but **no single fragment does**, so
`ABOVE` and `BELOW` both yield no candidate and the block falls through to `ON_COLUMN`, which always
carries the `-1000` bar penalty (its band *is* `barTop..barBottom`). Hence `score=-976`.

Had the delta been packed against the band top (`59.07..85.22`), the remainder would have been
~59.8 px and both blocks would have fitted in `ABOVE`.

The existing safety net in `TodayColumnOverlayRenderer` does not fire:

```kotlin
if (specs.size > 1 && placements.size < specs.size) { /* retry as one combined stack */ }
```

Both blocks *were* placed — one just landed in the worst zone. The retry is keyed on "a block was
dropped", not on "a block landed badly".

## What will change

1. **Group-first placement in the preferred zone.** `TodayColumnOverlayPlanner.place` gains a
   pre-pass: for each zone in preference order (`ABOVE`, `BELOW`, `ON_COLUMN`), try to place *all*
   blocks as one contiguous stack — scan the zone for a free run tall enough for the summed block
   heights plus inter-block spacing, and lay them out sequentially from the top of that run. Take
   the first zone where the whole set fits. Only if no zone fits the whole set does the current
   per-block greedy search run, unchanged.

   This is the direct expression of the reported expectation: *if all three fit above the bars, put
   all three above the bars.* It also removes the arbitrariness of which block happens to be placed
   first.

2. **Keep the greedy path as the fallback**, so narrow/short columns (1x3, small widget sizes)
   behave exactly as they do now. No constant changes; `SAME`-zone scoring, penalties and
   `zonePreference` are untouched.

3. **Retire the `combined` retry in `TodayColumnOverlayRenderer`** once the planner does grouping
   properly — it exists only to paper over this fragmentation and it merges the blocks into a single
   paint/spec, losing the per-block structure. (Verify no test depends on the `"combined"` key
   before removing; keep it if it still earns its place for the width-overflow case.)

## Testing

Pure-function tests on `TodayColumnOverlayPlanner` (shared module, `@Category(ShortDuration::class)`),
plus renderer-level coverage. Each must be proven to fail before the change.

1. **The reported case, verbatim.** Feed the exact emulator geometry (`graph=51.20..359.60`,
   `bars=173.15..290.52`, `padding=7.875`, delta `68.12x26.15`, temp_age `50.0x53.61`, today's high
   label as an obstacle capping the band at ~145). Assert **both** placements come back with
   `zone == ABOVE` and non-overlapping bounds. Fails today (temp_age → `ON_COLUMN`, score −976).

2. **No block lands on the bars when the set fits above.** Generalized invariant: for any input
   where `sum(heights) + spacing <= largest free run in ABOVE`, assert no placement has
   `zone == ON_COLUMN`. Guards the fragmentation class rather than the single geometry.

3. **Order independence.** Same blocks supplied in reversed order must produce the same set of
   zones. Today the first block placed wins the best slot and dictates the outcome.

4. **Fallback preserved.** When the set genuinely does not fit in `ABOVE` (shrink the band below the
   summed height), assert the planner still returns the current greedy result — blocks placed,
   `ON_COLUMN` used only as last resort. Prevents the fix from regressing small widget sizes.

5. **Stacking geometry.** Blocks in a group placement are laid out top-to-bottom in input order,
   with no overlap and the expected inter-block spacing; the stack is horizontally centred on the
   column as before.

6. **Degenerate inputs.** Single block (grouping must be a no-op vs today's result); zero blocks;
   a block taller than every zone (still falls through to greedy/`ON_COLUMN`).

7. **Renderer parity.** `TodayColumnOverlayPlannerTest` covers the pure planner; add an
   Android-side check in the existing `TodayColumnOverlaySettingsRoboTest`/`TodayOverlaySettingsRoboTest`
   surface that with all three toggles on and a tall column, the emitted
   `TodayOverlayPlacementDebug` zones are all `ABOVE`. Desktop shares the planner, so no separate
   desktop test — but confirm visually on the running app.

## Verification

- Full `:shared:test` + `:app:testDebugUnitTest`.
- Emulator: `TodayColumnOverlay` log shows `zone=ABOVE` for both blocks, positive scores.
- Desktop: rebuild and confirm all three texts sit above the bar top, none outlined over the bars.
- Samsung: confirm the 2x3/1x3 sizes did not regress into overlap (the fallback path).

## Status

Planned — not yet implemented.
