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

## Step 6 — Next-source cloud cover & condition (REFINEMENT)

### Problem

After steps 1–5 shipped, the next-source bar was drawn using the PRIMARY source's icon/condition/cloud-cover metadata. This is visually wrong: the grey-bottom "cloud cover split" on the next-source bar shows the primary's cloud cover even when the next source predicted a different cloud level. A temporary quick-fix disabled adaptive segments on the next-source bar (`allowAdaptiveSegments = false` at `DailyForecastGraphRenderer.kt:737`), but that loses real information — the next source's own cloud cover signal.

The proper fix: plumb the next source's icon, condition flags, and cloud-cover-ratio through `DayData`, then let the renderer paint the next-source bar using *its* metadata.

### What drives the primary bar's cloud-cover split (today)

In `DailyViewLogic.prepareGraphDays()`:

- `cloudCoverRatioOverride` (line 456) comes from `resolveNoonCloudCoverRatio(date, hourlyForecasts, displaySource, weatherSourceId)` at lines 563–589. This finds the hourly forecast closest to noon **filtered by `displaySource.id`** and returns its `cloudCover` percent as a 0–1 ratio.
- `iconRes` (line 465) comes from `DailyForecastIconResolver.resolveIcon(weather, …, cloudCover = cloudCoverPercent)`.
- `isSunny`/`isRainy`/`isMixed` (lines 524–526) come from `WeatherIconMapper.isSunny(iconRes)` etc.
- In the renderer, `drawWeatherAdaptiveBar()` reads `day.iconRes`, `day.cloudCoverRatioOverride`, and `shouldUseAdaptiveSegments(day)` (which tests `day.isMixed || day.cloudCoverRatioOverride > 0`).

### Design

Add five sibling fields to `DayData` — one mirror per existing primary field — plus plumb `nextSource` into `prepareGraphDays`. Then synthesize a `DayData` "view" via `copy()` in the renderer's next-source draw block so the existing helpers (`drawWeatherAdaptiveBar`, `shouldUseAdaptiveSegments`) read next-source values without any signature changes.

### Implementation

**1. `DailyForecastGraphRenderer.kt` — extend `DayData` (~line 178–185)**

```kotlin
val nextSourceHigh: Float? = null,
val nextSourceLow: Float? = null,
val nextSourceIconRes: Int? = null,
val nextSourceIsSunny: Boolean = false,
val nextSourceIsRainy: Boolean = false,
val nextSourceIsMixed: Boolean = false,
val nextSourceCloudCoverRatioOverride: Float? = null,
```

**2. `DailyViewLogic.prepareGraphDays()` — accept next source and compute fields**

Add a parameter (~line 311):
```kotlin
nextSource: WeatherSource? = null,
```

Inside the day loop (after the existing `cloudCoverRatioOverride` block at line 456, ~before line 516), compute the next-source counterparts:
```kotlin
val nextSourceWeather = nextSourceWeatherByDate[date]
val nextSourceCloudCoverRatioOverride =
    if (nextSourceWeather != null && nextSource != null) {
        resolveNoonCloudCoverRatio(
            date = date,
            hourlyForecasts = hourlyForecasts,
            displaySource = nextSource,
            weatherSourceId = nextSourceWeather.source,
        )
    } else null
val nextSourceCloudCoverPercent =
    nextSourceCloudCoverRatioOverride?.let { (it * 100).toInt() }
val nextSourceIconRes = nextSourceWeather?.let { w ->
    DailyForecastIconResolver.resolveIcon(
        weather = w,
        targetDate = date,
        now = now,
        latitude = w.locationLat,
        longitude = w.locationLon,
        dayPrecipProbability = w.daytimePrecipProbability ?: w.precipProbability,
        nightPrecipProbability = w.nighttimePrecipProbability,
        cloudCover = nextSourceCloudCoverPercent,
    )
}
```

Pass them into the `DayData(...)` constructor (extending the existing block at lines 555–556):
```kotlin
nextSourceHigh = nextSourceWeather?.highTemp,
nextSourceLow = nextSourceWeather?.lowTemp,
nextSourceIconRes = nextSourceIconRes,
nextSourceIsSunny = nextSourceIconRes?.let(WeatherIconMapper::isSunny) ?: false,
nextSourceIsRainy = nextSourceIconRes?.let(WeatherIconMapper::isPrecipitation) ?: false,
nextSourceIsMixed = nextSourceIconRes?.let(WeatherIconMapper::isMixed) ?: false,
nextSourceCloudCoverRatioOverride = nextSourceCloudCoverRatioOverride,
```

Note: reuse the existing `resolveNoonCloudCoverRatio` and `DailyForecastIconResolver.resolveIcon` — no new helpers. The dayPrecip/nightPrecip handling is simpler than the primary path (uses entity fields directly instead of `dayNightPrecip` recomputation) — acceptable because the next-source bar is a secondary signal and the primary path's precision is preserved.

