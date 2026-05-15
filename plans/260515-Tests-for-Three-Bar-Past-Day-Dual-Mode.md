# Tests for Three-Bar Past-Day Dual-Mode

## Context

The previous task added a third forecast bar on past days when dual API mode is on — pulling `nextSource` snapshots from `forecast_snapshots` and rendering them opposite the displaySource overlay. The fix touched three layers (fetch, transform, render). The user is now asking whether automated tests should cover it. The project's testing memory says: **no mocking, prefer pure-function extraction; Android glue is verified on-device.** That means:

- ✅ Worth testing as pure unit tests: the **transform** layer (`DailyViewLogic.prepareGraphDays`) and the **render** layer (`DailyForecastGraphRenderer.drawDayBars` via Robolectric Canvas).
- ❌ Not worth a unit test: the **fetch** layer (`WeatherWidgetProvider.activeSources` construction) — it's a `flatMap` over WidgetStateManager queries against a context-bound singleton; testing would require either Robolectric+Context setup or extracting a trivial pure helper. Existing on-device verification (already done) is the right level.

Existing tests verified during exploration:

- `DailyViewLogicTest.kt:1330` — `prepareGraphDays populates next-source cloud cover...` covers future-day dual-mode data flow (from `nextSourceWeatherByDate`, not snapshots).
- `DailyForecastGraphRendererRoboTest.kt:82` — `nextSourceBar_usesNextSourceConditionColor` covers next-source bar color/position on a non-past day.
- `DailyForecastGraphRendererRoboTest.kt:138` — `renderGraph_withForecastBarMode_showsForecastOverlayForHistoryDay` covers single-mode past-day forecast overlay.

Neither file covers **past-day dual-mode** — the new path. Two new tests close that gap.

## Approach

### Test 1 — `DailyViewLogicTest.kt`: past-day nextSource snapshot wiring

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt`

Add a new `@Test` (place it near the existing next-source test at line 1330):

```kotlin
@Test
fun `prepareGraphDays populates pastNextSource from forecast snapshot for past day in dual mode`() {
    val now = LocalDateTime.of(2030, 6, 15, 12, 0)
    val today = now.toLocalDate()
    val yesterday = today.minusDays(1)
    val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

    // Actual observation for yesterday (drives the red actuals bar = day.high/low).
    val weatherByDate = mapOf(
        yesterday to createWeather(
            date = yesterdayStr,
            source = WeatherSource.NWS.id,
            highTemp = 70f,
            lowTemp = 50f,
            isActual = true,
        )
    )
    // Two forecast snapshots for yesterday, made the day before yesterday.
    val nwsSnap   = createWeather(date = yesterdayStr, source = WeatherSource.NWS.id,        highTemp = 72f, lowTemp = 48f)
    val meteoSnap = createWeather(date = yesterdayStr, source = WeatherSource.OPEN_METEO.id, highTemp = 75f, lowTemp = 46f)
    val forecastSnapshots = mapOf(yesterday to listOf(nwsSnap, meteoSnap))

    val result = DailyViewLogic.prepareGraphDays(
        now = now,
        centerDate = yesterday,
        today = today,
        weatherByDate = weatherByDate,
        forecastSnapshots = forecastSnapshots,
        numColumns = 1,
        displaySource = WeatherSource.NWS,
        skipYesterday = false,
        skipHistory = false,
        hourlyForecasts = emptyList(),
        nextSourceWeatherByDate = emptyMap(),   // intentionally empty — past-day path must read snapshots
        nextSource = WeatherSource.OPEN_METEO,
    )

    val day = result.first { it.date == yesterday }

    // Primary forecast overlay = NWS snapshot.
    assertEquals(72f, day.forecastHigh)
    assertEquals(48f, day.forecastLow)
    // Next-source bar = Open-Meteo snapshot — even though nextSourceWeatherByDate is empty.
    assertEquals(75f, day.nextSourceHigh)
    assertEquals(46f, day.nextSourceLow)
    // Actuals stay distinct.
    assertEquals(70f, day.high)
    assertEquals(50f, day.low)
}

