# Today-column overlay: all rows now fit above the column

**Date:** 2026-08-07
**Plan:** `plans/260807-today-overlay-graph-edge-inset-opus.md`
**Files:** `TodayColumnOverlayPlanner.kt` (shared), `TodayColumnOverlayRenderer.kt` (app),
`DailyForecastGraph.kt` (desktop), `TodayColumnOverlayPlannerLayoutTest.kt`

## Problem

In the large daily view the Today-column overlay split across zones: `+0.0 fcst` rendered above the
column while `58.7°` / `0m` rendered **across the forecast bars** — on the emulator, on the Samsung
fold, and on desktop. Visibly there was room above for all three rows.

There was. The stack missed by **1.91 px on the emulator and 1.60 px on the Samsung fold** — 2% of an
81–94 px stack. Captured live from `adb logcat -s TodayColumnOverlay:V`, the emulator case:

| quantity | px |
|---|---|
| ABOVE band start (`graphTop + padding` = 51.20 + 7.875) | 59.07 |
| today's own `74.3°` high label caps the free run at | 138.23 |
| largest free run | **79.16** |
| stack (26.15 + 1.31 spacing + 53.61) | **81.07** |
| deficit | **1.91** |

`TodayColumnOverlayPlanner` was behaving as designed — it correctly fell through its cost ladder to
the ABOVE + ON_COLUMN split added in `e688223e`. The **input geometry and the fit test** were wrong.

## Three independent causes

### 1. A redundant inset at the graph edge

`Input.padding` conflated two unrelated roles: inset from the graph's outer edge
(`graphTop`/`graphBottom`) and clearance from the bar cap. Role 1 is redundant —
`DailyGraphLayoutResolver.kt:184` already sets `graphTop = TOP_PADDING_DP (50dp) × labelScale ×
density`, so the entire header band is *already* excluded. Insetting a further 7.875 px below it
bought nothing and was exactly what cost the fit.

Split into `Input.edgeInset` (a **final** constructor param defaulting to `padding`, so the
positional call sites in the existing tests keep their meaning); both renderers pass `0f`. Role 2
keeps its 3dp. This alone fixed the emulator: run 79.16 → 87.04 against an 81.07 stack.

### 2. Fit was decided on font boxes, not ink — the actual defect

The Samsung was still 1.60 px short, yet the rendered gap between `0m`'s ink and the `74.4°` label's
ink measured **27 device px ≈ 10.5 bitmap px of visible whitespace**.

`DailyTemperatureLabelRenderer.kt:48-53` returns the high label's bounds as `fontAscent`..
`fontDescent` — the full font **box**. The overlay measured itself the same way
(`TodayColumnOverlayRenderer.kt:78`). Where the two met, both contributed blank leading: ~6 px of
empty ascent above `74.4°`, plus ~6 px of unused descent below `0m` (which has no descenders at all).
**~12 px of visibly empty space counted as solid**, against a 1.6 px deficit.

Fix: `Line.topLeading` / `Line.bottomLeading`, measured by the renderer via
`Paint.getTextBounds` against font metrics (`TodayColumnOverlayRenderer.inkLeading`). The planner's
new `inkHeight()` trims **only the stack's outer edges** — interior leading is real inter-row
spacing, and trimming it would visually close the blocks up. Layout still advances by full box
height, so row rhythm is unchanged; the blank leading simply hangs outside the run.

`inkLeading` returns `0f` when the platform reports no ink bounds. Robolectric has no font engine and
yields an empty rect there; over-trimming would silently move text rather than fail a test.

### 3. Centring wasted the slack

`layOut` centred the stack in its run, so spare room split evenly instead of becoming distance from
the bars. Outer zones now hug the edge furthest from the bars — `ABOVE` → top, `BELOW` → bottom;
`ON_COLUMN` has bars on both sides and still centres.

## Raising the ceiling

Even fitting, the stack sat only ~5 px higher — the run had little slack to redistribute. Added
`Input.aboveCeiling` (defaults to `graphTop + edgeInset`), letting the ABOVE zone rise into the part
of the reserved header band the header does not draw in. Android passes
`graphTop × (1 − HEADER_BAND_RECLAIM_FRACTION)`; `graphTop` *is* the band's height, so this scales
correctly at any density and bitmap scale.

Measured on the Samsung fold, header ink stops at bitmap y 22.4 against a `graphTop` of 51.6, so up
to ~0.55 is physically free. Reclaiming `0.5` cleared the label but read as too high against the
header; settled on **`0.25`** by eye.

Deliberately *not* `TOP_PADDING_DP = 50f → 30f`: that also stretches the temperature scale, so
today's bar and its high label rise with it and the net gain is ~8 px rather than ~20 — while
changing the daily graph on every widget size and device.

## Tests

`TodayColumnOverlayPlannerLayoutTest` grew to 37 tests, including two **paired controls** that assert
the *old* behaviour still reproduces the bug, so the positive tests cannot pass vacuously:

- `the graph-edge inset is what cost the fit` — restoring the old ceiling must still split.
- `box packing is what rejected the Samsung stack` — zeroing the leading must still split.

That second control earned its keep immediately: it failed because the fixture carried only the high
label as an obstacle, leaving `BELOW` spuriously free. Both device fixtures now use the full
four-obstacle list (high label, icon, low label, day label) captured from the log.

Also covered: only outer leading is trimmed (blocks stay exactly `rowSpacing` apart); the trimmed
ascent hangs above the run start rather than shifting ink into obstacles; `edgeInset` defaults to
`padding`; outer zones hug the far edge in both directions.

`DailyLargeTodayLayoutRoboTest` — the canary that once passed *because of* a workaround — still
passes, as do `TodayOverlaySettingsRoboTest` and `TodayColumnOverlayPlannerTest`.

## Verified on device

Emulator and Samsung fold both log
`placements=[delta:ABOVE, dominant_temp_age:ABOVE]` at the exact geometry that previously split, with
all three rows rendered above the column.

## Not done

Desktop still packs boxes. Compose exposes no glyph ink bounds equivalent to
`Paint.getTextBounds`, so `topLeading`/`bottomLeading` default to `0` in `DailyForecastGraph` and its
split-expecting test still passes. Desktop also does not reclaim any header band. Desktop *does* get
causes 1 and 3 (`edgeInset = 0f` and the zone-hugging alignment) via the shared planner.
