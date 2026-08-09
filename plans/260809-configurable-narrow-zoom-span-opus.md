# Configurable hourly zoom span (4–8h) for the NARROW view

## Context

The hourly graph's zoom is a three-stage cycle defined once in
`shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomStage.kt`. The zoomed-in stage,
`NARROW`, is hardcoded to `backHours = 2, forwardHours = 2` (a 4-hour window) with `navJump = 1`.
Those numbers are baked into the enum constructor, so the tight view is the same 4 hours for
everyone with no way to widen it.

The goal is a user setting — "zoom view amount", 4 to 8 hours — that controls how much time the
narrow hourly view shows, with the nav-arrow scroll step scaling with it: **1 hour at 4–6h,
2 hours at 7–8h**. It goes above the "Weather Data Sources" section on the settings screen, and
per the platform decision it lands on **both Android and desktop**, with the rule itself living in
`:shared` so the two can't drift.

**Default is 5 hours** (3 back / 2 forward, 1-hour scroll) — not the current 4. See "Default change"
below for who this moves.

**Assumption (per "don't worry about splitting now evenly"):** odd spans split back-heavy —
`back = ceil(n/2)`, `forward = floor(n/2)` (5h → 3 back / 2 forward). This matches `THREE_DAY`
(48/24) and desktop's existing "wider views lean into history" rule.

### The structural problem to solve

`ZoomStage` is an enum, so it cannot carry a runtime-configurable span. Today ~25 main-source call
sites read `zoom.backHours` / `zoom.navJump` / `zoom.labelInterval` / `zoom.smoothIterations`
straight off the enum value. Simply adding a preference lookup at each of those sites would scatter
config reads through the render pipeline and leave every missed site silently pinned to the old 2/2.

The fix is to separate the two things the enum currently conflates:

- **`ZoomStage`** — the user's discrete *selection*. Identity only: persisted by name, cycled by tap,
  compared with `==`. Keeps `next()`, `DEFAULT`.
- **`ZoomWindow`** — the *resolved geometry* of the current view, produced from a stage plus the
  configured narrow span.

Crucially, the five geometry properties are **removed** from `ZoomStage`. That makes the compiler,
not review discipline, guarantee every geometry consumer resolves a window.

---

## Changes

### 1. `:shared` — the rule (new file + edit)

**New `shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomWindow.kt`:**

```kotlin
object HourlyZoomRules {
    const val MIN_NARROW_SPAN_HOURS = 4
    const val MAX_NARROW_SPAN_HOURS = 8
    const val DEFAULT_NARROW_SPAN_HOURS = 5          // was an implicit 4 before this setting

    fun clampNarrowSpan(hours: Int): Int = hours.coerceIn(MIN..MAX)

    /** Nav-arrow step. Single source of truth for Android's navJump and desktop's navJumpHours. */
    fun navJumpHours(spanHours: Int): Int = when {
        spanHours <= 6 -> 1
        spanHours <= 8 -> 2
        else -> (spanHours / 2).coerceAtLeast(1)     // preserves desktop's half-span rule above 8h
    }
}

data class ZoomWindow(
    val stage: ZoomStage,
    val backHours: Long,
    val forwardHours: Long,
    val navJump: Int,
    val labelInterval: Int,
    val smoothIterations: Int,
) { val totalSpanHours: Long get() = backHours + forwardHours }
```

Property names deliberately match today's enum fields, so the ~25 `zoom.backHours`-style reads
compile unchanged — only the declared *types* move.

**Edit `ZoomStage.kt`:** drop the five constructor properties and `totalSpanHours`; keep the three
constants, `next()`, `DEFAULT`. Add:

- `fun window(narrowSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS): ZoomWindow` —
  `WIDE` and `THREE_DAY` return their existing fixed values; `NARROW` derives
  `back = ceil(n/2)`, `forward = floor(n/2)`, `navJump = HourlyZoomRules.navJumpHours(n)`,
  `labelInterval = 1`, `smoothIterations = 1`.