**3. `DailyViewHandler.kt` — pass `nextSource` through**

In the existing `prepareGraphDays` call (line 462–471), add `nextSource = nextSource` as a named argument.

**4. `DailyForecastGraphRenderer.drawDayBars()` — use next-source metadata in the next-source block (lines 720–741)**

Replace the current block with:
```kotlin
if (day.nextSourceHigh != null && day.nextSourceLow != null) {
    val nHighY = layout.tempToY(day.nextSourceHigh)
    val nLowY = layout.tempToY(day.nextSourceLow)
    val endpoints = resolveBarEndpoints(nHighY, nLowY, layout.minBarHeightPx)
    if (endpoints != null) {
        val (effectiveNHighY, effectiveNLowY) = endpoints
        val nextX = centerX + if (day.isToday) layout.nextSourceTodayBarOffset else layout.nextSourceBarOffset
        val condColor = WeatherConditionColors.forecastColor(
            day.nextSourceIsSunny, day.nextSourceIsRainy, day.nextSourceIsMixed, isNight = false,
        )
        val nextPaint = paints.nextSourceForColor(condColor)
        val nextDayView = day.copy(
            iconRes = day.nextSourceIconRes,
            isSunny = day.nextSourceIsSunny,
            isRainy = day.nextSourceIsRainy,
            isMixed = day.nextSourceIsMixed,
            cloudCoverRatioOverride = day.nextSourceCloudCoverRatioOverride,
        )
        drawWeatherAdaptiveBar(
            canvas = canvas,
            centerX = nextX,
            topY = effectiveNHighY,
            bottomY = effectiveNLowY,
            paint = nextPaint,
            day = nextDayView,
            logPrefix = "next_source",
            allowAdaptiveSegments = !day.isPast,
        )
        onBarDrawn?.invoke(BarDrawnDebug(day.date, "NEXT_SOURCE", effectiveNHighY, effectiveNLowY, nextX, nextPaint.color))
    }
}
```

Key changes vs. the quick-fix in place today:
- Color now derives from `nextSourceIs*` flags (line `condColor = …`).
- `nextDayView` is a `copy()` of `day` with the next-source-specific fields swapped into the slots that `drawWeatherAdaptiveBar`/`shouldUseAdaptiveSegments` read.
- `allowAdaptiveSegments = !day.isPast` is restored (was `false` during the quick-fix).

## Automated tests (NEW)

Two complementary tests are needed. Both use Robolectric, matching the existing test runner choice. No new test infrastructure required — the helpers `createWeather()` and `createHourlyForecast()` at `DailyViewLogicTest.kt:1362–1410` cover all the entity construction we need.

### Test 1 — `DailyViewLogicTest.prepareGraphDays_populatesNextSourceCloudCoverFromNextSourceHourly()`

**File:** `/home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` — append after the existing `prepareGraphDays today uses complete snapshot…` test (~line 1326).

**Setup:**
- `today = LocalDate.of(2030, 6, 15)`; `now = today.atTime(12, 0)`.
- `weatherByDate = { today → createWeather(today, highTemp=80f, lowTemp=60f, source=NWS, condition="Clear", precipProbability=0) }` — primary: sunny, dry.
- `nextSourceWeatherByDate = { today → createWeather(today, highTemp=78f, lowTemp=58f, source=OPEN_METEO, condition="Cloudy", precipProbability=40) }` — secondary: cloudy/rainy.
- `hourlyForecasts = listOf(`
  - `createHourlyForecast(today.atTime(12,0), cloudCover=10, source=NWS),`    // primary: clear noon
  - `createHourlyForecast(today.atTime(12,0), cloudCover=85, source=OPEN_METEO),` // secondary: heavy clouds at noon
  - `)`

**Call:**
```kotlin
val result = DailyViewLogic.prepareGraphDays(
    now, today, today, weatherByDate, forecastSnapshots = emptyMap(),
    numColumns = 1, displaySource = WeatherSource.NWS,
    skipYesterday = false, skipHistory = true,
    hourlyForecasts = hourlyForecasts,
    nextSourceWeatherByDate = nextSourceWeatherByDate,
    nextSource = WeatherSource.OPEN_METEO,
)
```

**Assertions:**
- `val day = result.first { it.date == today }`
- `assertEquals(0.10f, day.cloudCoverRatioOverride!!, 0.01f)` — primary cloud cover matches NWS noon (10%).
- `assertEquals(0.85f, day.nextSourceCloudCoverRatioOverride!!, 0.01f)` — next-source cloud cover matches Open-Meteo noon (85%).
- `assertNotNull(day.nextSourceIconRes)` — icon resolved for the next source.
- `assertNotEquals(day.iconRes, day.nextSourceIconRes)` — primary and next-source icons differ (sunny vs cloudy).
- `assertEquals(78f, day.nextSourceHigh)`; `assertEquals(58f, day.nextSourceLow)` — temps come from the next-source entity, not the primary.
- `assertFalse("primary should not be rainy", day.isRainy)`; `assertTrue("next source should be rainy or mixed", day.nextSourceIsRainy || day.nextSourceIsMixed)`.

