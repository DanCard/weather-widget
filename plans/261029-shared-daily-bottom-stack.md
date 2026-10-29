# Shared daily bottom stack (2026-10-29)

Follows [summaries/261029-daily-axis-drawn-temps-only.md](../summaries/261029-daily-axis-drawn-temps-only.md),
which fixed the top of the axis. This plan fixes the bottom.

## Goal

Make the desktop daily bars longer at the same window size, with **no change to text or icon sizes**.
Do it by moving the "space under the bars" geometry (bar → icon → low label → day name) into one shared
`:shared` type that both platforms use. Android should render pixel-for-pixel the same.

## Evidence

- At 894×835 px on the desktop (scale 2.65), the coldest column (Wed, 45.8°) has its low label ending at
  y≈705, while the day names start at y≈757. About 50 px under the coldest bar is unused.
- The desktop reserve uses rough allowances instead of measured sizes (`DailyForecastGraph.kt:141–148`):
  `lowLabelBand = 14sp×s×1.4 + 4s`, `dayLabelBand = 11sp×s×1.5 + 6s`, plus another `+ 6s`. It also leaves
  out the 4s bar→icon and 2s icon→label gaps that the draw loop actually uses. So the reserve doesn't
  match what's drawn.
- Android already reserves measured sizes (`DailyGraphLayoutResolver.kt:188–196`):
  `graphBottom = height − dayLabelHeight − (tempLabelHeight + iconSize + 4dp)`.
- The same geometry exists in five places with slightly different numbers:

  | Piece | Android | Desktop |
  |---|---|---|
  | Bottom reserve / `graphBottom` | `DailyGraphLayoutResolver.kt:188–196` | draw `:141–148`; tap layout `:1054–1058` |
  | Tap strip height | — | `dailyGraphBottomStripHeightPx` `:1004–1014` |
  | Icon top under a bar | `DailyColumnRenderer.kt:72`, `:178` (`lowY + 3dp`, no clamp) | `yAt(anchorLow) + 4s`, clamped by `iconTopMax` at `:219`, `:537`, `:1073` |
  | Low label position | baseline `iconY + iconSize + tempLabelHeight − 1dp` (`:84`, `:180`) | top `iconTop + iconSize + 2s` (`:221`, `:546`) |
  | Day name row | `dayLabelTextSize × DAY_LABEL_SIZE_MULTIPLIER`, margin `DAY_LABEL_BOTTOM_MARGIN_DP` | `size.height − dayText.height − 6s`; band `11sp×s×1.5 + 6s` |

## Design

### `shared/src/main/kotlin/com/weatherwidget/shared/graph/DailyBottomStack.kt`

A value class built once per layout pass from **measured** inputs. Each platform measures its own text;
nothing in `:shared` touches fonts.

```kotlin
data class DailyBottomStack(
    val canvasHeightPx: Float,
    val pxPerDp: Float,              // platform dp→px including the view's scale
    val iconSizePx: Float,
    val lowLabelHeightPx: Float,     // tallest low label (Android: tempLabelHeight)
    val dayLabelHeightPx: Float,     // measured day-name row height
    val dayLabelBottomMarginPx: Float,
) {
    val dayLabelTop: Float           // canvasHeight − margin − dayLabelHeight
    val graphBottom: Float           // lowest bar end = dayLabelTop − stackHeight, floored at 40% of height
    val stackHeight: Float           // barGap + icon + iconLowGap + lowLabelHeight
    val tapStripHeight: Float        // canvasHeight − graphBottom (desktop BOTTOM_ICON zone)
    fun iconTop(barBottomY: Float): Float      // barBottomY + barGap, clamped so the stack clears dayLabelTop
    fun lowLabelTop(iconTop: Float): Float     // iconTop + icon + iconLowGap
    fun lowLabelBottom(iconTop: Float): Float
    companion object {
        const val BAR_ICON_GAP_DP = 3f       // Android's value today
        const val ICON_LOW_GAP_DP = 0f       // Android's effective value; see "Android parity"
    }
}
```

- **One rule for both platforms:** gaps are shared constants. Only the measured sizes and the icon size
  stay platform-specific (Android 36dp × labelScale, desktop 30dp × scale, capped at 0.6 × column width).
- **The clamp becomes shared behavior.** Today Android doesn't clamp the icon above the day names. It
  doesn't need to, because `graphBottom` already leaves room. With a shared clamp, Android stays
  correct even if a future change makes the two disagree.
