# Add "Show two bars on daily forecast view" option

## Context

Today the daily forecast graph draws bars for **one** API source at a time (the widget's "current display source", chosen via tap-cycle on the API indicator). The app stores both NWS and Open-Meteo data in parallel (composite PK on `(date, source)`), so a second source's data is already on-device — it's simply filtered out before rendering at `DailyViewHandler.kt:216`.

This change adds a Settings toggle ("Show two bars on daily forecast view") that, when enabled, draws the **next** API source's high/low bar to the **LEFT** of the current source's bar on every day in the daily forecast view, with a ~2dp gap between them. The header's API indicator gains a `"<first> - <second>"` label when there is room; otherwise it falls back to today's single-source label. Tapping still cycles the toggle step by 1 — both labels update together because "next" is derived from "current".

Everything else stays unchanged: the existing forecast overlay (yellow/condition-colored secondary bar shown to the RIGHT of historical/future bars to compare prediction vs. actual), today's triple-bar (snapshot + observed + forecast), and all sizing/color rules remain exactly as they are. Past days will show 3 bars (next-source + history + forecast overlay), today will show 4 (next-source + the existing triple), and future days will show 3 (next-source + primary + forecast overlay).

## Critical Files

- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt` — global preference + `getNextDisplaySource()` helper
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/ui/SettingsActivity.kt` — wire new CheckBox + trigger UI refresh
- `/home/dcar/projects/weather-widget/app/src/main/res/layout/activity_settings.xml` — add CheckBox view
- `/home/dcar/projects/weather-widget/app/src/main/res/values/strings.xml` — new label string
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt` — build parallel next-source map; compose dual-source header label
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` — accept the new map; populate new `DayData` fields
- `/home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` — new offset constant, new `LayoutInfo` field, new `DayData` fields, new draw block

## Step 1 — Preference + state helper

In `WidgetStateManager.kt`:

- Add companion constant near other widget-scoped keys (~line 79):
  `private const val KEY_SHOW_TWO_BARS = "show_two_bars_on_daily"`
  This is **global** (not per-widget): the Settings screen owns it, matching how `KEY_VISIBLE_SOURCES_ORDER` is global.
- Add getter/setter after `setDailyColumnCount()` (~line 137):
  - `fun isShowTwoBarsEnabled(): Boolean`
  - `fun setShowTwoBarsEnabled(enabled: Boolean)`
- Add a peek-helper after `getCurrentDisplaySource()` (~line 551):
  ```
  fun getNextDisplaySource(widgetId: Int): WeatherSource {
      val visibleSources = getEffectiveVisibleSourcesOrder(widgetId)
      val toggleStep = getDisplaySourceToggleStep(widgetId)
      return sourceForStep(toggleStep + 1, visibleSources)
  }
  ```
  Reuses existing private `sourceForStep()` and `getDisplaySourceToggleStep()`. If only one source is visible (`size = 1`), modulo collapses and `next == current` — caller handles this.

## Step 2 — Settings screen

- `activity_settings.xml`: insert a `CheckBox` (`android:id="@+id/show_two_bars_checkbox"`) between the `api_sources_container` (~line 103) and the `icon_preview_title` TextView (~line 105). Use the same `widget_text_primary` color and 14sp text size as existing settings widgets.
- `strings.xml`: add `<string name="show_two_bars_on_daily">Show two bars on daily forecast</string>`.
- `SettingsActivity.kt`: in `setupViews()` after `setupApiSourcesList()` (~line 49), wire `isChecked` from `widgetStateManager.isShowTwoBarsEnabled()` and on-change call `setShowTwoBarsEnabled(checked)` then `WeatherWidgetProvider.triggerUiOnlyUpdate(this, reason = "show_two_bars_toggle")` so all widgets re-render immediately. `WeatherWidgetProvider` is already imported.

## Step 3 — Data plumbing (keep BOTH sources alive)

In `DailyViewHandler.kt`, the filter at lines 214–230 drops everything except `displaySource` + `GENERIC_GAP`. **Do not modify that filter** — instead, build a **second, parallel map** gated on the toggle. Immediately after line 230:

```
val showTwoBars = stateManager.isShowTwoBarsEnabled()
val nextSource = if (showTwoBars) stateManager.getNextDisplaySource(appWidgetId) else displaySource
val nextSourceWeatherByDate: Map<LocalDate, ForecastEntity> =
    if (showTwoBars && nextSource != displaySource) {
        weatherList.filter { it.source == nextSource.id }
            .groupBy { LocalDate.ofEpochDay(it.targetDate / WidgetConstants.MS_IN_A_DAY) }
            .mapValues { (_, items) -> items.first() }
    } else emptyMap()
```

Pass `nextSourceWeatherByDate` into `DailyViewLogic.prepareGraphDays(...)` (called ~line 426–435).

In `DailyViewLogic.kt`:
- Add parameter `nextSourceWeatherByDate: Map<LocalDate, ForecastEntity> = emptyMap()` to `prepareGraphDays()` (~line 290).
- In the `DayData(...)` constructor (~line 515–555), populate two new fields from `nextSourceWeatherByDate[date]?.highTemp` / `lowTemp`.

In `DailyForecastGraphRenderer.kt`, add to `DayData` (~line 156–180):
```
val nextSourceHigh: Float? = null,
val nextSourceLow: Float? = null,
```
Also extend `computeLayout()`'s `allTemps.flatMap { ... }` (~line 343) to include `it.nextSourceHigh, it.nextSourceLow` so the y-axis scale accommodates them.

## Step 4 — Renderer: draw the second bar to the left

In `DailyForecastGraphRenderer.kt`:

- Add constants near the existing offset scales (~line 70):
  ```
  private const val NEXT_SOURCE_BAR_OFFSET_SCALE = -0.7f   // mirror of FORECAST_BAR_OFFSET_SCALE
  private const val NEXT_SOURCE_BAR_GAP_DP = 2f             // small daylight gap
  ```
- Add `nextSourceBarOffset: Float` to `LayoutInfo` (~line 187–213) and populate it in the `return LayoutInfo(...)` block (~line 421):
  ```
  nextSourceBarOffset = barWidth * NEXT_SOURCE_BAR_OFFSET_SCALE - NEXT_SOURCE_BAR_GAP_DP.dp(density)
  ```
- In `drawDayBars()` (~line 626), after the primary-bar block (line ~672) and **before** the existing forecast-overlay block at line 674, insert a draw block guarded by `day.nextSourceHigh != null && day.nextSourceLow != null`. Use the same Paint construction pattern the primary bar uses (`WeatherConditionColors.forecastColor(...)` → `paints.barForColor(condColor)`), call the same `drawWeatherAdaptiveBar(...)` helper, position it at `centerX + layout.nextSourceBarOffset`. Apply `clampMinBarHeight()` like the primary block does.
- For today, add the same draw block right after the existing `drawTodayTripleBar()` call (~line 639) — gated on the same `nextSourceHigh/Low` non-null check.

Edge case: when `next == current` (only 1 visible source), Step 3 produces an empty map, so `nextSourceHigh/Low` stay null and this block no-ops automatically. No additional guard needed in the renderer.

## Step 5 — Header: `<first> - <second>` label

The header label is composed in `DailyViewHandler.kt` and passed through to RemoteViews (~line 233–234), `HeaderWidthChecker.resolveHeaderDisclosure(...)` (~line 329), `resolveHeaderPrecipPlacement(...)` (~line 482), and the bitmap header (~line 541).

Reuse `HeaderWidthChecker` — it already owns text-width measurement (`textWidthPx`, `dpToPx`, `resolveApiLeftPx`, `resolveLeftClusterRightPx`). Before line 329:

```
val dualSourceText =
    if (showTwoBars && nextSource != displaySource)
        "${displaySource.shortDisplayName} - ${nextSource.shortDisplayName}"
    else null

val dualFits = dualSourceText?.let { /* width check using HeaderWidthChecker */ } ?: false
val apiSourceText = if (dualFits) dualSourceText!! else displaySource.shortDisplayName
```

Replace every existing reference to `displaySource.shortDisplayName` (or equivalent) in the header-composition path with `apiSourceText`. The icon-only narrow-width path (~line 541, `if (isIconWidth) null else ...`) remains unchanged. Tap behavior (`setupApiToggle`, ~line 353) is unchanged — it still increments the toggle step; both labels recompute on next render because they read fresh state.

## Verification

1. `./gradlew :app:compileDebugKotlin` — type-checks the new fields, plumbing, and `LayoutInfo` extension.
2. `./gradlew :app:installDebug` on the emulator. Drop a 4x3 widget. Open Settings, toggle **Show two bars on daily forecast**.
   - **Future days:** 3 bars per column — next-source (left, full color) + primary + forecast overlay.
   - **Today:** 4 bars — next-source + the existing snapshot/observed/forecast triple.
   - **Past days:** 3 bars — next-source + history + forecast overlay.
   - Visible ~2dp gap between next-source bar and primary bar.
3. Tap the API source toggle on the widget — both labels in the `<first> - <second>` header rotate together.
4. Resize the widget down to 1 cell wide — header falls back to the single-source label automatically (`dualFits` returns false).
5. In Settings, untoggle the option — widgets refresh to single-bar mode; no orphan bars remain.
6. `grep -rn "isShowTwoBarsEnabled\|NEXT_SOURCE_BAR_OFFSET_SCALE\|nextSourceHigh" app/src/main` confirms every wiring site is touched.
7. No new test files. Existing renderer unit tests pass unchanged because new `DayData` fields default to `null`.
