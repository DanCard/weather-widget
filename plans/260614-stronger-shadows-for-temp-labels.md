# Daily-view history label shadow: thin outline, history-only

## Context
The daily forecast view's temperature labels need to read more strongly **only on history (past)
days**, on both Android and desktop. Earlier attempts overshot: a heavy black outline
(`0.32 × fontSize`) was added to *every* daily label (commit `bead92c9`) — too strong and it bled
onto today/future. That outline was reverted on Android, leaving a soft 2.5dp blur the user finds
**too weak**. Desktop currently shows an equally-weak blur.

User decisions for this change:
- **Technique:** thin crisp black outline (much thinner than the old 0.32 — start ~`0.12 × fontSize`), tune visually.
- **Scope:** history (past) labels only. **Remove** the shadow from today/future daily labels entirely.
- **Hourly temp graph:** leave as-is (keeps its soft blur).

Outcome: past-day high/low/dual-high labels get a crisp, legible thin outline; today/future daily
labels have no shadow; hourly graph unchanged.

## Android — `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
1. Add a thin outline constant, e.g. `private const val LABEL_OUTLINE_STROKE_FRACTION = 0.12f` (tunable).
2. `drawTempLabel(...)` (~line 836): add a `drawOutline: Boolean = false` param. When true, draw a
   black `Paint.Style.STROKE` pass (`strokeWidth = paint.textSize * LABEL_OUTLINE_STROKE_FRACTION`,
   `clearShadowLayer()`) *before* the fill `drawText`. This mirrors the reverted code but thinner and
   gated. When false, just the fill (current behavior).
3. Pass `drawOutline = day.isPast` at the history-capable call sites: the low label (~685-689) and the
   high block (~794-826), including the dual actual/forecast high labels (~818-821, which already only
   fire for past days). Today/future keep the default `false`.
4. Remove the blur from the daily temp paints so today/future have no shadow and history relies on the
   outline: drop `shadowRadius`/`shadowDy` from `tempTextPaint`, `todayTempTextPaint`, **and**
   `pastTempTextPaint` `createTextPaint(...)` calls (~555-557). Keep the `shadowRadius`/`shadowDy`
   vals only if `rainTextPaint` still uses them.
   - **Open item:** `rainTextPaint` currently has the blur and rain labels appear on today/future. Out
     of the stated scope (temp labels); leave as-is for now unless the user wants rain shadow removed too.

## Desktop — `DesktopGraphUtils.kt` + `DailyForecastGraph.kt`
Two techniques now coexist: keep the blur helper for hourly, add a thin-outline helper for daily history.
1. `DesktopGraphUtils.kt`: reintroduce a thin outline helper (the soft-blur `drawShadowedText` +
   `tempLabelShadow` stay for hourly). Add `internal fun DrawScope.drawOutlinedText(measurer, text,
   fontSize, real, topLeft, fontWeight=null)` that re-measures `real`'s style with
   `drawStyle = Stroke(width = fontSize * OUTLINE_STROKE_FRACTION)` + black, draws outline then fill.
   Reuse a thin `private const val OUTLINE_STROKE_FRACTION = 0.12f` (matches Android).
2. `DailyForecastGraph.kt` call sites (currently all call `drawShadowedText(..., scale)`):
   - Dual highs `aLayout`/`fLayout` (~183/189, past-only): → `drawOutlinedText` (pass the existing
     `aText`/`aSize`, `fText`/`fSize`).
   - Single high `highText` (~207): `if (day.isPast) drawOutlinedText(...) else drawText(highText, topLeft)`
     (today/future no shadow).
   - Low `lowText` (~221): same `day.isPast ? drawOutlinedText : drawText` split.
3. Hourly (`TemperatureGraph.kt`): no change — stays on `drawShadowedText` (blur).

## Also: fix stale color test (regression from the OBSERVED lightening)
`app/src/test/java/com/weatherwidget/widget/DailyGapFallbackGraphIntegrationTest.kt` ~line 188 asserts
`assertEquals(-52378, todayBar.color)` — `-52378` is the OLD `#FF3366`. Commit `bead92c9` lightened
`WeatherConditionColors.OBSERVED` to `#FF7799` (now `-34919`) and pointed `COLOR_OBSERVED_RED` at it,
so the today actual bar is the new color. Fix robustly by asserting against the constant rather than a
magic int: `assertEquals(WeatherConditionColors.OBSERVED, todayBar.color)` (add the import). This makes
the test survive future OBSERVED tweaks. (The test name says "yellow…orange" but it only checks the
today actual bar color; leave the name unless we add snapshot-bar assertions.)

## Reuse notes
- Android `drawTempLabel` and the per-day `day.isPast`/`day.isToday` paint selection already exist —
  only a flag + outline pass is added.
- Desktop daily call sites already have `day.isPast`/`day.isToday` in scope (used for label color).
- The thin-outline mechanism is the same one reverted from `bead92c9`, just thinner and gated.

## Verification
1. Build + install Android: `./gradlew installDebug`; refresh widget:
   `adb -s emulator-5554 shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider`.
   Screenshot per CLAUDE.md (PNG→JPG) and confirm: past-day labels crisp outline; today/future no shadow.
2. Rebuild + restart desktop: `./scripts/buildStart.sh`; visually confirm the same on the daily view.
3. Compare emulator-5554 vs desktop daily views for equivalence; tune `*_OUTLINE_STROKE_FRACTION`
   together (0.10–0.15 range) if needed.
4. Run `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.*Daily*"` and `:desktop:test`
   (no test asserts on these visual constants, but confirm nothing regresses/compiles).
