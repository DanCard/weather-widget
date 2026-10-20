# Sim loop pill: placed in the graph's free space, tap target that follows it, review fixes

Follow-up to `plans/261020-precipitation-sim-loop-viewer.md` (commit 59ca84f6).

## Requirement (restated)

The "Sim loop" pill goes in **empty space in the precipitation graph** — wherever that is for the
data being drawn — not at a fixed spot. The first commit read "unused space" as "the upper-right
corner" and hard-coded it.

## What is wrong today (Pixel 7 Pro, precip view, 2026-10-20 screenshot)

- The pill is drawn at bitmap y 6–26 dp, right edge −8 dp: directly under "Silur" and the gear,
  inside `top_right_header_container` (y −10…40 dp). It reads as part of that cluster.
- `sim_loop_touch_zone` is a fixed 80×40 dp box at y **26**–66 dp (`widget_weather.xml`): it
  starts where the pill ends. A tap on the pill itself lands on the API toggle or settings; the
  zone's top 14 dp in turn steals the bottom of those two (declared later in the `FrameLayout`).
- The pill lives in bitmap pixels behind `scaleType="fitCenter"`; the zone lives in dp. Any
  letterboxing moves one and not the other.
- The corner is not always empty: late-window rain % labels ("5%" at 10p) and "Pred …in" sit there.

## Design

### 1. Choose the slot from what was drawn (`:shared`, both platforms)

New `:shared` `SimLoopPillPlacement.choose(graph: RectF, occupied: List<RectF>, pill: SizeF,
reserved: List<RectF>): RectF?`:

- `occupied` = what the renderer actually drew: every placed label rect (from the label placement
  engine), the curve's area, the "Now" line + label, day-boundary labels, hour icons/axis.
- `reserved` = header controls overlapping the graph (API label, gear, center icons; their
  **touch zones**, not just their glyphs) plus an 8 dp gap — so the pill never crowds a button.
- Candidates: a coarse grid of positions across the graph's upper area (above the curve's max
  in that column), scored by clearance to the nearest occupied/reserved rect; prefer the top band,
  then right over left. Return the best rect with clearance ≥ 8 dp, or **null → no pill** (a pill
  on top of data is worse than none; the viewer stays reachable from the desktop and, if wanted,
  a later menu entry).
- Pure function, unit-tested in `:shared` with fixtures: flat 0 % day (top-right free),
  rain peaking at the right edge (moves left), labels everywhere (null).

Desktop `PrecipitationGraph` uses the same function and places its Compose pill at the returned
rect (it is already a real clickable composable, so its hit area is the pill).

### 2. Android: tap zone positioned from the chosen rect

- `PrecipitationGraphRenderer` draws the pill at the chosen rect and returns that rect (bitmap px)
  in its result, as it already returns render logs.
- `PrecipViewHandler` maps bitmap px → widget dp the way the view will (fitCenter scale + offset,
  `graph_view` margins, root padding) — one helper, `GraphBitmapMapping.toViewDp`, unit-tested.
- The zone is sized and placed via `setViewLayoutWidth/Height/Margin` (API 31+, the pattern
  `TemperatureTouchTargets` already uses): pill rect grown to **≥ 48 dp tall / ≥ 64 dp wide**, then
  clipped so it never intersects a header touch zone. Below API 31 the zone cannot move → don't
  draw the pill (minSdk 26; affects few devices).
- Zone moves in `widget_weather.xml` to sit just after `graph_view` (before header/nav zones), so
  if anything ever overlaps, the header buttons win, not the pill.
- Log `SIM_LOOP_PILL rect=… zoneDp=… reason=placed|no_space|api<31`.

### 3. Review fixes from the first commit

| # | Fix |
|---|---|
| a | **Stale frames forever (both platforms)** — Android `RainForecastActivity` and desktop `RainForecastWindow.kt:140` name cached frames `${domain}_${layer}_${lead}.jpg` and reuse any file > 1000 bytes, so after the first view a newer model run is never downloaded and the loop replays the first forecast while looking current. The unused `:shared` `downloadFrame` has the same check. Fix once in `:shared`: key = `${domain}_${layer}_${runKey}_${lead}`, where `runKey` = manifest `run` ?: `init` ?: frame `init` ?: a hash of the frame's file path (never "no key" — a nullable run must not fall back to the old name); on manifest load delete that domain/layer's files from other runs. Test: two manifests with different `run`, same lead → second fetch downloads, first run's file is gone; null `run`/`init` → path-hash key still differs across runs. |
| b | `RainForecastActivity` and `RainForecastWindow` both use `:shared` `WeatherNextForecastClient` (manifest + frames) instead of their own `URL(...)` downloads, so fix (a) lives in one place. |
| c | Third-party host (`ajoros.github.io`): manifest failure → in-viewer error with Retry, never a crash or blank; log `WEATHERNEXT_FETCH outcome=…`. |
| d | Bitmaps decoded with `inSampleSize` to view size; recycled in `onDestroy` / on layer switch. |
| e | "Sim loop" → string resource (19 locales), removed from `HardcodedUserFacingStringTest` allowlist; desktop uses the same text source as its other strings. |
| f | Pill paints cached with the renderer's other paints (no per-render `Paint` allocation). |

