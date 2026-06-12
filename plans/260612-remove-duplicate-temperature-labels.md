# Fix: duplicate "88°" labels on the hourly forecast line (Samsung)

## Context

On the Samsung (SM-F936U1) hourly temperature graph, the forecast line shows **two identical
`88°` labels** stacked on the flat plateau (confirmed by screenshot + live logcat). The user
reports this as a duplicate-label bug.

Root cause, confirmed from live device logs (`adb -s RFCT71FR9NT logcat`):

```
TempExtrema: LABEL_TEMPS: [88,88,88,88,88,88,88,88,88,88,88,88,88,88,88,88,86,85]   # forecast curve
TempExtrema: ACTUAL_END_INDEX: 14 transitionX=248
TempLabelResolver: LabelAccepted: role=HIGH  idx=0  val=88.0                         # global daily high
TempLabelResolver: LabelAccepted: role=LOW   idx=17 val=85.0
TempLabelResolver: LabelAccepted: role=ACTUAL_LOW idx=13 val=80.66
TempLabelResolver: LabelAccepted: role=LOCAL idx=15 val=88.0 reason=FORECAST_MIDPOINT # <-- DUPLICATE
```

The forecast curve is a flat plateau at **88°** that only declines (88→86→85) at the very end.
`addForecastMidpointLabel` (in `TemperatureLabelResolver.kt`) injects a synthetic `LOCAL` label at
the geometric middle of the future region (idx 15) so a *monotonic decline* isn't left bare. But on
a plateau the midpoint value (88) is identical to the global `HIGH` (88) that's already labeled — so
it renders a redundant second `88°`.

Why existing redundancy logic misses it:
- `checkRedundantPairSuppression` (the normal LOCAL/END redundancy gate) is **never applied** to the
  midpoint — the midpoint is injected *after/outside* the main candidate loop (line 234), bypassing
  the line-198 call.
- Even if routed through it, that gate is **distance-gated** (`boundaryWindow ≈ 2` indices here),
  while the duplicate `HIGH` sits 15 indices away across the plateau, so it wouldn't fire anyway.

Intended outcome: the forecast line shows the daily high `88°` once; the redundant midpoint label is
not emitted. Genuinely-bare monotonic declines (e.g. overnight 70→58) still get their midpoint.

## The Fix

Single, self-contained change in
`shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`,
function `addForecastMidpointLabel` (≈ lines 254-278).

Add a **value-redundancy guard** before injecting the label: skip the midpoint when its formatted
value already appears on a forecast-valued label currently in `specialCandidates`. Unlike
`checkRedundantPairSuppression`, this is **NOT distance-gated** — a midpoint is a "readable
reference" label, and a duplicate number anywhere on the same forecast line defeats that purpose, no
matter how far away.

Sketch (final wording at implementation time):

```kotlin
val mid = (futureStart + lastIndex) / 2
if (mid <= futureStart || mid >= lastIndex) return
if (specialCandidates.any { it.index == mid }) return
if (mid !in labelTemps.indices) return

// Don't repeat a value the forecast line already shows. The midpoint exists to give a NEW readable
// reference for an otherwise-bare region; on a flat plateau its value equals the global HIGH/LOW (or
// a region-boundary label) already on screen, so it would render as a duplicate number. Not
// distance-gated: a duplicate anywhere on the forecast line is redundant for a reference label.
val midText = formatTemp(labelTemps[mid])
val alreadyOnForecastLine = specialCandidates.any { c ->
    c.role in FORECAST_VALUE_ROLES &&
        c.index in labelTemps.indices &&
        formatTemp(labelTemps[c.index]) == midText
}
if (alreadyOnForecastLine) {
    Log.d(TAG, "MidpointSuppressed: idx=$mid val=${labelTemps[mid]} duplicates existing forecast label")
    return
}
```

