# Today-column overlay: ABOVE splits its run's slack with a 25% top bias

**Date:** 2026-08-10
**Plan:** none (single-rule change, driven live off the emulator)
**Files:** `TodayColumnOverlayPlanner.kt` (shared), `TodayColumnOverlayPlannerLayoutTest.kt`

## Problem

Reported on the **emulator**, Meteo source, large daily view: the today column's optional text
(`-0.2 fcst` / `67.3°`) hugged the top part of the column with empty space above it, and read as
crammed rather than placed.

Captured live from `adb logcat -s TodayColumnOverlay:V` — the renderer prints the run, the obstacles
and the final bounds, which is what made this measurable rather than a matter of taste:

| quantity | px |
|---|---|
| `aboveCeiling` (header measured ink + `padding`) | 30.22 |
| today's `76.9°` high label (box top) — caps the run | 113.88 |
| **free run ABOVE** | **83.66** |
| stack (`delta` + `dominant_temp_age`, 2 rows) | 53.61 |
| stack as drawn | 54.67 – 108.27 |

So the stack was pinned to the *bottom* of its run and all 24.4 px of spare room collected as one
empty band under the header, while the last row sat ~5.6 px off the high label.

That bottom-hug was itself a fix (2026-08-07, `today-overlay-header-ceiling-is-per-column`): once
`aboveCeiling` became the header's *measured* ink instead of a guessed fraction of the band,
top-hugging floated the stack up under the header. The correction over-shot — it banked every spare
pixel on the side nobody looks at.

## Change

`TodayColumnOverlayPlanner.layOut`, the `Zone.ABOVE` arm:

```kotlin
Zone.ABOVE -> min(free * ABOVE_SLACK_ABOVE_FRACTION, (free - input.padding).coerceAtLeast(0f))
```

with `internal const val ABOVE_SLACK_ABOVE_FRACTION = 0.25f`. `BELOW` still hugs its far edge (the
graph bottom, away from both the bars and the header); `ON_COLUMN` still centres outright.

Two decisions worth keeping:

- **A bias, not a hard top hug.** The crowded seam is the one with the day's high label, not the
  header — `aboveCeiling` already carries a full `padding` below the header ink. But that ceiling is
  measured **per column**, so on a column the header does not reach over, a hard hug would park the
  text at the widget's top edge, away from what it annotates. A fraction of the slack is bounded at
  both ends.
- **The `free - padding` term survives as a floor.** It binds when `free < padding / (1 - fraction)`,
  where the bias alone would eat into bar-cap clearance; there it degrades to the old bottom-hug. The
  change is therefore monotone — no device ends up with *less* clearance from the bars than before.

### Landing sequence

Centring shipped first (the literal ask), was seen on device, and the user then asked for a top bias
instead. Both alignments were built and screenshotted; the numbers below are all from the same run
geometry on the emulator:

| rule | stack bounds | gap under header | gap to high label |
|---|---|---|---|
| bottom-hug (before) | 54.7 – 108.3 | 24.4 | ~5.6 |
| centre (intermediate) | 44.0 – 97.6 | 13.8 | 16.2 |
| **25% top bias (now)** | **34.8 – 88.4** | **4.6** | **25.5** |

## Tests

Two synthetic roomy-run tests pinned the old rule and were rewritten:

- `an outer zone hugs the edge away from the bars` →
  `ABOVE splits a roomy run with a top bias while BELOW hugs the edge away from the bars`. Now
  asserts the quarter/three-quarter split at `padding = 0` and `padding = 12`, plus a third case in
  a run tight enough that the `padding` floor takes over.
- `a lifted ceiling does not float the stack up under the header` →
  `a lifted ceiling keeps the stack off the ceiling by a share of the slack`. This one asserted
  bottom-hug as the *mechanism* protecting against header collisions; under a top bias that
  protection is the per-column measured ceiling, so it now pins the guarantee that actually exists
  (a bounded share of the slack) rather than a mechanism that no longer does.

**The whole ink-vs-box test group passed untouched** — the Samsung fold fixture has `free = 10.1`
against `padding = 9.09`, which is inside the regime where the floor binds, so its geometry is
unchanged. That the tight regime is exercised by a real captured fixture rather than only synthetic
input is why the floor is worth having.

Verified: full `:shared` suite, `:desktop:test`, and the Android overlay + `DailyLargeTodayLayout`
Robolectric tests all pass; installed on all three devices and confirmed by emulator screenshot;
desktop distributable rebuilt and restarted (the planner is shared, so Android and desktop moved
together).

Memory: `today-overlay-above-slack-top-bias`; the alignment claims in
`today-overlay-interval-packing`, `today-overlay-header-ceiling-is-per-column` and
`today-overlay-ink-vs-box-fitting` were corrected to point at it.
