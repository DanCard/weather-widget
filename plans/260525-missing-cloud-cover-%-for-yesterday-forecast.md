# Plan: Grey cloud-cover segment missing on yesterday's forecast-overlay bar

## Context

**Bug (observed on device):** In the daily forecast view, yesterday — when it was "today" — showed a vertical bar with a grey **cloud-cover** segment at the bottom. Today, that same day is rendered as a **past day** with a **yellow forecast-overlay bar**, and the grey segment is gone.

**Desired behavior (confirmed with user):** The yellow forecast-overlay bar for a past day *should* show the grey cloud-cover segment — it represents the forecast for that day, so it should carry the forecast's cloud shading. The missing grey is a regression. Deliverable: **an integrated test that captures this + a fix**.

### How the grey segment works (verified)
- The grey bottom segment is drawn by `DailyForecastGraphRenderer.drawWeatherAdaptiveBar()` (`app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt:556`), which splits a bar into a colored top + grey/blue bottom.
- It is gated by: `allowAdaptiveSegments` **and** `shouldUseAdaptiveSegments(day)` (= `day.isMixed || (cloudCoverRatioOverride ?: 0f) > 0f`, line 605) **and** `day.iconRes != null` **and** `WeatherConditionColors.resolveMixedBarSplit(...)` returning non-null (returns non-null whenever `cloudCoverRatioOverride` or `cloudRatio(iconRes)` is non-null — `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt:89`).
- The **primary** bar deliberately suppresses grey on past days: `allowAdaptiveSegments = !day.isPast` (line 723) — actuals are meant to be a clean solid bar. **This is intentional and stays.**
- The **forecast overlay** (line 736–765) does **not** pass `allowAdaptiveSegments`, so it defaults to `true`. Therefore the overlay *will* draw grey **iff the past-day `DayData` still carries `cloudCoverRatioOverride` (or a mixed `iconRes`)**.

### Root-cause hypothesis (to confirm on device — see Step 2)
The renderer is already capable of shading the overlay; the grey disappears because the past-day `DayData` produced by `DailyViewLogic.prepareGraphDays` (`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`) loses its cloud condition. Candidate null points, in order of suspicion:
1. **`cloudCoverRatioOverride` resolves to null for yesterday.** `resolveNoonCloudCoverRatio` (line 628) filters hourly rows by `it.source == displaySource.id` **and** `it.cloudCover != null`, nearest to noon of that date. For a past day the overlay temps come from the *snapshot's* `displaySource`, but the cloud ratio is resolved against the same `displaySource` — a source mismatch (or NWS skyCover gap, `NwsForecastMapper.kt:83`) yields null.
2. **`iconRes` falls back to the actual observation's condition** (line 509: `actual.condition`, no cloud cover) when the forecast `weather`/snapshot entity for that past date is absent → non-mixed icon.
3. Both → `shouldUseAdaptiveSegments` false → solid yellow overlay.

Ruled out: the 72h hourly lookback (`WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS = 72`) comfortably covers yesterday's noon, so the window is not the cause.

---

## Step 1 — Make render-decision logging visible (per user request)

The diagnostic logs already exist but don't surface: `DailyForecastGraphRenderer.debug {}` (`:23`) calls `Log.d`, which R8 strips in non-debug builds. Make them survive:

- In `DailyForecastGraphRenderer.kt:23`, change the `debug {}` helper from `Log.d` to `Log.i` (non-stripped) so the existing `"Overlay color decision: ..."`, `"Bar color decision: ..."`, and `"... mixed bar geometry: ..."` lines emit in release/`installDebug` builds.
- In `DailyViewLogic.resolveNoonCloudCoverRatio` (`:628`), add one `Log.i` per resolved past date logging: source filter used, count of matched hourly rows with `cloudCover != null`, and the chosen ratio (or null). This pinpoints candidate #1.
- The existing `DailyViewLogic` `Log.d` lines (icon/forecast/weather presence per date) are sufficient for candidate #2; bump the icon-resolution path to log final `iconRes`, `isMixed`, and `cloudCoverRatioOverride` for the past day if not already evident.

## Step 2 — Reproduce + confirm exact cause on device