- `nearestByTotalSpan(totalSpanHours: Int, narrowSpanHours: Int = DEFAULT)` — same minBy, now over
  resolved windows (desktop's click-snap).

Keep the existing "declaration order is load-bearing" comment; persistence is unaffected.

### 2. Android — preference storage

- `app/.../widget/WeatherDisplayPreferences.kt`: `KEY_HOURLY_NARROW_SPAN = "hourly_narrow_span_hours"`,
  plus getter/setter clamped through `HourlyZoomRules.clampNarrowSpan`. Mirrors the existing
  `KEY_PERSONAL_STATION_DISCOUNT` pair exactly.
- `app/.../widget/WidgetStateManager.kt`: `getNarrowZoomSpanHours()` / `setNarrowZoomSpanHours()`
  delegating to `displayPreferences`, alongside `getPersonalStationDiscountPercent()`.

### 3. Android — resolution point and plumbing

- **`WidgetStateManager.kt`**: `getZoomLevel(widgetId)` splits into
  `getZoomStage(widgetId): ZoomStage` (persistence/cycle) and
  `getZoomWindow(widgetId): ZoomWindow` (geometry, resolved with the pref). `getNavJump` reads the
  window. `setZoomLevel`/`cycleZoomLevel` stay on `ZoomStage`. Retire the
  `typealias ZoomLevel = ZoomStage` — the two concepts now need distinct names.
- **`WidgetPresentationStateStore.kt`**: constructor gains `narrowSpanHours: () -> Int` (supplied by
  `WidgetStateManager`, same prefs instance). `navigateHourly` and `resolveHourlyCenterTime` resolve a
  window instead of reading the enum. `WidgetPresentationState.zoom` stays a `ZoomStage`.
- **Pipeline signature swap** — `zoom: ZoomLevel` → `zoom: ZoomWindow`, and identity checks
  `zoom == ZoomLevel.NARROW` → `zoom.stage == ZoomStage.NARROW`. Same mechanical pattern in each:
  `handlers/TemperatureStateResolver.kt`, `TemperatureViewHandler.kt`, `TemperatureHourDataBuilder.kt`,
  `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`, `GraphDataLoader.kt`, `TemperatureTouchTargets.kt`,
  `TemperatureTextMode.kt`, `TemperatureWidgetState.kt`, `HourlyBottomZoneHelper.kt`,
  `SourceStalenessProbe.kt`, `DailyHeaderBinder.kt`, `GraphInteractionRenderer.kt`,
  plus `widget/HourlyTouchZoneMapper.kt` and `widget/WidgetRenderer.kt`.
  Logging that prints `zoom.name` becomes `zoom.stage.name`.

The persisted zoom key still stores a stage name, so saved widget state is untouched.

**Default change.** The span pref is absent on every existing install too, so a bare
`DEFAULT_NARROW_SPAN_HOURS = 5` moves *current* users from 4h to 5h as well, not just new installs.
That is the recommended behavior — it is a small, visible improvement and needs no migration code.
If you want existing installs pinned at 4h instead, say so and the plan adds a one-time backfill:
on first read, write `4` when the pref is absent *and* any widget already exists. Same question on
desktop, where an existing `config.json` simply lacks the key.

### 4. Android — settings UI

- `app/src/main/res/values/strings.xml`: `hourly_zoom_title` ("Hourly Zoom"),
  `hourly_zoom_description`, `hourly_zoom_min` / `hourly_zoom_max` end labels.
- `app/src/main/res/layout/activity_settings.xml`: new section inserted **immediately above** the
  `api_sources_title` TextView (currently ~line 199, right after the Today Column section). Copies
  the Personal Weather Stations block structure: bold title, secondary description, `bg_surface_card`
  container with `hourly_zoom_value` TextView + `hourly_zoom_seekbar`
  (`android:max="4"`, progress = span − 4, so the 5h default sits at progress 1) + min/max end labels.
- `app/.../ui/SettingsActivity.kt`: `setupHourlyZoomSpan()` modeled on `setupPersonalStationDiscount()`.
  Label reads just the span, e.g. `"5 hours"`. Persists in `onStopTrackingTouch` and calls the
  existing `repaintWidgets()` (hoisted from a local fun in `setupViews` to a private member).
  All five new strings are translated into the 19 shipped locales (`LocaleResourceParityTest`
  requires every translatable base key to exist in each).

### 5. Desktop

- `DesktopConfig.kt`: `val narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS`.
- `DesktopGraphUtils.kt`: `zoomFactorForStage(stage, narrowSpanHours)` inverts against the resolved
  window's `backHours`; `navJumpHours(zoomFactor)` delegates to `HourlyZoomRules.navJumpHours(span)`
  so both platforms share one step rule.
- `Main.kt` (~lines 1042–1056, 1135–1147): pass `config.narrowZoomSpanHours` into
  `nearestByTotalSpan` and `zoomFactorForStage`.
- `SettingsWindow.kt`: new `SettingsCard("Hourly Zoom")` with a `Slider(valueRange = 4f..8f, steps = 3)`
  and the same label text, placed directly above the existing `SettingsCard("Weather Data Sources")`
  (~line 197), matching the `PersonalStationDiscount` composable's shape.

**Scope note:** desktop keeps its continuous wheel/drag zoom and its `MIN_BACK_HOURS = 2` floor
untouched. The setting governs the stage a *click* snaps to, which is desktop's equivalent of the
Android tap cycle.

---

## Tests — Robolectric-first

New behavior is pinned by fast tests (`:shared` JVM + Robolectric). The instrumented suite gets
compile-only type swaps, so validating this feature does **not** require
`./scripts/emulator-tests.sh`.

### New

- **`shared/src/test/.../ZoomWindowTest.kt`** (pure JVM) — table-drive spans 4..8 asserting the
  back/forward split and navJump: 4→2/2/1, 5→3/2/1, 6→3/3/1, 7→4/3/2, 8→4/4/2. Clamping of 3 and 9.
  `WIDE`/`THREE_DAY` windows unchanged at every span. Default resolves to the 5h row.
- **`HourlyZoomCenteringRoboTest.kt`** (extend) — the setting→render check. It already calls
  `buildHourDataList(..., zoom = …)` directly and asserts `HourData` labels, so add: default (5h)
  yields 3 hours back / 2 forward of the centered hour, and span 8 spans twice as many hours as
  span 4. Assert on the returned label list / hour bounds, never on text pixels — Robolectric has no
  font engine.
- **`TemperatureTouchRoutingRoboTest.kt`** (strengthen) — this is the odd-span guard. The file
  already inflates the `RemoteViews`, lays it out, clicks each zone and reads broadcasts off
  `shadowOf(app)`; at `:212` it only asserts `hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET)`.
  Upgrade to asserting that extra's **value** per zone at spans 4, 5 and 8. This is the first
  coverage of `HourlyTouchZoneMapper`'s `asymmetryShift` term being non-zero, which the 5h default
  makes the shipped path.
- **`WidgetStateManagerTest.kt`** (extend) — pref → window → navJump on real `SharedPreferences`:
  default span 5 with navJump 1; setter clamps 3→4 and 9→8; `getNavJump` returns 2 at span 6;
  `getZoomWindow` reflects a changed pref without restart.

Each new assertion gets confirmed failing first (flip the expected value once, watch it go red)
before being committed green — Robolectric geometry tests pass too easily by accident.

### Updated — behavioral

- **`ZoomStageTest.kt`** — drop `stage parameters match historical values` (moved to the window
  test); keep cycle order, ordinal order, `DEFAULT`. Update `nearestByTotalSpan` for the new
  signature, adding a case where a configured 8h narrow span changes which stage a span snaps to.
- **`DesktopGraphZoomTest.kt`** (34 geometry reads — heaviest single file) — snap round-trip
  `zoomFactorForStage(NARROW, n)` → `nearestByTotalSpan(…, n)` returns `NARROW` for every n in 4..8.
- **`ZoomCycleRoboTest.kt`**, **`ZoomLevelSmoothingTest.kt`** — re-point span/smoothing assertions at
  the resolved window.
- **`WidgetStateManagerMigrationInstrumentedTest.kt`** — the one *required* instrumented edit:
  `:88–97` asserts `ZoomLevel.NARROW.navJump`, a property being deleted. Re-point at the window.

### Updated — compile-only type swap

`ZoomLevel` → `ZoomWindow` at call sites, `zoom == ZoomLevel.X` → `zoom.stage == ZoomStage.X`:
~18 files under `app/src/test/.../handlers/` and `app/src/test/.../widget/`, plus `DesktopUiTest.kt`,
plus instrumented `TemperatureZoomConsistencyTest.kt`, `PrecipTouchRoutingInstrumentedTest.kt` (14
refs), `TemperatureTouchRoutingInstrumentedTest.kt`, `CloudCoverTouchRoutingInstrumentedTest.kt`,
`TemperatureHomeTouchRoutingInstrumentedTest.kt`, `testutil/WidgetStateTestUtils.kt`.

**Not covered by Robolectric:** whether the launcher actually delivers the taps (this repo has hit
emulator top-center tap drops and Samsung HoneySpace span quirks). That is pre-existing host
behavior, unrelated to span math.

## Verification

1. `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` — this is the gate. All new
   behavior (span table, window geometry, touch-zone offsets, pref plumbing) is covered here; no
   emulator needed. Instrumented suite only needs to still compile.
2. `./gradlew installDebug`; open Settings, confirm the new section sits directly above
   "Weather Data Sources" and the label updates as the slider moves.
3. On the widget: confirm a fresh install opens the narrow view at 5h (3 back / 2 forward), then set
   4h and 8h and screenshot each — the 8h window should be twice as wide as the 4h one. Capture via
   `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg`.
4. Tap the nav arrows at 5h (expect 1h steps) and 6h (expect 2h steps); confirm against
   `HOURLY_CENTER_TRACE` in logcat.
5. **Footer-label crowding at wide spans on a narrow (2–3 column) widget** — done, not deferred.
   `NARROW` labelled every hour, which would draw up to 8 `<hour><icon>` groups where `WIDE` already
   thins itself to ~4 (24h ÷ `NARROW_WIDE_LABEL_INTERVAL`). `HourlyZoomRules.narrowWidgetLabelInterval`
   now returns 2 from 6h up, applied in all three hourly handlers. Eyeball it at 8h to confirm.
6. Desktop: `scripts/buildStart-desktop.sh`, then confirm the popup's click-to-cycle lands on the
   configured span and the arrows step 1h vs 2h accordingly.
