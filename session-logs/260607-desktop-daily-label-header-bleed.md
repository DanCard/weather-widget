# Session Log: Desktop Daily-View Labels Bleed Into Header (2026-06-07)

## Overview
This session fixed a visual bug in the Compose Desktop daily forecast view where
the hottest day's high-temperature label (e.g. Thursday "89°") was drawn
*overlapping its own vertical bar* instead of sitting above it like every other
day's label. Investigation traced it to a hard top-edge clamp on the label
position. Rather than reserve space to push the bars down, the user reframed the
desired behavior: they *like* it when the temp/rain labels nudge up into the
header row a little. The fix turned the bug into an intentional, occasional
"header bleed" — the same one-line relaxation both lifts the label off the bar
and produces the playful overlap.

## User Prompts
1. "Desktop: daily view forecast: Thursday 89 degree label not drawn on top of
   vertical bar. I suspect this is because of overlap with header row? If that is
   true, disregard overlap with header row."
2. "Label is not suppressed. It is drawn overlapping vertical bar. I want it
   drawn on top instead like all the other labels. Feel free to add logging if
   that helps."
3. "Is it possible that rain or temp labels overlap the header row at least a
   little? I think it is fun when it does."
4. "Looks great! Thanks! Write a session log to session-logs/ dir."

## Root Cause
In `desktop/.../DailyForecastGraph.kt`, the graph canvas deliberately uses
`top = 2f * scale` so the hottest bar runs to the very top of the band. For that
hottest day, `yAt(rawMax)` lands only a few pixels below the canvas top, so the
high-label position `highY - textHeight - 3f` computes to a negative Y. The
`.coerceAtLeast(0f)` clamp then pinned the label back down to Y=0 — directly on
top of the bar. Every other day's bar starts lower, leaving room, so only the
single tallest bar collided.

## Key Finding (why the fix is trivial)
The popup layout (`Main.kt`) is a `Column`: `WidgetHeader`, `Spacer(4.dp)`, then
the daily graph `BoxWithConstraints(weight 1f)` → `DailyForecastGraph` Canvas.

- Compose does **not** clip a `Canvas`/`drawBehind` to its layout bounds by
  default; overflow is allowed unless an ancestor opts in (`clipToBounds`,
  `Modifier.clip`, `Surface`, scroll).
- The only clipping ancestor is the window-level `Surface`, which clips at the
  window edge — not at the graph's top.
- The graph is the `Column`'s **last** child, so its overflow paints *on top of*
  the header.

Therefore a label drawn at a slightly-negative Y naturally renders up over the
4dp spacer and into the header band — visible and on top. The original bug fix
and the requested "fun overlap" are the same change: stop clamping at the canvas
top and let labels ride up as far as they need.

## Changes (`desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`)
1. **Added a `headerBleed` allowance** next to the geometry constants:
   `val headerBleed = 12f * scale * 1.4f + 4f * scale` (~high-label height + gap)
   — the budget for how far labels may overflow above the canvas top.
2. **High-temp label clamp**: `coerceAtLeast(0f)` → `coerceAtLeast(-headerBleed)`.
   The hottest day's label now clears its bar and pokes into the header; all
   other days are unaffected (their natural Y was already positive).
3. **Rain label clamp**: relaxed from `coerceAtLeast(2f)` to
   `coerceAtLeast(-headerBleed - rainHeight - 2f * scale)` so when both ride up,
   the rain label stays above the temp label.
4. Updated the surrounding comments to document that the overflow into the header
   is by design.

## Design Notes / Accepted Trade-offs
- Overlap only occurs for the tallest bar(s); short bars keep labels well inside
  the canvas — so the bleed is occasional and small, as requested.
- If the hottest day happens to be the center column, its label may brush the
  centered date in the daily header. Accepted as fun/edge-case imperfection,
  consistent with the user's best-effort preference.
- `headerBleed` is a single tunable constant if the poke ever feels too big/small.
- Kept `top = 2f * scale` — bars still fill the full height.

## Verification
- `scripts/buildStart.sh`: **BUILD SUCCESSFUL**, distributable rebuilt,
  incumbent instance stopped via `.quit` trigger, new instance started through the
  autostart launcher. Only compiler output was a pre-existing `painterResource`
  deprecation warning, unrelated to this change.
- Visual confirmation by the user on the running desktop app: the "89°" label now
  sits above its bar and nudges into the header — "Looks great!"
- No automated tests added: this is a visual-only `DrawScope` renderer with no
  unit-test coverage; verification is by eye on the live app.

## Files Touched
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt`
- `plans/` plan recorded at the user's plan path
  (`desktop-daily-view-forecast-magical-cupcake.md`)
