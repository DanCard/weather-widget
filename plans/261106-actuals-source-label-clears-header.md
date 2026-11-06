# "Actual temperature data from X" label: clear the header, shrink, wrap, or omit

## User prompt

> Pixel 7 pro: hourly temperature graph: "Actual temperature data from NWS" Suppose to find empty
> space to put that line. Overlays header.
> 1) Can shrink font if not easy to find empty space
> 2) Can line wrap if it makes it easier to find empty space.
> 3) Omit if can't find empty space.

## Evidence

1. Pixel 7 Pro screenshot (2026-11-06 14:31): the pink label is drawn on the bitmap's top row, under
   the RemoteViews header (current temp `71.2°`, delta `-1.4`, home/thermometer/cloud/graph buttons).
2. `TemperatureGraphAnnotationRenderer.placeActualsSourceLabel` searches `input.labelArea()` =
   `GraphLabelArea.rect(width, graphBottom)`, whose top is `GraphLabelArea.TOP = 0f` (the bitmap edge).
   The comment on `labelArea()` still says "the header row over that strip is in graphObstacles" —
   no longer true.
3. Commit `da576280` (2026-10-27, "Do not check for collision with header row in graphs") removed the
   `HEADER` obstacles for **every** label because they suppressed a ghost-line peak label (67.8°).
   Since then nothing keeps a free-floating label out of the header strip, and the empty-space finder
   happily picks the clear band at y≈0.
4. Shrinking already exists: `DominantStationLabel.placeWithFontFallback` tries scales
   `1.0, 0.8, 0.6`, then returns null (omit). Line wrapping does not exist.

## Root cause

The actuals-source label's search area starts at the bitmap top, which on Android lies under the
header row. With no header obstacle, the top strip always looks empty, so the label lands there.

## Proposed fix (scoped to the actuals-source label only)

The Oct 27 decision stays for value labels, ghost labels, delta and dominant-station labels — only
this lowest-priority annotation gets a ceiling.

1. **Header ceiling (Android)** — `TemperatureHeaderFit` regains a small vertical-only helper
   `headerBandBottomPx(...)`: header row centre (5dp) + half the tallest header item, × density ×
   `bitmapScale` (the same formula removed in `da576280`, minus the per-item horizontal spans — the
   label is far wider than any gap between header items). `TemperatureStateResolver` passes it to the
   renderer as `actualsSourceTopPx`; `placeActualsSourceLabel` searches
   `GraphRect(0, actualsSourceTopPx, width, graphBottom)`. Desktop's header is a row above the canvas,
   so its top stays 0.
2. **Line wrap (`:shared`)** — `DominantStationLabel.placeWithFontFallback` gains a wrap fallback:
   - order: one line at 1.0 → 0.8 → 0.6, then two lines at 1.0 → 0.8 → 0.6, else null (omit);
   - wrap splits at the word boundary that best balances the two lines
     ("Actual temperature" / "data from NWS"), keeping segment parts (prefix paint vs. source paint);
   - the block's metrics = max line width, height = 2 lines + line gap; returns `lines` + per-line
     baselines in `ScaledPlacement`.
   Both Android (`placeActualsSourceLabel`) and desktop (`TemperatureGraph.kt`) draw from the returned
   lines. The cloud-cover graph callers keep single-line behaviour (wrap is opt-in via a parameter)
   unless you want it there too.
3. Logging: `ActualsSourceDiag` gains `topPx`, `lines`, and the reason stays `no_empty_band` when
   omitted.
4. Docs: `arch/ARCHITECTURE.md` line 143 — note the one exception (actuals-source label clears the
   header band; shrinks, wraps, else omitted). Fix the stale `labelArea()` comment.

## Tests

1. `:shared` `DominantStationLabelTest`: wrap split balances lines and preserves segment parts; wrap
   used only when every single-line scale fails; null when two lines also fail; a ceiling (plot top)
   is respected.
2. `:app` Robolectric `TemperatureActualsSourceLabelRoboTest`: with `actualsSourceTopPx` set, the
   placed box top ≥ it; a crowded plot yields a 2-line placement; an impossible plot omits it.
   Must fail on current code (box top = 0).
3. Desktop test for wrapped draw parity.
4. Device: install on Pixel 7 Pro, screenshot, and `ActualsSourceDiag` from logcat.

## Out of scope

- Cloud-cover graph's identical label (same header overlay likely applies) — ask before changing.
- Restoring header obstacles for any other label.
