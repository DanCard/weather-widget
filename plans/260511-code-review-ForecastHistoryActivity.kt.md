# Code Review: ForecastHistoryActivity.kt

## Context

`ForecastHistoryActivity.kt` (871 lines) is the detail view for a tapped day: it
shows forecast-evolution / forecast-error graphs, an actuals-vs-prediction
legend, an accuracy-summary block, and a freshness card. It's invoked from the
widget tap handlers (`DayClickHelper`, `DailyClickHandlerFactory`,
`DailyViewHandler`) and has parallel pure-function tests in
`ForecastHistoryActivityTest.kt` (334 lines).

The file works correctly today, but has accumulated dead code, dead imports,
shadowing pitfalls, an indentation regression, and several copy-paste blocks
that are easy to collapse. None of the findings are functional bugs; this
review focuses on clarity, coherence with the rest of the codebase
(Hilt-injected DAOs, `WidgetIntentRouter`, localized strings), and removing
material that no longer earns its keep.

---

## Findings

Severity tags: **HIGH** (likely bug or real defect), **MED** (clarity / dead
code / inconsistency), **LOW** (nit).

### 1. Dead code: NWS-from-observations path is wired to nothing — **MED**

The companion helpers `buildActualFromNwsObservations` (lines 140–176) and
`isNwsObservationStation` (135–138), the `ObservationDao` injection
(`observationDao`, line 62), the `ObservationResolver` import (line 33), and
the `ObservationEntity` import (line 29) are not called from anywhere in the
activity body. The activity's NWS-actuals lookup goes through
`resolveSourceSpecificActual` (340–365), which only consults `forecastDao`.

These are tested in `ForecastHistoryActivityTest.kt` lines 250–333, so the
tests pass and the dead code is invisible at the call-site level.

**Action (user-confirmed: delete):**
- Delete companion `buildActualFromNwsObservations` (140–176) and
  `isNwsObservationStation` (135–138)
- Delete the `@Inject lateinit var observationDao: ObservationDao` field
  (61–62)
- Delete imports: `ObservationDao` (28), `ObservationEntity` (29),
  `ObservationResolver` (33)
- Delete tests in `ForecastHistoryActivityTest.kt`:
  - `isNwsObservationStation - rejects non NWS synthetic station ids` (250–255)
  - `buildActualFromNwsObservations - uses only NWS rows for high and low` (258–302)
  - `buildActualFromNwsObservations - returns null when only non NWS rows exist` (304–333)
  - Drop the now-unused imports in the test file:
    `ObservationEntity`, `buildActualFromNwsObservations`,
    `isNwsObservationStation`

### 2. Indentation regression in `updateFreshnessCard` — **MED**

Lines 811–824 are indented to 8 spaces; lines 826 onward drop back to 4
spaces. The closing brace on 857 sits at the original method level. Every
other private method in the file uses 4-space body indent. Likely a paste
artifact from a recent edit.

**Fix:** re-indent lines 811–824 to match the rest of the body (and remove
the trailing whitespace on the now-empty separator lines).

### 3. Variable shadowing of class fields — **MED**

`onCreate` declares `val targetDate` (line 212) inside the same scope where
`lateinit var targetDate` is a field (line 202). It's resolved by
`safeTargetDate` (222) and `this.targetDate = safeTargetDate` (224), but a
reader scanning the function sees `targetDate` referring to two different
things across 14 lines.

`resolveSourceSpecificActual` (340) also shadows the field
`targetLocalDate` with a local of the same name (line 346).

**Fix:** rename the locals — e.g., `intentTargetDate`, `parsedLocalDate` —
or read straight from `intent.getStringExtra(...)` into the field.

### 4. Direct DB access bypasses Hilt — **MED**

