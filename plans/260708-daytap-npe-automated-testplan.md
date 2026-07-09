# Automated Test Plan — Day-Tap NPE (source-gap crash)

**Bug:** Tapping a day on the widget silently did nothing. `WidgetIntentRouter: handleSetView
failed` with `java.lang.NullPointerException` at `CurrentTemperatureResolver.computeSmoothedForecasts`
(`shared/.../CurrentTemperatureResolver.kt`, formerly line 66).

**Root cause:** `pickBestForecast` returns **null** for any hour bucket containing rows from
neither the displayed source nor `GENERIC_GAP` (per-source strictness introduced in `5c34bd7f`).
The old code asserted the pick with `!!`. Any provider gap in the loaded window — the emulator
repro had NWS covering 20 of 22 hour buckets while other sources covered all 22, with NWS
displayed — crashed the day-tap path before the view mode could flip.

**Fix:** null picks are dropped via `mapNotNull` (with a VERBOSE dropped-bucket breadcrumb).
Guard rule going forward: **never `!!` a grouped per-source pick** — buckets legitimately
resolve to null.

---

## Layer 1 — Pure unit tests (`:shared`, plain JUnit, no Android) ✅ implemented

`shared/src/test/kotlin/com/weatherwidget/widget/CurrentTemperatureResolverSourceGapTest.kt`

| Case | Asserts |
|------|---------|
| Bucket without display-source rows | bucket dropped, others returned, **no crash** (the exact on-device crash shape) |
| No rows for display source at all | empty map, no crash |
| GENERIC_GAP rows in a gap bucket | gap row fills the bucket |
| Duplicate rows in one bucket | latest `fetchedAt` wins |
| Empty input | empty map |
| Full coverage | nothing dropped, no NaN |

Run:
```bash
./gradlew :shared:test --tests "com.weatherwidget.widget.CurrentTemperatureResolverSourceGapTest"
```

## Layer 2 — Robolectric render-path test (app module) ✅ implemented

`app/src/test/java/com/weatherwidget/widget/handlers/TemperatureViewHandlerSourceGapRoboTest.kt`

Drives the same path the widget takes after a day tap
(`TemperatureViewHandler.updateWidget` → `TemperatureStateResolver.resolve` →
`computeSmoothedForecasts`) against a Room DB seeded with the production crash shape:
full OPEN_METEO coverage, NWS missing two mid-window hours, NWS displayed. Includes a fixture
sanity assert that the window really contains NWS-less buckets — a full-coverage fixture would
pass even with the `!!` bug (false-safety guard, same idea as the disjoint-cluster note in
`WidgetIntentRouterHeaderTempRoboTest`).

Asserts: `updateWidget` completes (old code threw here) and the applied RemoteViews renders a
non-empty current temp.

Run:
```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.TemperatureViewHandlerSourceGapRoboTest"
```

## Layer 3a — Robolectric broadcast-level test (app module) ✅ implemented

`app/src/test/java/com/weatherwidget/widget/WeatherWidgetProviderDayTapSourceGapRoboTest.kt`

Drives the FULL day-tap chain: `ACTION_DAY_CLICK` intent → `WeatherWidgetProvider.onReceive` →
`goAsync`/`launchAsync` → `handleDayClickAction` → `handleSetView` → `refreshGraphView` → render.
Uses the `provider.scope` seam + `StandardTestDispatcher` (harness from
`WeatherWidgetProviderNoHourlyRoboTest`); `goAsync()` returns null on direct `onReceive` and
`finishPendingResultSafely` tolerates it. AppWidgetManager is static-mocked with sized options.

Key subtlety this layer catches that mode-flip asserts miss: with the old bug, **the stored view
mode still flipped to TEMPERATURE** — the NPE was caught after the state write, so only the
RemoteViews push was lost. The load-bearing asserts are the captured `updateAppWidget` call and
the breadcrumbs below.

Run:
```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.WeatherWidgetProviderDayTapSourceGapRoboTest"
```

## Layer 3b — Instrumented device test (emulator ONLY) ✅ implemented

`app/src/androidTest/java/com/weatherwidget/widget/DayTapSourceGapInstrumentedTest.kt`

Same broadcast + fixture, but with the real framework: actual Canvas/Bitmap rendering, real
RemoteViews, real display metrics, real `AppWidgetManager` (unbound test widget ID → system
no-ops the push, which is fine — the render work happens before it). Runs with the provider's
real IO scope and awaits the persisted breadcrumbs (15s bound).

Run (never `connectedDebugAndroidTest` — it removes widgets from physical devices):
```bash
./scripts/emulator-tests.sh -c com.weatherwidget.widget.DayTapSourceGapInstrumentedTest
```

### Observability fix that layers 3a/3b assert on

`WidgetIntentRouter.handleSetView` used to swallow failures as a logcat-only `Log.e` — which is
why the live bug was invisible to app_logs error sweeps. It now persists:
- `SET_VIEW_RENDER_OK` (success-only, parallel to `WIDGET_RENDER_OK` on the refresh path)
- `SET_VIEW_FAIL` (ERROR level, carries exception class + message)

Query on any device: `SELECT * FROM app_logs WHERE tag LIKE 'SET_VIEW_%'` (timestamp is epoch
millis — use `'localtime'`).

## Layer 4 — On-device smoke (manual; release-time checklist, not per-bug)

With 3a/3b in place, the only remaining Robolectric/instrumented blind spots are launcher-side:
the physical tap → `PendingIntent` dispatch (launchers can eat taps), cross-process RemoteViews
transport/reapply, and system timing (10s `goAsync` ANR deadline). Verify once per release, not
per bug:

1. Install on an emulator (NEVER `connectedDebugAndroidTest`; it removes widgets from physical
   devices): `ANDROID_SERIAL=emulator-5556 ./gradlew installDebug`
2. Create a source gap for the displayed source (or rely on natural provider gaps):
   ```sql
   -- adb pull the DB (python3 scripts/backup_databases.py), or on the emulator:
   DELETE FROM hourly_forecasts
   WHERE source='NWS'
     AND dateTime IN (SELECT dateTime FROM hourly_forecasts WHERE source='NWS'
                      ORDER BY dateTime LIMIT 2);
   ```
3. `adb logcat -c`, tap a day column on the widget, then:
   ```bash
   adb logcat -d | grep -E 'handleSetView failed|NullPointerException|CLICK_TIMING'
   ```
   Expect `CLICK_TIMING ... branch=hourly` and **no** `handleSetView failed`; widget shows the
   hourly curve. Optionally confirm the fix engaged:
   `adb logcat -d | grep 'computeSmoothedForecasts: dropped'` (VERBOSE, ephemeral-sink only).

Verified 2026-07-08 on emulator-5556 (the original repro device): day tap opens the hourly view,
`CLICK_TIMING branch=hourly total=383ms`.

## CI / routine invocation

All automated layers run in the standard suites — no special wiring needed:
- `./scripts/unit-tests.sh` runs layers 1, 2, and 3a: Layer 1 via its parallel `:shared:test`
  invocation; 2 and 3a in the **Long** bucket (`@Category(LongDuration)`; Short/Medium/Long all
  run by default)
- Direct: `./gradlew :shared:test` (Layer 1), `./gradlew testDebugUnitTest` (Layers 2, 3a)
- `./scripts/emulator-tests.sh` includes Layer 3b in every full emulator run
