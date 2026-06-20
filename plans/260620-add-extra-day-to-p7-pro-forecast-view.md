# Make the "extra day" on the daily forecast view intentional

## Context

On the Pixel 7 Pro, the daily forecast view now fits one more day than it used to. This
is **not a bug** — it's an emergent side-effect of two things lining up:

1. **The column formula is biased to round up.** `WidgetSizeCalculator.getWidgetSize()`
   computes `cols = ((width + 15) / 70).roundToInt()`. The `+15` fudge plus round-to-nearest
   (not floor) means a widget only ~half a cell wider than a threshold gains another column.
   Days displayed always equal columns (`NavigationUtils.getDayOffsets()`).
2. **There is now data to fill that column.** The recent `Extend Open-Meteo forecast to
   8-day baseline` commit (`ForecastHorizon.BASELINE_DAYS = 8`) means the extra column —
   which was previously computed but rendered empty — now shows a real forecast day.

The Pixel 7 Pro's launcher reports a `minWidth` dp that happens to land inside that round-up
window. The formula itself is device-agnostic; only the launcher-reported width differs.

**Goal (per user):** keep the extra day, but make it a *deliberate, documented, tested*
design choice instead of an accidental-looking magic number — and confirm the narrower
per-day bars stay legible.

## Approach

### 1. Verify on-device (Pixel 7 Pro = `2A191FDH300PPW`)
Confirm the real width→cols values and that the extra day renders legibly, per the project's
widget-debugging workflow (logs + screenshot, JPG not PNG):

```bash
adb -s 2A191FDH300PPW logcat -c
# trigger a redraw (normal update path):
adb -s 2A191FDH300PPW shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE \
  -n com.weatherwidget/.widget.WeatherWidgetProvider
adb -s 2A191FDH300PPW logcat -d | grep WidgetSizeCalculator   # reads "<widthDp>dp×<heightDp>dp"
adb -s 2A191FDH300PPW exec-out screencap -p > /tmp/p7.png && convert /tmp/p7.png /tmp/p7.jpg
```
Read `/tmp/p7.jpg` to confirm the extra-day bar + label are legible. Record the exact
`widthDp` (and resulting `cols`) — it feeds the regression test below. (Note: `getWidgetSize`
itself doesn't log `cols` today; the bitmap-size log line gives `widthDp`, from which `cols`
is derivable, or add a one-line temporary log of `cols` during verification.)

### 2. Codify the intent — `WidgetSizeCalculator.kt`
File: `app/src/main/java/com/weatherwidget/widget/handlers/WidgetSizeCalculator.kt:54-57`

Replace the bare `+15` / `+25` literals with named, documented constants so the round-up
bias reads as a decision, **not** a guess. No change to the computed values (behavior is
preserved — we're keeping the extra day):

```kotlin
// We intentionally bias toward fitting one more day: a widget within ~half a cell of the
// next column rounds UP (roundToInt, not floor). This is safe because the daily view always
// has data to fill it — ForecastHorizon.BASELINE_DAYS (8) covers the widest practical widget,
// with on-demand extension to MAX_DAYS (16). See plan: pixel-7-pro-it-replicated-stardust.
private const val COLUMN_FIT_BIAS_DP = 15
private const val ROW_FIT_BIAS_DP = 25
...
val cols = ((width + COLUMN_FIT_BIAS_DP).toFloat() / CELL_WIDTH_DP).roundToInt().coerceAtLeast(1)
val rows = ((height + ROW_FIT_BIAS_DP).toFloat() / CELL_HEIGHT_DP).roundToInt().coerceAtLeast(1)
```

### 3. Legibility guard (verify; change only if screenshot shows crowding)
The renderer already degrades gracefully at narrower per-day widths
(`DailyForecastGraphRenderer.kt:446-468`): temp fonts hold at base via
`widthScaleFactor.coerceIn(1.0f, 1.2f)`, while day labels shrink to fit through
`resolveDayLabelLayout(maxTextWidthPx = dayWidth - gap)` down to `MIN_DYNAMIC_DAY_LABEL_SCALE`.
If `/tmp/p7.jpg` shows truncation or crowding, lower `MIN_DYNAMIC_DAY_LABEL_SCALE` /
`computeDayLabelWidthScale` slightly — otherwise leave it untouched.

### 4. Lock it in with a test
The width→cols formula currently has **no direct test** (only bitmap helpers are covered).
Add one to `app/src/test/java/com/weatherwidget/widget/handlers/WidgetSizeCalculatorRoboTest.kt`
(Robolectric — `getWidgetSize` needs `Context`/`AppWidgetManager`). Drive `getWidgetSize` via a
fake `AppWidgetManager` options bundle (`OPTION_APPWIDGET_MIN_WIDTH` etc.) and assert `cols` for
representative widths, **including the measured Pixel 7 Pro width that yields the extra day**, plus
the threshold neighbors (e.g. just-below vs just-above a column boundary) so the round-up bias is
pinned and can't silently regress.

## Critical files
- `app/.../widget/handlers/WidgetSizeCalculator.kt` — the `+15`/`+25` → named constants (the core change)
- `app/.../widget/handlers/WidgetSizeCalculatorRoboTest.kt` — new direct width→cols test
- `shared/.../util/NavigationUtils.kt` — reference only (`getDayOffsets`: days == cols)
- `app/.../widget/DailyForecastGraphRenderer.kt` — reference only (legibility scaling)
- `shared/.../config/ForecastHorizon.kt` — reference only (data that backs the extra day)

## Verification
- `./gradlew testDebugUnitTest --tests "*WidgetSizeCalculatorRoboTest*"` — new formula test green.
- `./gradlew testDebugUnitTest --tests "*NavigationUtilsTest*"` — day-offset logic unchanged.
- On Pixel 7 Pro (`2A191FDH300PPW`): `./gradlew installDebug`, then screenshot the daily widget
  and confirm the extra day renders with a legible bar + label.
- (Optional) Spot-check the Z Fold (`RFCT71FR9NT`) renders an internally-consistent day count
  for its reported width — confirming the behavior is uniform, not Pixel-specific.
