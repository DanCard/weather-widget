# Session Log: Three-Bar Past-Day Dual-Mode History (2026-05-15)

## Overview

User reported that on past/historical days, when dual API mode is active, only the red actuals bar renders — the two forecast comparison bars (one per API) are missing. Investigation revealed a three-layer bug: `WeatherWidgetProvider.activeSources` only included each widget's *current* display source (so the second API's snapshot rows were never loaded from the DB), `DailyViewLogic.prepareGraphDays` sourced `nextSourceHigh/Low` from `nextSourceWeatherByDate` (current weather, not historical snapshots), and `DailyForecastGraphRenderer.drawDayBars` deliberately suppressed the displaySource forecast overlay whenever a next-source bar was present.

The fix touched all three layers. After implementation the user requested a layout flip: forecast bars on the LEFT (for the primary/displaySource) with actuals in the center and the next-source bar on the RIGHT. The session then added 4 targeted unit tests (no mocks, reusing the codebase's pure-function testing strategy) and concluded with a polish change to remove the gap between the actuals and the next-source bar.

The session was source-agnostic from the start — the codebase supports 7 APIs (NWS, Open-Meteo, Visual Crossing, OpenWeatherMap, Silurian, WeatherAPI, Tomorrow.io), and the bug applied to any pair the user toggled between. None of the production code added contains hardcoded source names.

## 1. User Prompts (Verbatim, Chronological)

1. **Initial bug report:**
   > daily forecast view: history: when dual api mode is active, for history, I expect to see three bars, one bar for blended actuals, and two bars for forecast for each api. Currently I see the red actuals, but not the two forecast bars on historical days.

2. **Layout pick via `AskUserQuestion` (during planning):**
   - "How should the three bars be positioned on past days in dual-API mode?" → **Split left/right** (Open-Meteo on LEFT, red actuals CENTER, NWS on RIGHT — with ASCII preview shown)

3. **Rejecting initial `ExitPlanMode` to clarify source generalization:**
   > Are the APIs you specify examples or fixed? There is more than NWS and meteo.

4. **Approved second `ExitPlanMode`** after plan was generalized to use `displaySource` / `nextSource` roles instead of named APIs.

5. **(Two interrupted requests during verification phase — see §6 for details.)**

6. **Visual confirmation on emulator:**
   > I see it working

7. **Follow-up test request (re-entered plan mode):**
   > Should we add any automated tests for this?

8. **Approved test plan** via `ExitPlanMode`.

9. **Gap-removal request:**
   > dual mode, history : remove the gap between the thermostat and the next api forecast.

10. **Session log request (this document):**
    > Write a detailed session log to session-logs/ dir, include all prompts

## 2. Investigation Phase

### Initial exploration

Two `Explore` agents were launched in parallel to map the rendering pipeline:

- One traced the daily forecast widget's rendering code (`DailyForecastGraphRenderer.kt`, `DailyViewHandler.kt`) — found the `drawDayBars` / `drawNextSourceBar` decision points and the `suppressForecastOverlay` flag.
- The other traced forecast-snapshot retrieval (`ForecastDao`, `WeatherWidgetProvider.kt`) — found that `activeSourceList` only contained each widget's current source.

The two reports converged on a three-layer bug. Critical verification reads:

- `WeatherWidgetProvider.kt:158-162` — `activeSources = filteredIds.map { stateManager.getCurrentDisplaySource(it).id }.toSet()` — only one source per widget.
- `DailyViewLogic.kt:365-386` (`isPastDate` branch) — `pastForecast` only filtered `forecasts` by `displaySource.id`; never queried `nextSource.id`.
- `DailyViewLogic.kt:584-585` — `nextSourceHigh/Low` always sourced from `nextSourceWeatherByDate[date]` (built from `weatherList`, not snapshots).
- `DailyForecastGraphRenderer.kt:712` — `suppressForecastOverlay = day.nextSourceHigh != null && day.nextSourceLow != null` blocked the yellow overlay whenever dual mode was on.

### Source-count question

After the first `ExitPlanMode` attempt, the user pushed back: "Are the APIs you specify examples or fixed? There is more than NWS and meteo."

Grep'd `WeatherSource.` references in `ForecastRepository.kt` and `WidgetStateManager.kt`:

- `WeatherSource` enum supports **7 sources**: NWS, OPEN_METEO, VISUAL_CROSSING, OPEN_WEATHER_MAP, SILURIAN, WEATHER_API, TOMORROW_IO.
- `WidgetStateManager.getCurrentDisplaySource(widgetId)` / `getNextDisplaySource(widgetId)` cycle through a per-widget `visibleSourcesOrder` list — "dual mode" is just the current pair, and the pair changes as the user toggles.

The plan was then rewritten to refer to roles (`displaySource`, `nextSource`) throughout — no API names baked in. Final layout description: "the displaySource always occupies the right (yellow overlay) slot and nextSource always mirrors left" — but this was later revised by the user (see §4).

## 3. Implementation — Three-Layer Fix

### Layer 1 — Fetch (`WeatherWidgetProvider.kt`)

**Before:**
```kotlin
val activeSources = filteredIds
    .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    .map { stateManager.getCurrentDisplaySource(it).id }
    .toSet() + WeatherSource.GENERIC_GAP.id
```

**After:**
```kotlin
val showTwoBars = stateManager.isShowTwoBarsEnabled()
val activeSources = filteredIds
    .filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }
    .flatMap { widgetId ->
        val primary = stateManager.getCurrentDisplaySource(widgetId).id
        if (showTwoBars) {
            val next = stateManager.getNextDisplaySource(widgetId).id
            listOf(primary, next)
        } else {
            listOf(primary)
        }
    }
    .toSet() + WeatherSource.GENERIC_GAP.id
```

Because `activeSources` is a `Set`, sources shared between widgets are deduplicated — DB query stays minimal.

### Layer 2 — Transform (`DailyViewLogic.kt`)

In the `isPastDate` branch, added a second snapshot lookup mirroring the existing `pastForecast` filter chain, but keyed on `nextSource.id`:

```kotlin
if (nextSource != null && nextSource != displaySource) {
    val pastNextSourceForecast = forecasts
        .filter { it.source == nextSource.id }
        .filter { !it.isClimateNormal }
        .filter { it.highTemp != null && it.lowTemp != null }
        .maxByOrNull { it.fetchedAt }
    pastNextSourceHigh = pastNextSourceForecast?.highTemp
    pastNextSourceLow = pastNextSourceForecast?.lowTemp
}
```

At `DayData` construction, routed past-day values to `nextSourceHigh/Low`:

```kotlin
nextSourceHigh = if (isPastDate) pastNextSourceHigh else nextSourceWeather?.highTemp,
nextSourceLow  = if (isPastDate) pastNextSourceLow  else nextSourceWeather?.lowTemp,
```

### Layer 3 — Render (`DailyForecastGraphRenderer.kt`)

Two changes:

1. **Lifted the overlay suppression on past days:**
   ```kotlin
   val suppressForecastOverlay =
       !day.isPast && day.nextSourceHigh != null && day.nextSourceLow != null
   ```

2. **Mirrored the next-source bar to the LEFT for past days** (initial choice per the AskUserQuestion answer):
   ```kotlin
   val nextX = centerX + when {
       day.isToday -> layout.tripleBarOffset
       day.isPast -> -layout.nextSourceBarOffset
       else -> layout.nextSourceBarOffset
   }
   ```

Result on past days: `[nextSource bar LEFT] [red actuals CENTER] [displaySource overlay RIGHT]`.

## 4. Layout Revision (Live)

During verification on the emulator, two user messages arrived back-to-back:

> Lets change history single mode so that the thermostat is on the right side instead of on the left side. If NWS is the primary / first api, then the forecast history is on the left, instead of the right. In dual mode the primary is also on the left, and the second api is to the right of thermostat.

Then immediately:

> sorry I was trying to cancel what I typed in, please continue

The first message had been a partially-formed thought sent by mistake. Edits were rolled back. But then a third clarifying message arrived:

> Wait it should be [primary /first on left] [red actuals center] [second forecast on right]

So the layout *did* need to change — just clearer than the original message. The final layout flipped from the original plan:

| Bar | Original plan | Final layout |
|-----|---------------|--------------|
| displaySource overlay (yellow) | RIGHT (+forecastBarOffset) | **LEFT** (-forecastBarOffset) |
| Red actuals (history) | CENTER (centerX) | CENTER (centerX) |
| nextSource bar | LEFT (-nextSourceBarOffset) | **RIGHT** (+nextSourceBarOffset) |

Implementation: two single-line edits in `DailyForecastGraphRenderer.kt`:

```kotlin
val forecastX = centerX + if (day.isPast) -layout.forecastBarOffset else layout.forecastBarOffset
// ...
val nextX = centerX + if (day.isToday) layout.tripleBarOffset else layout.nextSourceBarOffset
```

Built, installed on emulator (`./gradlew installDebug`), and the user confirmed: **"I see it working"**.

## 5. Tests (Phase 2)

After visual confirmation, re-entered plan mode for: "Should we add any automated tests for this?"

Per the project's testing memory (`testing-strategy.md`): no mocking, prefer pure-function extraction, Android glue verified on-device. Plan classified each layer:

- ✅ **Transform layer** (`DailyViewLogic.prepareGraphDays`) — pure function, ideal for JUnit + Robolectric.
- ✅ **Render layer** (`DailyForecastGraphRenderer.drawDayBars`) — already has Robolectric-Canvas tests using a `BarDrawnDebug` callback that emits `(date, barType, highY, lowY, centerX, color)` for each bar drawn.
- ❌ **Fetch layer** (`WeatherWidgetProvider.activeSources`) — Android-coupled glue against `WidgetStateManager` singleton; tested on-device.

### Added tests

**`DailyViewLogicTest.kt`** (2 tests, after line 1406):

1. `prepareGraphDays populates nextSourceHigh and Low from forecast snapshot for past day in dual mode` — positive path. Sets up `forecastSnapshots = mapOf(yesterday to listOf(nwsSnap, meteoSnap))` and empty `nextSourceWeatherByDate`, then asserts `day.nextSourceHigh == 75f` (Open-Meteo snapshot). Proves the past-day path reads from snapshots, not from `nextSourceWeatherByDate`.

2. `prepareGraphDays leaves nextSourceHigh null for past day when nextSource has no snapshot` — negative path. Only the NWS snapshot is provided; asserts `day.nextSourceHigh == null` so the renderer's `drawNextSourceBar()` no-ops.

Both reuse the existing `createWeather()` factory at line 1442 and the `ObservationResolver.DailyActual` pattern.

**`DailyForecastGraphRendererRoboTest.kt`** (2 tests, after line 208):

3. `pastDay_dualMode_drawsThreeBars_overlayLeftOfActuals_nextSourceRightOfActuals` — builds a `DayData` with `isPast=true`, `forecastHigh/Low` set, `nextSourceHigh/Low` set. Asserts three bars are emitted via `BarDrawnDebug`: `HISTORY`, `FORECAST_OVERLAY`, `NEXT_SOURCE`. Asserts relative X positions: `overlay.centerX < history.centerX < nextSource.centerX`.

4. `pastDay_singleMode_forecastOverlaySitsLeftOfActuals` — regression guard for the overlay-left flip. With `nextSourceHigh = null`, still asserts `overlay.centerX < history.centerX` — would catch a silent revert of the `if (day.isPast) -layout.forecastBarOffset` edit.

### Test design notes

- Assertions use **relative** X positions (`<`, `>`) rather than absolute pixel values. This insulates the suite from tuning of layout constants like `FORECAST_BAR_OFFSET_SCALE` or `NEXT_SOURCE_BAR_GAP_DP` — invariant: directional, not metric.
- The single-mode regression test is the most valuable of the four: flipping the past-day overlay back to its old right-side position would compile, render, and produce a working widget — only the visual would silently regress. The test pins the invariant.

### Test results

```
DailyForecastGraphRendererRoboTest > pastDay_singleMode_forecastOverlaySitsLeftOfActuals PASSED
DailyForecastGraphRendererRoboTest > pastDay_dualMode_drawsThreeBars_overlayLeftOfActuals_nextSourceRightOfActuals PASSED
DailyViewLogicTest > prepareGraphDays populates nextSourceHigh and Low from forecast snapshot for past day in dual mode PASSED
DailyViewLogicTest > prepareGraphDays leaves nextSourceHigh null for past day when nextSource has no snapshot PASSED
```

Full unit test suite ran clean — no regressions in adjacent tests.

## 6. Gap-Removal Polish

User's final ask:

> dual mode, history : remove the gap between the thermostat and the next api forecast.

### Root cause

`nextSourceBarOffset` is computed for **future-day dual mode** where the primary bar uses `PRIMARY_BAR_DUAL_SOURCE_WIDTH_SCALE = 0.8` (a thick "dual-source primary" paint). But past days use `historyBarPaint` at `HISTORY_BAR_WIDTH_SCALE = 0.7` — narrower. So the offset places the next-source bar too far right when paired with the thinner history bar, leaving a `~0.05 * barWidth + 1dp` gap.

### Fix

Added a past-day-specific offset in `LayoutInfo` that places the next-source bar flush against the history bar:

```kotlin
// in LayoutInfo data class
val nextSourcePastBarOffset: Float,

// in layout construction
nextSourcePastBarOffset = barWidth * HISTORY_BAR_WIDTH_SCALE / 2f
                       + barWidth * NEXT_SOURCE_BAR_WIDTH_SCALE / 2f,
```

That's `(half of history bar width) + (half of next-source bar width)` — so the right edge of the history bar meets the left edge of the next-source bar with zero gap.

Updated `drawNextSourceBar`'s `nextX` calculation:

```kotlin
val nextX = centerX + when {
    day.isToday -> layout.tripleBarOffset
    day.isPast -> layout.nextSourcePastBarOffset
    else -> layout.nextSourceBarOffset
}
```

Future-day dual mode is unchanged (still uses `nextSourceBarOffset` which retains the 1dp gap, matching the thicker `PRIMARY_BAR_DUAL_SOURCE_WIDTH_SCALE` primary).

The existing renderer tests passed unchanged — they assert relative positions, and the new offset still keeps `nextSource.centerX > history.centerX`.

## 7. Files Modified

### Production

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt` — `activeSources` flatMap to include `nextSource` when dual mode is enabled
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — `pastNextSourceHigh/Low` from `forecasts.filter { it.source == nextSource.id }`; routed to `DayData.nextSourceHigh/Low` for past dates
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`:
  - Lifted `suppressForecastOverlay` on past days
  - `forecastX = centerX + if (day.isPast) -layout.forecastBarOffset else layout.forecastBarOffset` (overlay LEFT on past)
  - Added `nextSourcePastBarOffset` field + computation (flush against history bar)
  - `nextX` uses `nextSourcePastBarOffset` for past days
  - Updated `drawNextSourceBar` docstring to describe the three-bar past-day layout

### Tests

- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` — 2 new tests for past-day nextSource snapshot data flow
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt` — 2 new tests for three-bar past-day rendering layout

### Plan / session artifacts

- `~/.claude/plans/daily-forecast-view-history-zazzy-quill.md` — implementation plan, then rewritten as test plan
- `session-logs/260515-three-bar-past-day-dual-mode.md` — this document

## 8. Key Insights & Lessons

- **Three-layer bugs hide in plain sight when each layer's local invariant is correct in isolation.** The fetch layer correctly fetched the *active* source. The transform layer correctly read `nextSourceHigh/Low` from `nextSourceWeatherByDate` (which is right for future days). The renderer correctly suppressed the overlay (which is right for the previous "next-source reuses overlay slot" design). Each was right for its scope; the bug only appeared at the seam.

- **Source-agnosticism matters when the codebase already supports it.** Tests, plan prose, and code should refer to `displaySource` / `nextSource` roles, not "NWS" or "Open-Meteo". The codebase supports 7 APIs; the bug applied to any pair. Catching this in the planning phase (via the user's "There is more than NWS and meteo" pushback) avoided baking source names into the fix.

- **Relative-position assertions beat absolute-pixel assertions for renderer tests.** All four new test assertions use `<` and `>` on `centerX`, not `assertEquals(123.4f, ...)`. When the gap-removal fix came later, the existing tests passed unchanged.

- **Different bar widths require different offsets.** The gap between the actuals and next-source bars wasn't a bug per se — it was the layout constant `nextSourceBarOffset` being designed for a different primary-bar width. Adding `nextSourcePastBarOffset` is more maintainable than mutating the existing constant.

- **The single-mode regression test was the most valuable test added.** The dual-mode test verifies the new code path; the single-mode test pins the *prior* layout change against a silent revert. Layout changes that "still work" are the easiest to undo by accident.

- **Plan mode caught a scope misunderstanding before code was written.** The user's "There is more than NWS and meteo" came as a comment on the planned approach, not as a code review of a finished implementation. Saved at least a round-trip and probably a partial revert.

- **`MEMORY.md` testing strategy was load-bearing.** It defined the project's anti-mocking stance and pure-function preference, which set the scope for the test plan (transform + render only, fetch left to on-device verification). Without it, the test plan would likely have proposed a brittle `WeatherWidgetProvider` test.
