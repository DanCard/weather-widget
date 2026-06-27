# Daily rain % label gap above high temp + Android/desktop parity

Date: 2026-06-26

## Problem

On the Samsung daily forecast view, today's `15%` rain-chance label floated with a large
gap above the `75.6°` high-temp label — a gap, not an overlap. A fixed gap constant could
never fix it because the problem was the **anchor**, not the gap.

## Root cause

The rain `%` label computed the high-temp label's top from the **full-size** font metrics
(`highBaseline + ascent`). But `drawTempLabel` shrinks the high label via `fitScaleForWidth`
whenever the temp is too wide for its column — and today's `75.6°` is the worst case (a
*dual* actual+forecast label with a wide decimal, shrunk to scale `0.806`). So the
temperature actually renders ~6 bitmap-px lower than the rain label assumed; multiplied by
the ~4.5× bitmap-to-widget upscale, that became the ~30px floating gap.

logcat trace nailed it: `highTop=36` (assumed) vs `drawnTop=42` (real, at scale 0.806).

## Fix (4 files)

- **`app/.../widget/DailyForecastGraphRenderer.kt`** — extracted `tempLabelDrawScale()` (the
  real fit-to-width scale) and added `resolveHighLabelDrawScale()`. Removed the **dead**
  `RAIN_HIGH_TEMP_GAP_DP` constant (never read).
- **`app/.../widget/DailyForecastRainLabelRenderer.kt`** — anchors to the high label *as
  rendered* by scaling its metrics by `resolveHighLabelDrawScale()`; consumes the shared
  constants.
- **`shared/.../util/DailyRainLabels.kt`** — now the single source of truth for
  `RAIN_HIGH_TEMP_GAP_DP` + the night-tuck constants, documenting the rule:
  **rain bottom = high rendered top − gap** (negative gap = slight overlap).
- **`desktop/.../DailyForecastGraph.kt`** — anchors to its real drawn high-label top
  (`highLabelTopAtCenter`, captured from both dual and single branches) instead of the
  `14f + 8f` fudge, and pulls the same shared constants. Full Android/desktop parity.

## Verification

- Fix confirmed visually on the Samsung (Galaxy Z Fold, `RFCT71FR9NT`).
- Android + desktop + shared all compile; all `DailyForecastGraphRenderer` renderer tests
  pass (including the rain/high overlap tests).
- Final build installed on the Samsung; desktop rebuilt and restarted via `buildStart.sh`.
- Permanent `Log.v` placement trace kept (per the "keep graph-label debug logging"
  preference); all diagnostic scaffolding (green tint, one-shot probes) removed.

## Debugging notes (for next time)

- `ACTION_REFRESH` can take the **UI-only path** (updates current-temp text, keeps the old
  graph bitmap) — the home-screen graph won't repaint. Force a real full re-render by tapping
  a nav arrow (`adb input tap`) or changing data.
- To distinguish the column day-rain `15%` from the header current-conditions `15%`,
  temporarily tint the day label `Color.GREEN`.
- Fold `screencap` prepends a multi-display warning line to the PNG — strip bytes before the
  `\x89PNG` magic before converting to JPG.
