# Fix: Current Temp Changes When Scrolling Temperature Graph

## Context

In the temperature graph view (and likely precipitation/cloud-cover views as well), the **current temperature** label in the top-left corner of the widget shifts as the user scrolls left/right through history/forecast days. Per `CLAUDE.md`, this label should always display *today's* current (interpolated) temperature regardless of which day the graph is centered on. The bug makes the widget look broken — the user sees the "current" reading change as they navigate.

**Root cause:** `WidgetIntentRouter.updateHourlyViewWithData()` loads two hourly-forecast windows:
1. `hourlyForecasts` — a **graph-window** list centered on the user's scrolled `centerTime` (`WidgetIntentRouter.kt:836-844`)
2. `currentTempHourlyForecasts` — a **NOW-centered** list, loaded specifically for current-temp resolution (`WidgetIntentRouter.kt:875-881`)

The NOW-centered list is correctly used to compute `graphStyleObs` (`WidgetIntentRouter.kt:887-894`), but the **graph-window list** is then passed into `TemperatureViewHandler.updateWidget(hourlyForecasts = hourlyForecasts, …)` (`WidgetIntentRouter.kt:940`). Inside `TemperatureStateResolver.kt:153-164`, `CurrentTemperatureResolver.resolve()` is called with that graph-window list, so when the user scrolls to e.g. yesterday or two days ahead, the resolver looks for "now" inside a list that does not contain the current hour, and either returns a degraded/null value or extrapolates from the wrong end of the window. The top-left text is then bound from this wrong value.

## Fix

Thread the existing NOW-centered `currentTempHourlyForecasts` through to `CurrentTemperatureResolver.resolve()` as a separate parameter, leaving the graph-window list intact for graph rendering.

## Files to Modify

1. **`app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`**
   - In `updateHourlyViewWithData()` (~line 856), pass `currentTempHourlyForecasts` (already loaded at line 875-881) as a new argument to `TemperatureViewHandler.updateWidget()`, `PrecipViewHandler.updateWidget()`, and `CloudCoverViewHandler.updateWidget()`.

2. **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureViewHandler.kt`**
   - Add `currentTempHourlyForecasts: List<HourlyForecastEntity>` parameter to `updateWidget()` (~line 39). Forward it to `TemperatureStateResolver`.

3. **`app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt`**
   - Same parameter addition (~line 57). Forward to its state resolver path.

4. **`app/src/main/java/com/weatherwidget/widget/handlers/CloudCoverViewHandler.kt`**
   - Same parameter addition (~line 100). Forward to its state resolver path.

5. **`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureStateResolver.kt`**
   - Accept `currentTempHourlyForecasts` (~line 100 area). Use it — **not** `hourlyForecasts` — when calling `CurrentTemperatureResolver.resolveQuick()` (line 139) and `CurrentTemperatureResolver.resolve()` (line 156). Keep `hourlyForecasts` for graph rendering (`loadGraphHours` on line 100-116) unchanged.

## What NOT to Change

- `loadGraphWindowHourlyForecasts()` and the `centerTime`-based graph rendering path — they are correct.
- `loadCurrentTempResolutionHourlyForecasts()` — it already loads the right window.
- `CurrentTemperatureResolver` internals — the resolver is fine, it just needs the right input.
- `isNowLineVisible` (`TemperatureStateResolver.kt:169`) — must still be derived from `graphHours`, since it controls whether the NOW indicator appears in the graph and that *should* depend on the scrolled view.

## Automated Tests

This class of bug ("current temp changes when it shouldn't") recurs often, so we add regression tests alongside the fix. Per the project's testing strategy (no mocking framework — prefer pure function extraction), the tests target pure functions.

### Test 1: `CurrentTemperatureResolver` invariance across scroll offsets
**File:** `app/src/test/java/com/weatherwidget/widget/CurrentTemperatureResolverTest.kt` (create if absent; otherwise add a new `@Test`)

**What it asserts:** Given a fixed NOW-centered `hourlyForecasts` fixture, the resolver's `displayTemp` must be **identical** whether the caller is "viewing today," "viewing yesterday," or "viewing +2 days" — because `centerTime`/scroll offset is a *graph* concern, not a current-temp concern.

**Shape:**
```kotlin
@Test
fun `displayTemp is invariant across scroll offsets when given NOW-centered forecasts`() {
    val now = LocalDateTime.of(2026, 4, 13, 14, 30)
    val nowCenteredHourly = fixtureHourlyAroundNow(now)  // ±6h around now
    val results = listOf(-2L, -1L, 0L, 1L, 2L).map { offsetDays ->
        // centerTime is irrelevant to CurrentTemperatureResolver — this test
        // guards that it stays irrelevant. If a future refactor threads centerTime
        // into the resolver, this test fails loudly.
        CurrentTemperatureResolver.resolve(
            now = now,
            displaySource = WeatherSource.NWS,
            hourlyForecasts = nowCenteredHourly,
            lastObservedTemp = null,
            observedAt = null,
            storedDeltaState = null,
            currentLat = 37.42,
            currentLon = -122.08,
            smoothedForecasts = emptyList(),
        ).displayTemp
    }
    assertEquals(1, results.toSet().size) // all offsets produced same temp
}
```

### Test 2: Extract and test `resolveHeaderState(graphWindowHourly, nowCenteredHourly, centerTime)`
The real bug is not in the resolver itself — it's that `TemperatureStateResolver` passes the *graph-window* list into the resolver. A unit test on the resolver alone cannot catch that wiring error. The fix:

1. In `TemperatureStateResolver.kt`, extract the header-state resolution into a pure function:
   ```kotlin
   internal fun resolveHeaderCurrentTemp(
       now: LocalDateTime,
       displaySource: WeatherSource,
       graphWindowHourly: List<HourlyForecastEntity>,       // for graph rendering only
       nowCenteredHourly: List<HourlyForecastEntity>,       // for current temp only
       centerTime: LocalDateTime,
       lastObservedTemp: Float?,
       observedAt: Long?,
       // …existing delta-state params
   ): CurrentTemperatureResolution
   ```
   The function asserts — and the test verifies — that `CurrentTemperatureResolver.resolve()` is called with `nowCenteredHourly`, never `graphWindowHourly`.

2. **File:** `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureStateResolverTest.kt`

   **What it asserts:** Build two fixtures — a `graphWindowHourly` that centers on `now.plusDays(2)` (no current-hour entries) and a `nowCenteredHourly` that does include the current hour. Call `resolveHeaderCurrentTemp(...)` with `centerTime = now.plusDays(2)`. Assert `displayTemp` equals the expected NOW value from `nowCenteredHourly` — not null, not extrapolated from the +2d window. Repeat for `centerTime = now.minusDays(1)` and `centerTime = now`.

   This catches the *routing* bug directly: if a future refactor accidentally wires the graph-window list back into the current-temp path, the test fails.

### Test 3: Robolectric router-level regression test (**required**, not optional)

**Why this is required:** An existing test — `TemperatureViewHandlerCenterTimeTest.kt` — *already tries* to assert that the header temp comes from NOW, not `centerTime`, when scrolled to +2 days. It passes today despite the bug. The reason: it calls `TemperatureViewHandler.updateWidget()` directly with a **single hourly list containing both** the NOW-hour points (66°) and the center-time points (52°/58°). The resolver finds the NOW points inside that super-set and returns the right answer. But in production, `WidgetIntentRouter.updateHourlyViewWithData` passes a `loadGraphWindowHourlyForecasts(centerTime = now.plusDays(2), ...)` result that **does not contain the NOW hours** — which is exactly why the header temp goes wrong. The handler-level test's fixture is unrealistically complete; it cannot catch the wiring bug.

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/WidgetIntentRouterHeaderTempRoboTest.kt` (new, alongside existing `WidgetIntentRouterRobolectricTest.kt`)