Noticed while verifying, out of scope here: the precip header shows "Pred in" with no amount
on the Pixel (left side). Separate plan if it reproduces.

## Tests

- `:shared` `SimLoopPillPlacementTest` — fixtures above, plus "never intersects reserved", "≥ 8 dp
  clearance", "null when nothing fits".
- `GraphBitmapMappingTest` — fitCenter letterbox both axes.
- Robolectric `PrecipTouchRoutingRoboTest`: the zone's bounds contain the pill's mapped rect, and
  are disjoint from `api_touch_zone` / `settings_touch_zone`; zone GONE when placement is null.
- `RainForecastActivityRobolectricTest`: run-keyed cache names; manifest failure shows the error.
- Desktop: placement test on the composable's chosen offset.

## Manual verification

Pixel 7 Pro, Fold (folded + unfolded — different aspect, exercises letterboxing), emulator:
precip view on a dry day and a rainy one; screenshot each; tap the pill centre with
`adb shell input tap` and confirm `RainForecastActivity` opens; tap gear/API right next to it and
confirm they still do their own thing. Desktop: restart via `scripts/buildStart-desktop.sh`, same check.

## Implementation notes (deviations from the design above)

- **Reused `GraphEmptySpaceFinder`** (the station/delta-label search) instead of a new grid; NOW
  line and nav arrows go in as veto rects, the curve's fill as veto columns. The plot already starts
  at `GRAPH_TOP_PADDING_DP` = 44dp, below the header buttons (which end at 40dp).
- **Over-curve fallback** (not in the original design): the Robolectric fixture's 0/20 % every-4-h
  chances leave valleys narrower than the pill, so "null → no pill" hid the button on exactly the
  busy days. Second pass ignores the curve, still never covers a label / NOW / arrows
  (`SimLoopPill.Placement.overCurve`, logged).
- **Zone stays late in `widget_weather.xml`**, not moved before the header: the zoom body zones
  cover the whole plot, so a zone declared before them would never get a tap. Instead the zone is
  clipped to the plot (below the 40dp header line, above the footer) and off the nav arrows.
- **Also fixed:** `RainForecastActivity` defaulted to Mountain View with no location (CLAUDE.md: no
  default location) — now "No location set" and no fetch; it allocated a full output bitmap every
  35 ms tick — now two reused buffers; its frame map was a plain `HashMap` written from IO and read on
  main — now `ConcurrentHashMap`. Frame downloads write `.part` then rename.
- Desktop strings stay inline English like the rest of `:desktop`.

## Verification (2026-10-20)

- `./scripts/unit-tests.sh`: **5605 passed** (1 skipped desktop, 1 skipped long), incl. new
  `SimLoopPillTest` (10), `WeatherNextFrameCacheTest` (3), `SimLoopTouchTargetTest` (4),
  `PrecipTouchRoutingRoboTest` (zone ≥ 48dp, disjoint from `api_touch_zone`/`settings_touch_zone`,
  below the header line, launches the viewer), `RainForecastActivityRobolectricTest` (no-location).
- Emulator (Medium Phone API 36), precip view: pill drawn mid-plot between "Pred in" and
  "Pred .001in", clear of the header (`SIM_LOOP_PILL placed rect=380,110,460,132 bitmap=584x385`,
  `zoneDp=375,94,453,142`). `adb shell input tap` on the pill centre → `RainForecastActivity`
  resumed, loop playing. Cache files `ca_qpf1_imerg_2026102014hr01preds_NNN.jpg`;
  `WEATHERNEXT_FETCH outcome=manifest run=20261020_14hr_01_preds frames=100`.
- Pixel 7 Pro: pill left of centre in open plot (`rect=87,206,201,239 bitmap=517x435`,
  `zoneDp=63,126,140,174`); taps at pill centre and both ends → `RainForecastActivity`; settings
  gear → `SettingsActivity`.
- Fold (inner display, Samsung `hsResizeRatio=0.833` — the launcher scales the whole widget, so the
  zone scales with the pill): pill right side (`rect=477,141,539,160 bitmap=565x398`,
  `zoneDp=542,146,613,184`); centre and both ends → `RainForecastActivity`; gear → Settings.
  (First attempt landed on an expanded quick-settings panel, not the widget.)
- Desktop rebuilt and restarted; its precip pill was not visually checked.