This test fails today because `nextSourceCloudCoverRatioOverride` and `nextSourceIconRes` don't exist yet; passes after Step 6 is implemented. It is the **load-bearing** test — if it passes, the data is correctly plumbed, and the renderer just consumes it.

### Test 2 — `DailyForecastGraphRendererRoboTest.nextSourceBar_usesNextSourceConditionColor()`

**File:** `/home/dcar/projects/weather-widget/app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt` — append a new `@Test` method.

This test asserts that the renderer's `drawDayBars()` actually *consumes* the new `DayData` fields. Construct a single-day `DayData` directly (skipping `prepareGraphDays`), call `renderGraph(…, onBarDrawn = capture)`, then inspect captured `BarDrawnDebug` events.

**Setup:**
```kotlin
val primarySunnyIcon = R.drawable.ic_weather_clear     // any non-mixed sunny icon resource
val nextRainyIcon    = R.drawable.ic_weather_rain      // any rainy icon resource
val days = listOf(
    DailyForecastGraphRenderer.DayData(
        date = LocalDate.of(2030, 6, 15),
        label = "Today",
        high = 80f, low = 60f,
        iconRes = primarySunnyIcon,
        isSunny = true, isRainy = false, isMixed = false,
        cloudCoverRatioOverride = 0f,
        isToday = false,                        // pick a future-day path for simpler 3-bar layout
        nextSourceHigh = 78f, nextSourceLow = 58f,
        nextSourceIconRes = nextRainyIcon,
        nextSourceIsSunny = false,
        nextSourceIsRainy = true,
        nextSourceIsMixed = false,
        nextSourceCloudCoverRatioOverride = 0.8f,
    )
)

val captured = mutableListOf<DailyForecastGraphRenderer.BarDrawnDebug>()
DailyForecastGraphRenderer.renderGraph(
    context, days,
    widthPx = 800, heightPx = 400, bitmapScale = 1f, numColumns = 1,
    onBarDrawn = { captured.add(it) },
)

val nextBar = captured.first { it.barType == "NEXT_SOURCE" }
val primaryBar = captured.first { it.barType == "FUTURE" }

val expectedNextColor = WeatherConditionColors.forecastColor(
    isSunny = false, isRainy = true, isMixed = false, isNight = false,
)
val expectedPrimaryColor = WeatherConditionColors.forecastColor(
    isSunny = true, isRainy = false, isMixed = false, isNight = false,
)
assertEquals(expectedNextColor, nextBar.color)
assertEquals(expectedPrimaryColor, primaryBar.color)
assertNotEquals(
    "next-source bar color must differ from primary because conditions differ",
    nextBar.color, primaryBar.color,
)
```

**Why this test catches the bug:** Today the next-source draw block uses `day.isSunny`/`day.isRainy`/`day.isMixed` (primary flags) — `nextBar.color` would equal `primaryBar.color`. After the fix, the block uses `day.nextSourceIs*` — colors diverge. The `assertNotEquals` is the load-bearing assertion.

### Test files run via

```bash
./gradlew :app:testDebugUnitTest \
    --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest.prepareGraphDays_populatesNextSourceCloudCoverFromNextSourceHourly" \
    --tests "com.weatherwidget.widget.DailyForecastGraphRendererRoboTest.nextSourceBar_usesNextSourceConditionColor"
```

Existing tests stay green: the new `DayData` fields all default (`null` / `false`), so prior `DayData(...)` constructions in `DailyForecastGraphRendererRoboTest.kt`, `DailyForecastGraphRendererSizingTest.kt`, etc. are unaffected.

## Verification

1. `./gradlew :app:compileDebugKotlin` — type-checks new fields.
2. `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest*" --tests "com.weatherwidget.widget.DailyForecastGraphRenderer*"` — full unit suite green, both new tests pass.
3. `./gradlew :app:assembleDebug` + `adb -s RFCT71FR9NT install -r …` on the Samsung. Screenshot the widget; visually confirm:
   - On days where NWS forecasts sunny but Open-Meteo forecasts cloudy/rainy (or vice versa), the two bars in a column show **different** cloud-cover shading. The next-source bar's grey-bottom fraction reflects its own predicted cloud cover, not the primary's.
   - Today's 4-bar cluster still draws correctly (next-source bar gets adaptive segments only when `!day.isPast`, which is true for today).
   - Past days: next-source bar is solid (no adaptive segments, matching the history-red primary's solid style).
4. `grep -rn "nextSourceIconRes\|nextSourceCloudCoverRatioOverride" app/src/main app/src/test` — confirms every wiring site is touched and both tests reference the new fields.