Line 318: `WeatherDatabase.getDatabase(this@ForecastHistoryActivity).appLogDao()`.
Every other DAO in this class is `@Inject lateinit var`. Inject `appLogDao`
the same way, or remove the log call (see #5).

### 5. `HISTORY_LOAD` debug log on every load — **LOW/MED**

Lines 324–329 write a `HISTORY_LOAD` row to `app_logs` on every history
load. Per `MEMORY.md` ("Debug logging kept temporarily — remove after a few
days of monitoring"), debug instrumentation in this codebase is meant to be
short-lived. If this is no longer actively investigated, drop it; if it is,
leave a short `// TODO(remove after YYYY-MM-DD): ...` so the next reader
knows it's transient.

### 6. Stale "history" comment — **LOW**

Line 67: `// forecastDao is also used for actual weather lookups
(previously weatherDao)`. Per project guidance ("comments shouldn't
reference the current task or migration history — those belong in the PR
description"), delete it. The intent ("forecastDao serves both forecast and
actual lookups") is already obvious from the code.

### 7. Unused imports — **LOW**

- `import com.weatherwidget.widget.ObservationResolver` (line 33) — unused (see #1)
- `import com.weatherwidget.data.local.ObservationEntity` (line 29) — only used in companion helpers slated for review (#1)
- `import java.text.SimpleDateFormat` (line 40) — unused
- `import java.util.Date` (line 45) — unused
- `import kotlin.math.abs` (line 48) — line 697 uses fully-qualified `kotlin.math.abs(bias)`, so the import is redundant
- `import kotlin.math.roundToInt` (line 49) — unused
- `import com.weatherwidget.widget.BatteryFetchStrategy` (line 51) — unused; `updateFreshnessCard` reads `BatteryManager` directly

### 8. Repetitive `when (requestedSource)` blocks — **MED**

Three blocks switch on `requestedSource` with nearly identical bodies:

- Lines 411–425 (summary text): five branches all of the form
  `append("$summaryCount ${displayName} forecast snapshots")`. Collapse to
  `requestedSource?.let { append("$summaryCount ${it.displayName} forecast snapshots") } ?: append(...)`.
- Lines 453–474 (legend visibility): `VISUAL_CROSSING`, `OPEN_METEO`,
  `WEATHER_API` all hide NWS legend, show Meteo legend. Group them:
  `WeatherSource.VISUAL_CROSSING, WeatherSource.OPEN_METEO,
  WeatherSource.WEATHER_API -> { ... }`.
- Lines 403–410 (summary count): again only NWS / non-NWS distinction in
  practice; consider single-counter logic.

These are also a maintenance smell — every new `WeatherSource` (`SILURIAN`,
`TOMORROW_IO`) needs three edits today and is silently treated as
"NWS-like" in the legend block.

### 9. Hard-coded strings where the rest uses `getString` — **LOW/MED**

The activity uses `R.string.*` for most user-facing text but has hand-rolled
literals at:

- Line 414, 416, 418, 420, 422 (summary text)
- Line 490 (`"$sourceLabel API actual: ..."`)
- Line 499 (`"Location actual: ..."`)
- Line 821, 823, 830, 856 (freshness card labels)
- Lines 648–649 (no-history-yet message)

If localization isn't a goal for this app, ignore. Otherwise these should
move to `strings.xml`.

### 10. `cycleApiSource` uses raw broadcast — **LOW**

Lines 781–786 build an `Intent` to `WeatherWidgetProvider` directly and
call `sendBroadcast`. The rest of the file routes through
`WidgetIntentRouter` (e.g., `launchWidgetTemperatureMode` at 737). Prefer
the router for consistency, or extract a helper on `WidgetIntentRouter` if
one doesn't exist.

### 11. Ambiguous boolean precedence — **LOW**

Line 482:
```kotlin
if (apiHigh != null && apiLow != null || appHigh != null && appLow != null)
```
Operator precedence is correct (`&&` binds tighter than `||`), but
parentheses make it unambiguous to a reader and survive a future edit:
```kotlin
if ((apiHigh != null && apiLow != null) || (appHigh != null && appLow != null))
```

### 12. Duplicate `effectiveVisibleSources()` call — **LOW**

Line 240:
```kotlin
cachedRequestedSource = requestedSource?.takeIf { it in effectiveVisibleSources() } ?: firstVisibleSource()
```
`effectiveVisibleSources()` runs once for `takeIf`, then `firstVisibleSource()`
calls it again. Both hit `widgetStateManager.getEffectiveVisibleSourcesOrder(targetLat, targetLon)`.
Cache once into a local.

### 13. Mirror-image bitmap blocks — **LOW**

Lines 536–582: `highBitmap` and `lowBitmap` are constructed with the same
shape (mode-conditional, six identical kwargs except the high/low ones).
Extract a small helper to halve the block size:

```kotlin
fun render(actual: Float?, appActual: Float?, isHigh: Boolean): Bitmap = ...
```

Optional — only worth doing if you're already touching the block.

### 14. `backfillDailyExtremesIfNeeded` triggers a widget refresh from
the history activity — **LOW (note, not change)**

Lines 618–622 fire `WeatherWidgetProvider.triggerImmediateUpdate(...)` from
inside the accuracy-summary loader. The reason
(`"history_missing_extremes_NWS"`) suggests intent: opening history can
surface gaps that a refresh would fill. Worth a one-line comment so it
doesn't look like a side-effect leak; otherwise leave alone.

---

## Critical files

- `app/src/main/java/com/weatherwidget/ui/ForecastHistoryActivity.kt` — all fixes
- `app/src/test/java/com/weatherwidget/ui/ForecastHistoryActivityTest.kt` — touched only if #1 deletes the dead helpers (drop the corresponding tests)

## Verification

After applying any fixes:

- `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.ui.ForecastHistoryActivityTest"` — pure-function tests still green
- `./gradlew installDebug` — full build
- Manual: tap a past day on the widget → confirm graphs render, legend
  shows API + Location actuals, mode button toggles Evolution/Error, source
  cycle button advances through visible sources and refreshes the graph,
  freshness card values look right
- Manual: tap today/future → confirm "Hourly" mode button appears and
  launching it switches the widget to TEMPERATURE view

## Decisions (user-confirmed)

1. **Dead NWS-observations path (#1):** **delete** the helpers, tests,
   `observationDao` injection, and the related imports.
2. **Scope:** **all findings.** HIGH/MED + LOW nits all applied in one
   pass.

## Execution order

To keep diffs reviewable, apply in this order:

1. **#1** delete dead NWS path (activity + test file)
2. **#2** fix `updateFreshnessCard` indentation
3. **#3** rename shadowing locals (`intentTargetDate`, `parsedLocalDate`)
4. **#4** inject `appLogDao` via Hilt (or remove per #5 — see #5 first)
5. **#5** remove the `HISTORY_LOAD` debug log; if removed, #4 is moot
6. **#6** delete the stale `weatherDao` migration comment
7. **#7** drop the unused imports (do this last so earlier deletions
   don't leave orphaned import lines unaccounted for)
8. **#8** collapse the three `when (requestedSource)` blocks
9. **#9** move hard-coded user-facing strings to `strings.xml`
10. **#10** route `cycleApiSource`'s broadcast through `WidgetIntentRouter`
11. **#11** add parentheses to the boolean on line 482
12. **#12** cache `effectiveVisibleSources()` in `onCreate`
13. **#13** extract a `renderGraph(actual, appActual, isHigh)` helper for
    the bitmap blocks
14. **#14** add a one-line `// why` comment above
    `triggerImmediateUpdate` in `backfillDailyExtremesIfNeeded`