**Approach:**
1. Use the existing `TestDatabase.create()` helper (see `app/src/test/java/com/weatherwidget/testutil/TestDatabase.kt`) to get a real Room DB on Robolectric.
2. Seed `HourlyForecastDao` with two disjoint clusters:
   - NOW cluster: hours around `LocalDateTime.now()`, all at 66°F.
   - +2-day cluster: hours around `now.plusDays(2)`, all at 52°F.
3. Seed `WidgetStateManager` with `hourlyOffset = 48` (or equivalent) so `centerTime = now.plusDays(2)`.
4. Capture the `RemoteViews` passed to `AppWidgetManager.updateAppWidget(...)` via MockK slot (same pattern as `TemperatureViewHandlerCenterTimeTest.kt:56-57`).
5. Inflate the captured `RemoteViews` and read the `R.id.current_temp` TextView.
6. Assert: `current_temp` text displays 66°, **not** 52° and **not** empty/null.
7. Parameterize/repeat for offsets `{-48h, -24h, 0, +24h, +48h}` and assert the value is invariant across all five.

This test fails **today** against the current code, and must pass after the fix. It is the only test in the plan that actually reproduces the production bug path.

### Also: update the existing `TemperatureViewHandlerCenterTimeTest`

Once the handler takes `currentTempHourlyForecasts` as a separate parameter, split the fixture in that existing test so the NOW-hour points are passed only via `currentTempHourlyForecasts` and the +2-day points only via `hourlyForecasts`. This prevents the test from accidentally "passing because the super-set contains both" — i.e., converts it from a weak assertion into a real regression guard at the handler layer too.

## Verification

1. Build and install: `./gradlew installDebug`
2. Add a Weather Widget to the home screen (or use an existing one) on the emulator.
3. Switch to the temperature graph view (hourly view).
4. Note the current temp shown in the top-left corner.
5. Tap the left/right navigation arrows to scroll back to yesterday and forward to tomorrow / +2 days.
6. **Expected:** top-left current temp stays constant across all scroll positions. The graph contents and the day-name in the top-right indicator change as before.
7. Repeat for the precipitation and cloud-cover view modes (same code path through `updateHourlyViewWithData`).
8. Scroll back to today and confirm the NOW indicator line still appears in the graph (regression check on `isNowLineVisible`).
9. Pull `adb logcat` and confirm no `NET_FETCH_FAIL` / null-resolution warnings around the navigation taps.

## Notes

- The fix is purely plumbing — no new logic, no new queries. The correct hourly list is already loaded at `WidgetIntentRouter.kt:875-881`; we just have to route it to the right consumer.
- The same bug pattern likely affects `PrecipViewHandler` and `CloudCoverViewHandler` since they share `updateHourlyViewWithData()`. Fix all three handler entry points in one pass.