1. `./gradlew installDebug`
2. Ensure a Weather Widget showing the daily view (center = today, yesterday visible) on the emulator.
3. Trigger a re-render (tap/refresh), then screenshot:
   `adb exec-out screencap -p > /tmp/shot.png && convert /tmp/shot.png /tmp/shot.jpg` → read `/tmp/shot.jpg`.
4. Capture logs: `adb logcat -d | grep -E "DailyGraphRenderer|DailyViewLogic"`.
5. From the logs for yesterday's date, determine which is null/false: `cloudCoverRatioOverride`, `iconRes`'s mixed status, or both — selecting the fix below.

## Step 3 — Fix (lands in the layer Step 2 identifies)

Most likely (candidate #1): in `DailyViewLogic.prepareGraphDays`, resolve the past-day overlay's cloud ratio against **the source that actually supplies the overlay** (the `pastForecast` snapshot's source, line 378–384) rather than only `displaySource`, and fall back to `cloudRatio(iconRes)`/the snapshot's own condition so a mixed icon is produced. Keep the primary (red actuals) bar solid — do **not** touch `allowAdaptiveSegments = !day.isPast` at line 723.

If Step 2 instead shows the renderer dropping a populated ratio, the fix is renderer-side; but current reading says the renderer overlay path is already correct.

Exact edit will be finalized once Step 2 confirms the null point. Reuse existing helpers (`resolveNoonCloudCoverRatio`, `WeatherConditionColors.resolveMixedBarSplit`, `WeatherIconMapper.isMixed`) — no new rendering code.

## Step 4 — Integrated test (the core deliverable)

**Primary test — data layer** (`app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicCloudOverlayTest.kt`, Robolectric, `@Category(LongDuration::class)`):
- Drive `DailyViewLogic.prepareGraphDays` for a **past date** with: an actual observation (drives solid bar), a `displaySource` forecast **snapshot** with high+low (drives the yellow overlay), and **hourly forecasts including a ~noon entry with `cloudCover` set** for that date/source. Build inputs with the `TestData` factory (`app/src/test/java/com/weatherwidget/testutil/TestData.kt`).
- Assert the returned past-day `DayData`:
  - `isPast == true`, `dashedLineHigh != null && dashedLineLow != null` (overlay present), and
  - `cloudCoverRatioOverride != null` **and** the day is grey-capable (`isMixed || cloudCoverRatioOverride > 0` and `iconRes != null`) — i.e. `shouldUseAdaptiveSegments` would be true for the overlay.
- This test is **red before the fix, green after** (it reproduces the regression at the layer where the data is lost).

**Guard test — renderer layer** (extend an existing `DailyForecastGraphRenderer` Robolectric test, e.g. `DailyForecastGraphRendererRobolectricTest.kt`):
- Given a past-day `DayData` (`isPast = true`, `dashedLineHigh/Low` set, `cloudCoverRatioOverride = 0.6f`, mixed `iconRes`), render via mockk-constructed `Canvas` and verify the overlay bar at the overlay X is drawn as **two `drawLine` calls** (grey bottom full-height + colored top fraction) rather than a single solid line — following the existing `verify(exactly = n) { anyConstructed<Canvas>().drawLine(...) }` pattern used in the `TemperatureGraphRenderer*Test` files. Locks in that the overlay never re-acquires a `!day.isPast` suppression.

## Verification

1. Unit tests: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DailyViewLogicCloudOverlayTest"` and the renderer guard test — confirm red→green across the fix.
2. On device: re-`installDebug`, re-screenshot yesterday's overlay (convert PNG→JPG per CLAUDE.md) and confirm the yellow overlay now shows the grey cloud-cover segment at its bottom.
3. Logcat confirms `cloudCoverRatioOverride` non-null for yesterday after the fix.
4. Decide whether to keep the new `Log.i` instrumentation (Step 1) or revert it to `Log.d` once diagnosis is done — flag to user (memory note "Hourly Graph Label Overlap" shows the team's convention of removing temporary debug logging after monitoring).

## Files touched
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt` (logging level; possibly overlay guard if Step 2 redirects)
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (cloud-ratio/icon resolution for past-day overlay + logging) — primary fix site
- `app/src/test/java/com/weatherwidget/widget/handlers/DailyViewLogicCloudOverlayTest.kt` (new)
- `app/src/test/java/com/weatherwidget/widget/DailyForecastGraphRendererRobolectricTest.kt` (guard test)
