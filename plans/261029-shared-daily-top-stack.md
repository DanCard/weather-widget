# Shared daily top stack (2026-10-29)

Companion to [plans/261029-shared-daily-bottom-stack.md](261029-shared-daily-bottom-stack.md)
(committed `c91a0368`). That plan moved the geometry under the bars into `:shared`; this one does the
same for the space above them.

## Goal

1. Replace each platform's guessed top reserve with one shared rule, `DailyTopStack`. It sizes the room
   above the bars from the **measured** labels that actually sit there: the high label(s), then the
   day rain %.
2. Add one shared **header padding** knob, set to clear the header by more than today, so labels run into
   the header less often.
3. **Out of scope: per-column header-collision handling.** No header ink measurement, no label dodging
   around header text or buttons. The header is treated as a flat band of known height.

## Evidence

| | Android | Desktop |
|---|---|---|
| Reserve above bars | Fixed `TOP_PADDING_DP = 50dp × labelScale` (`DailyGraphLayoutResolver.kt:94,164`), not measured. The header is drawn **inside** this band. | `top = 2·scale` (`DailyForecastGraph.kt:161`). The header is a separate Compose row above the canvas. |
| Label ceiling | Day rain % top margin `graphTop × 0.2` (`DailyForecastRainLabelRenderer.kt:84,198`) | `-headerBleed = -(14sp×s×1.4 + 4s)`: labels ride up into the header row (`:164,399,509,616,647`) |
| High-label gap above the bar | `HIGH_LABEL_OFFSET_DP = 8dp` (`DailyHighLabelPlanner.kt:37`) | `3·scale` single, `DUAL_NORMAL_GAP = 3` dual (`:507`, `:1171`) |
| Shared already | `DualHighLabel.bottomOffsetsDp` (upper forecast push), `DailyRainLabels.RAIN_HIGH_TEMP_GAP_DP = -3dp`, `RAIN_PEAK_GAP_DP` | same |

- `DailyForecastHeaderRenderer.kt:392` notes the header uses "well under half" of Android's 50dp, so
  part of the band is unused on Android.
- Desktop charges nothing for its top labels: they borrow `headerBleed` (~58 px at scale 2.46) from the
  header row. That's why "71°" sits beside "Thu 29" (see
  [summaries/261029-daily-axis-drawn-temps-only.md](../summaries/261029-daily-axis-drawn-temps-only.md)).
- Both platforms reserve the same amount whatever the data. A week with no rain % and single-label highs
  needs less room than one with a dual high plus a rain % on the hottest day.

## Design

### `shared/src/main/kotlin/com/weatherwidget/shared/graph/DailyTopStack.kt`

```kotlin
data class DailyTopStack(
    val headerBandPx: Float,        // Android: header height drawn in the bitmap; desktop: 0 (header is outside the canvas)
    val headerPaddingPx: Float,     // HEADER_PADDING_DP × pxPerDp — the "increase top padding" knob
    val pxPerDp: Float,
) {
    val labelCeiling: Float          // headerBandPx + headerPaddingPx: no top label goes above this
    /** Room column c needs above its bar top: gap + high label(s) (+ dual push) + rain % (+ peak hour). */
    data class ColumnHeadroom(val barTopFraction: Float, val aboveBarPx: Float)
    /** Graph top that lets every column's labels clear [labelCeiling]; see "Solving graphTop". */
    fun graphTop(graphBottom: Float, columns: List<ColumnHeadroom>): Float
    fun describe(): String
    companion object {
        const val HEADER_PADDING_DP = …   // chosen in step 2; larger than today on both platforms
        const val HIGH_LABEL_GAP_DP = …   // one value for both platforms, chosen in step 2
        fun resolve(…, platform: String): DailyTopStack   // + Log.v, like DailyBottomStack
    }
}
```

Each platform measures its own text and fills in each column's `aboveBarPx`, using a shared helper so
the parts are identical:

- the high-label gap (`HIGH_LABEL_GAP_DP`, or 0 for a past day's label pinned to its bar), plus the
  bar's ink radius
- the high label's height; for a dual high, the upper label including `DualHighLabel`'s push-up
- the day rain % height plus `RAIN_HIGH_TEMP_GAP_DP`, only when that column prints one
- the peak-hour line plus `RAIN_PEAK_GAP_DP`, only when printed

### Solving graphTop

The bars run between `graphTop` and `graphBottom`; `graphBottom` comes from `DailyBottomStack`. Column
c's bar top sits at `y_c = graphBottom − H · f_c`, where `H = graphBottom − graphTop` and `f_c` is the
column's high as a fraction of the axis range (0..1). Requiring `y_c − aboveBarPx_c ≥ labelCeiling` for
every column gives:

```
H = min over c of (graphBottom − labelCeiling − aboveBarPx_c) / f_c      (columns with f_c > 0)
graphTop = graphBottom − H, never above labelCeiling
```

So the column that actually needs the most room sets the height. Most weeks that's the hottest day.
Sometimes it's a slightly cooler day carrying a rain % or a dual label. Room isn't reserved for labels
that aren't drawn. This only scales the axis; it's a per-render calculation that doesn't move labels
sideways or depend on panning beyond the data on screen.

### Platform wiring

- **Android:** `DailyGraphLayoutResolver` replaces `TOP_PADDING_DP` with:
  - `headerBandPx`: the header's measured full-width height, one number, not per column
  - `topStack.graphTop(bottomStack.graphBottom, headroom)`

  `DailyForecastRainLabelRenderer`'s `graphTop × 0.2` top margin becomes `topStack.labelCeiling`.
  `DailyHighLabelPlanner.HIGH_LABEL_OFFSET_DP` becomes `DailyTopStack.HIGH_LABEL_GAP_DP`.
- **Desktop:** `DailyForecastGraph` and `computeDailyGraphTapLayout` replace `top = 2·scale` with the
  solved `graphTop`, with `headerBandPx = 0`. Every `-headerBleed` clamp becomes `topStack.labelCeiling`
  (`:399,509,616,647`), so labels stay inside the canvas below the padding. `headerBleed` and the local
  single/dual gap constants are deleted.
- **One function per platform builds the stack and headroom**, used by both draw and tap layout
  (`desktopTopStack(...)` next to `desktopBottomStack`; Android inside the resolver). The bar `yAt` and
  the tap layout stay in step.

## Expected effect (verify in step 1 before committing to numbers)

- **Desktop:** labels move out of the header row. Charging for them costs bar height, up to about the
  current `headerBleed` (~58 px at the user's window) plus the extra padding. That's partly offset when the
  hottest day has no rain %. This is the trade the user asked about: fewer header collisions for
  somewhat shorter bars.
- **Android:** the 50dp guess becomes header band + padding + the data's real need. Likely taller bars on
  weeks without rain % on hot days, and similar or slightly shorter ones on busy weeks. With larger
  padding, labels stay clear of the header band itself.

## Steps

1. **Diagnostic only, no rendering change.** Add `DailyTopStack` with `describe()`, and on both
   platforms log the solved `graphTop`, the binding column, and the current reserve:
   - Android: `TOP_PADDING_DP`
   - desktop: `top` and `headerBleed`

   Collect numbers on the desktop at the user's window, the emulator, the Pixel 7 Pro and the Fold.
   Report the per-device delta in bar height.
2. **Decision gate with the user:** choose `HEADER_PADDING_DP` and `HIGH_LABEL_GAP_DP` from the step-1
   numbers. Today the gap is 8dp on Android and 3·s on desktop. Show screenshots for at most two
   padding values.
3. `DailyTopStackTest` (`:shared`, Short):
   - the closed-form solve: one column, several columns where a cooler rain-labelled column sets the
     height, all-equal highs (`f_c = 0` for every column), the never-above-`labelCeiling` clamp
   - the headroom helper (single, pinned past, dual with push, rain %, peak hour)
4. Android switch, plus updated sizing tests. Android's pin test (`bottom stack geometry is pinned`)
   keeps `graphBottom`. Its low-label baseline moves only through `graphTop`; update the pinned values
   deliberately and note the change in the test comment.
5. Desktop switch: draw pass and tap layout. A regression test asserts that no top label's box goes
   above `labelCeiling` for a week whose hottest day has a dual high and a rain %. It fails on the old
   `-headerBleed` clamp.
6. Run `./scripts/unit-tests.sh`.

## Runtime verification

1. **Desktop:**
   - Before/after screenshots at the user's window; read the `DailyTopStack` `Log.v`.
   - Check that the hottest day's high and rain % sit below the header row, and that taps still route.
2. **Emulator:**
   - Before/after screenshots of the daily widget, plus a week with rain % on the hottest day. Pan, or
     use a source that has one.
   - Enable the trace with `adb shell setprop log.tag.DailyTopStack VERBOSE`, then reset it.
3. **Pixel 7 Pro and Fold cover:** screenshots, to confirm narrow columns and the Today overlay still fit.
   The Today overlay reads `graphTop`; check that its rows don't shrink.

## Out of scope

- Per-column header ink, label avoidance of header text or buttons, header layout changes.
- The axis's 4% range padding (`rangePad`) at the top or bottom.
- Text, icon and header sizes.

## Docs

- No living doc describes the top reserve today. If step 2 changes the visible spacing, add a short
  note to `arch/` next to wherever daily layout is described, naming `DailyTopStack` and
  `DailyBottomStack` as the owners of vertical daily geometry.

## Decisions (2026-10-29, after step 1)

- **Overlap, not padding:** top labels may rise into the header by `HEADER_OVERLAP_FRACTION = 0.10` of
  the header's height (user: "slight = allow 10% overlap into the header. A guess is fine, I can tweak
  it later"). `labelCeiling = headerBottom − 0.10 × headerHeight`.
  - Android: header = the full-width header ink (`resolveHeaderInkBottom(…, 0, widthPx)`), top at 0.
  - Desktop: header = the measured `WidgetHeader` row, ending 4dp (the spacer) above the canvas.
- **Needs come from a measuring pass, not a hand-written formula.** Each platform lays the bars and
  top labels out once on the provisional layout and reports, per column, how far the topmost high or
  day rain label reaches above the tallest drawn bar. That can't drift from the real label rules (dual
  highs, pushes, snapshot-anchored rain %).
  - Android: a draw-dropping `MeasureOnlyCanvas`, then `resolve(graphTopPx, labelCeilingPx)`.
  - Desktop: the draw body runs once onto a 1x1 `CanvasDrawScope` (stopping before the Today overlay),
    then for real; taps reuse the drawn graph top.
- **High-label gaps unchanged** (Android 8dp, desktop 3·scale); unifying them is left for later.
- Step-1 comparison logging was replaced by one `DailyTopStack` `Log.v` per render (ceiling, graph top,
  binding column, per-column needs).