- **`Log.v` for the stack** (canvas height, measured sizes, `graphBottom`, `dayLabelTop`), written from
  `:shared` so both platforms log the same fields. Desktop's `axisRange` line keeps its own field.

### Android parity

Choose the shared constants so Android's output doesn't change:

- `iconTop = barBottomY + 3dp` (unchanged).
- Low label: Android places a baseline at `iconY + iconSize + tempLabelHeight − 1dp`. Express this as
  `lowLabelBottom(iconTop) + TEMP_LABEL_SPACING_DP`, so the `−1dp` baseline offset stays in Android's
  text-drawing code where it belongs.
- `graphBottom`: Android reserves `+4dp` today, while the stack itself uses 3dp + 0dp + label height.
  Keep the extra 1dp as the bottom margin inside `stackHeight`, so `graphBottom` stays identical.
  A sizing test pins the exact value before the change.

### Desktop adoption

- The draw pass, `resolveLowLabelY` and `computeDailyGraphTapLayout` all build the same `DailyBottomStack`
  with a small `desktopBottomStack(canvas, scale, density, measure)` helper. That removes the duplicated
  reserve math and `dailyGraphBottomStripHeightPx`.
- Desktop gap changes, about 1–2 px each at scale 2.65:
  - bar→icon: 4s → 3dp×s
  - icon→low label: 2s → shared value
- Expected gain at the user's window: graphBottom moves down by ~25–50 px. The arithmetic says about
  24 px; the screenshot shows ~50 px of slack. The new `Log.v` reports the real number. Bars get roughly
  5–10% longer.
- `dayLabelBand` is also used by the night rain labels (`:669`, `:710`), the Today overlay
  (`dayLabelBandPx`, `:238`) and `graphBottom` for the label planner (`:750`). All of these read
  `stack.dayLabelTop`, so the day-name row has a single definition.

## Steps

1. Add `DailyBottomStack` and `DailyBottomStackTest` (`:shared`, Short). Cover: `graphBottom` math, the
   clamp when the bar runs to `graphBottom`, the 40% floor, and the low-label top/bottom.
2. **Pin Android first.** Before switching Android over, add assertions to
   `DailyForecastGraphRendererSizingTest` for the current `graphBottom`, icon top and low-label baseline
   at two sizes (a phone widget and the Fold cover). They must pass both before and after the switch.
3. Switch Android: `DailyGraphLayoutResolver` and `DailyColumnRenderer` use the stack; delete
   `ICON_BELOW_BAR_SPACING_DP`/`ICON_STACK_SPACING_DP`.
4. Switch desktop: draw pass, `resolveLowLabelY`, tap layout. Delete the rough allowances and
   `dailyGraphBottomStripHeightPx`.
5. **Desktop regression test** (`DailyForecastGraphTapZoneTest`): with the same measure lambda as the draw
   pass, `computeDailyGraphTapLayout`'s `graphBottom` equals the stack's. The coldest column's low-label
   bottom must sit within 2 px of `dayLabelTop`. This fails on the old reserve, which left ~24+ px of gap.
6. Keep the existing tap-zone tests green. They pin icon routing through the strip.
7. Run `./scripts/unit-tests.sh`.

## Runtime verification

1. **Desktop:**
   - Rebuild with `createDistributable` and relaunch with `scripts/buildStart-desktop.sh`.
   - Take before/after screenshots at the user's window size; read `axisRange` and the stack `Log.v`.
   - Check that the coldest column's icon, low label and day name don't overlap, and that taps on the
     icon/strip still open the day.
2. **Android, on the emulator:**
   - Before/after screenshots of the daily widget. They must be pixel-identical (diff with ImageMagick
     `compare -metric AE`); any difference blocks the change.
   - Also check the Pixel 7 Pro.
3. **Check one cold-day case on desktop:** pan to a day whose low is the minimum and has a night rain
   label, to see that the label still fits between the low label and the day name.

## Out of scope

- No change to text sizes, icon sizes, header bleed or `uiScale`.
- The `71°` label crowding "Thu 29" in the header (noted in the previous summary) is a separate issue.

## Docs

- If a living architecture doc describes daily layout reserves, add a line pointing to `DailyBottomStack`
  as the single owner. None mentions it today, so this is likely a no-op.