@Test
fun `prepareGraphDays leaves pastNextSource null when nextSource has no snapshot`() {
    val now = LocalDateTime.of(2030, 6, 15, 12, 0)
    val today = now.toLocalDate()
    val yesterday = today.minusDays(1)
    val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)

    val weatherByDate = mapOf(yesterday to createWeather(date = yesterdayStr, source = WeatherSource.NWS.id, highTemp = 70f, lowTemp = 50f, isActual = true))
    val forecastSnapshots = mapOf(
        yesterday to listOf(createWeather(date = yesterdayStr, source = WeatherSource.NWS.id, highTemp = 72f, lowTemp = 48f))
    )

    val day = DailyViewLogic.prepareGraphDays(
        now = now, centerDate = yesterday, today = today,
        weatherByDate = weatherByDate, forecastSnapshots = forecastSnapshots, numColumns = 1,
        displaySource = WeatherSource.NWS, skipYesterday = false, skipHistory = false,
        hourlyForecasts = emptyList(),
        nextSourceWeatherByDate = emptyMap(), nextSource = WeatherSource.OPEN_METEO,
    ).first { it.date == yesterday }

    // Without a snapshot for nextSource the renderer should skip the third bar.
    assertNull(day.nextSourceHigh)
    assertNull(day.nextSourceLow)
}
```

**Reuses:** the file's existing `createWeather()` helper (line 1442), `DailyViewLogic.prepareGraphDays` signature, and JUnit 4 / Robolectric setup already at the top of the file.

### Test 2 — `DailyForecastGraphRendererRoboTest.kt`: three bars on past day in dual mode

**File:** `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt`

Add a new `@Test` next to `renderGraph_withForecastBarMode_showsForecastOverlayForHistoryDay` (line 138):

```kotlin
@Test
fun pastDay_dualMode_drawsThreeBars_overlayLeftOfActuals_nextSourceRightOfActuals() {
    // History day with: actuals (red), displaySource snapshot (yellow overlay), nextSource snapshot.
    val pastDate = LocalDate.of(2026, 2, 1)
    val days = listOf(
        DailyForecastGraphRenderer.DayData(
            date = pastDate,
            label = "Sat",
            high = 65f,             // actuals
            low  = 45f,
            isPast = true,
            forecastHigh = 67f,     // displaySource snapshot
            forecastLow  = 44f,
            nextSourceHigh = 70f,   // nextSource snapshot
            nextSourceLow  = 42f,
        ),
        DailyForecastGraphRenderer.DayData(date = pastDate.plusDays(1), label = "Today", high = 68f, low = 48f, isToday = true),
        DailyForecastGraphRenderer.DayData(date = pastDate.plusDays(2), label = "Mon",   high = 70f, low = 50f),
    )

    val bars = render(days)
    val pastBars = bars.filter { it.date == pastDate }

    val history       = pastBars.single { it.barType == "HISTORY" }
    val overlay       = pastBars.single { it.barType == "FORECAST_OVERLAY" }
    val nextSourceBar = pastBars.single { it.barType == "NEXT_SOURCE" }

    // Layout: [overlay LEFT] [history CENTER] [next-source RIGHT]
    assertTrue(
        "displaySource forecast overlay must sit LEFT of actuals on past day",
        overlay.centerX < history.centerX,
    )
    assertTrue(
        "next-source bar must sit RIGHT of actuals on past day",
        nextSourceBar.centerX > history.centerX,
    )
}

@Test
fun pastDay_singleMode_keepsForecastOverlayLeftOfActuals() {
    // Sanity check: when nextSource is absent we still get overlay on the left of past-day actuals
    // (this is the layout change we explicitly made; guards against a regression that restores
    //  the old right-side overlay position).
    val pastDate = LocalDate.of(2026, 2, 1)
    val days = listOf(
        DailyForecastGraphRenderer.DayData(
            date = pastDate, label = "Sat",
            high = 65f, low = 45f,
            isPast = true,
            forecastHigh = 67f, forecastLow = 44f,
        ),
        DailyForecastGraphRenderer.DayData(date = pastDate.plusDays(1), label = "Today", isToday = true, high = 68f, low = 48f),
    )
    val pastBars = render(days).filter { it.date == pastDate }
    val history = pastBars.single { it.barType == "HISTORY" }
    val overlay = pastBars.single { it.barType == "FORECAST_OVERLAY" }
    assertTrue(overlay.centerX < history.centerX)
}
```

**Reuses:** the file's `render()` helper (line ~50, returns `List<BarDrawnDebug>`) and `BarDrawnDebug` debug struct (`date`, `barType`, `centerX`, `color`). No new fixtures.

## Files to Modify

- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicTest.kt` — append two new `@Test` cases for past-day nextSource snapshot wiring (positive + negative path).
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRoboTest.kt` — append two new `@Test` cases for three-bar past-day rendering and single-mode past-day overlay-left guard.

No production code touched. No new dependencies, no mocks.

## Out of Scope

- `WeatherWidgetProvider.activeSources` flatMap — Android-coupled glue, verified on-device per the codebase testing strategy (`testing-strategy.md`).
- Visual color regression (paint colors for the three bars) — already covered by `nextSourceBar_usesNextSourceConditionColor` (line 82).
- DAO query coverage — covered by existing repository tests; we're not changing the DAO.

## Verification

1. Run the new unit tests:
   ```
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest.prepareGraphDays populates pastNextSource*"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicTest.prepareGraphDays leaves pastNextSource*"
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.DailyForecastGraphRendererRoboTest.pastDay_*"
   ```
2. Confirm all four tests pass.
3. Run the full unit test suite to confirm no regression in adjacent tests:
   ```
   ./gradlew testDebugUnitTest
   ```
4. (Optional) Temporarily revert the production change in `DailyViewLogic.kt` (the `pastNextSourceHigh/Low` block) and confirm Test 1's positive case **fails** — proves the test actually exercises the new code path.