Add the role set near the existing `FORECAST_HIGH_ROLES` / `FORECAST_LOW_ROLES` / `ACTUAL_DISPLAY_ROLES`
(≈ line 290) — the roles whose label text is drawn from `labelTemps` (the forecast/hindcast series),
deliberately excluding `ACTUAL_*` roles which read `actualLabelTemps`:

```kotlin
// Roles whose label text comes from the forecast series (labelTemps), used to detect a midpoint
// that merely repeats a value already shown on the forecast line.
private val FORECAST_VALUE_ROLES = setOf(
    TemperatureRole.HIGH, TemperatureRole.LOW,
    TemperatureRole.FORECAST_HIGH, TemperatureRole.FORECAST_LOW,
    TemperatureRole.PAST_FORECAST_HIGH, TemperatureRole.PAST_FORECAST_LOW,
    TemperatureRole.START, TemperatureRole.END, TemperatureRole.LOCAL,
)
```

Reuse the existing `formatTemp(...)` helper (line 78) — the same rounding the renderer uses — so the
comparison matches what's actually drawn.

### Why this and not alternatives
- **"Skip if region is flat (max-min < threshold)"** — wouldn't fire: region `[88,88,86,85]` has
  range 3°, above any sane flat threshold, yet is still a duplicate.
- **"Skip if midpoint equals region endpoints"** — self-contained but wrongly suppresses a *fully*
  flat future region (e.g. steady 75°) that genuinely deserves a label when 75 isn't shown elsewhere.
  The value-vs-existing-labels check keeps that case (adds 75 only if nothing else shows 75).
- **Routing the midpoint through `checkRedundantPairSuppression`** — distance-gated, so it can't see
  a duplicate 15 indices away; changing its window would risk the carefully-tuned boundary tests.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt`
  — add `FORECAST_VALUE_ROLES`; add the guard inside `addForecastMidpointLabel`.

No Android/desktop renderer changes needed — both consume `collectLabelCandidates`, so the shared
fix covers Pixel, Samsung, and the desktop port.

## Tests

Add a regression case to
`shared/src/test/kotlin/com/weatherwidget/shared/graph/TemperatureLabelSuppressionTest.kt`
(which already has midpoint coverage and a `samsungFlatCurveHours()` fixture):

- **New:** `forecast plateau equal to the daily high gets no duplicate midpoint label` — build a
  flat-88 forecast region declining only at the end (mirrors the live data), assert that
  `collectLabelCandidates` yields **no** `LOCAL` (FORECAST_MIDPOINT) candidate whose value rounds to
  the same text as the `HIGH` candidate. Reuse / extend `samsungFlatCurveHours()` if it fits.
- **Guard against regression:** confirm the existing tests still pass —
  `monotonic forecast gets a midpoint label so the line is not bare` and
  `tight-zoom forecast region of three hours still gets a midpoint label` must remain green (their
  midpoint values are distinct from any other label, so the new guard must not suppress them).

Run:
```bash
./gradlew :shared:testDebugUnitTest --tests "com.weatherwidget.shared.graph.TemperatureLabelSuppressionTest"
```

## End-to-end verification on the Samsung

1. `./gradlew installDebug` (installs to all devices; the bug repros on `RFCT71FR9NT`).
2. Trigger a redraw and capture state (read-only inspection per CLAUDE.md):
   ```bash
   adb -s RFCT71FR9NT logcat -c
   adb -s RFCT71FR9NT shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n com.weatherwidget/.widget.WeatherWidgetProvider
   adb -s RFCT71FR9NT logcat -d | grep -E "FORECAST_MIDPOINT|MidpointSuppressed|LabelAccepted"
   adb -s RFCT71FR9NT exec-out screencap -p > /tmp/ww.png   # strip pre-PNG warning bytes, convert to JPG before reading
   ```
3. Confirm: logs show `MidpointSuppressed ...` (or simply no `reason=FORECAST_MIDPOINT` LabelAccepted)
   for the flat-plateau case, and the screenshot shows a **single** `88°` on the forecast line.
4. Sanity-check a non-plateau day still shows its interior midpoint (no over-suppression).
