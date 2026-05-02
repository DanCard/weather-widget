# Daily Forecast View: Night Rain Label Touch Zones

## Context

In the daily forecast graphical view, each day column can render a small blue "night rain" label (e.g. "💧 60% night") underneath the low-temp label when overnight precipitation probability crosses the day's threshold. Tapping anywhere in a day column today routes via `DayClickHelper.resolveDailyTargetViewMode(iconRes)` — typically to the precipitation graph centered on **noon** of the target day. That means a user who specifically cares about *tonight's* rain has to manually navigate the hourly graph to see the relevant window.

This change adds dedicated touch zones over each visible night rain label that, when tapped, open the precipitation graph centered on the **midpoint between sunset of the target day and sunrise of the next day** — i.e. true astronomical night.

Touch zone scope (per user, Option A): one small ≈square zone *per visible label* — a single half-column-wide tap target (≈35dp × 24dp at typical widget sizes; ≈25dp × 24dp on cramped 5-col-narrow widgets). If two adjacent days both show night rain labels, two zones appear — one per label. Existing day-icon and bar tap behavior is unchanged.

**Tap-target trade-off**: because the half-zones are aligned to fixed column halves (not to the label's pixel-exact draw center), each active half-zone covers roughly half of the visible label's footprint. The other half of the label still falls through to the underlying `graph_bottom_dayN_zone` (icon-home routing). This is a deliberate consequence of choosing the smallest square footprint and not adopting `setViewLayoutMargin` (API 31+ only; minSdk = 26).

## Approach

The renderer already distinguishes two horizontal placements for night rain labels:
- **Shifted right** (`NIGHT_SHIFTED_RIGHT` / `NIGHT_SHIFTED_SCALED` / `NIGHT_INTERSTITIAL`) — label sits at the boundary between column *i* and column *i+1*. This is the common case.
- **Centered fallback** (`NIGHT_CENTERED` / `NIGHT_CENTERED_SCALED`) — label sits at the center of column *i*. Only used for the last visible day where a right-shift would clip.

Because RemoteViews on minSdk 26 cannot reposition views at runtime (`setViewLayoutMargin` is API 31+), we pre-define a fixed grid of 20 half-column FrameLayouts in the layout XML. For each visible night rain label we activate **exactly one** half-zone whose center sits closest to the label:

| Placement | Active half-zone |
|---|---|
| Shifted right (col *i*, *i* < N-1) | `graph_night_rain_zone_i_r` (right half of col *i*) |
| Centered fallback (last col, *i* = N-1) | `graph_night_rain_zone_i_r` (right half of col *i*) — symmetric pick |

Either edge case (shifted or centered) is covered by the right half of the originating column, so all night labels map cleanly to a single deterministic `(column → half-zone)` rule. Inactive zones receive `setOnClickPendingIntent(null)` so taps fall through to the underlying `graph_bottom_dayN_zone` — preserving existing icon-tap behavior, including for the half of the label that doesn't fall under the active zone. This null-intent fall-through pattern is already used in the codebase (`DailyViewHandler.kt:1313`).

Centering on night is done by computing an `EXTRA_HOURLY_OFFSET` such that the night midpoint becomes `centerTime` after `WidgetIntentRouter.handleSetView` stores it and the next render reads it back as `now.plusHours(hourlyOffset)`.

## Files to modify

### 1. `app/src/main/res/layout/widget_weather.xml` — new `graph_night_rain_zones` LinearLayout

Insert immediately after the existing `graph_bottom_day_zones` LinearLayout so it stacks on top in the FrameLayout (active zones intercept; inactive zones pass through). Mirror the margin/padding conventions of `graph_day_zones` (`widget_weather.xml:474`).

```xml
<LinearLayout
    android:id="@+id/graph_night_rain_zones"
    android:layout_width="match_parent"
    android:layout_height="24dp"
    android:layout_gravity="bottom"
    android:layout_marginStart="4dp"
    android:layout_marginEnd="4dp"
    android:layout_marginBottom="18dp"
    android:layoutDirection="ltr"
    android:orientation="horizontal"
    android:visibility="gone">

    <!-- 20 children: graph_night_rain_zone_0_l … graph_night_rain_zone_9_r -->
    <!-- each: layout_width=0dp, layout_height=match_parent, layout_weight=1, background=transparent -->
</LinearLayout>
```

The band sits ≈18dp above the day-of-week labels (drawn at `heightPx - 3px`, ≈17dp tall), which is where `drawNightRainLabel` places labels (`DailyForecastGraphRenderer.kt:1071-1080`).

### 2. `app/src/main/java/com/weatherwidget/widget/handlers/WidgetRequestCodes.kt`

Add a new request-code namespace alongside the existing `dayClick` / `graphClick` helpers (`WidgetRequestCodes.kt:36-37`):

```kotlin
fun nightRainClick(id: Int, halfZoneIndex: Int) = id * 10000 + BASE_NIGHT_RAIN_CLICK + halfZoneIndex
```

Reserve a `BASE_NIGHT_RAIN_CLICK` constant in a band that doesn't collide with `BASE_DAY_CLICK` / `BASE_GRAPH_CLICK` / `BASE_BOTTOM_HOUR_CLICK`. Index ranges 0..19 (one per half-zone).

### 3. `app/src/main/java/com/weatherwidget/widget/handlers/DayClickHelper.kt`

Add a sibling to `calculatePrecipitationOffset` (`DayClickHelper.kt:100-109`) that centers on night midpoint:

```kotlin
fun calculateNightCenterOffset(
    now: LocalDateTime,
    targetDay: LocalDate,
    lat: Double,
    lon: Double,
): Int {
    val sunsetToday = SunPositionUtils.getSunTimes(targetDay.atStartOfDay(), lat, lon).sunsetHour
    val sunriseTomorrow = SunPositionUtils.getSunTimes(targetDay.plusDays(1).atStartOfDay(), lat, lon).sunriseHour

    // Both hours are in the target day's local frame: sunset is < 24, sunrise (next day) is < 24.
    // Night spans (sunsetHour..24+sunriseHour). Midpoint expressed as hours from midnight of targetDay:
    val nightMidHourFromTargetMidnight = (sunsetToday + 24.0 + sunriseTomorrow) / 2.0
    val nightMid = targetDay.atStartOfDay()
        .plusMinutes((nightMidHourFromTargetMidnight * 60).toLong())

    val alignedNow = WeatherTimeUtils.alignToNearestHourHalfUp(now)
    return Duration.between(alignedNow, nightMid).toHours().toInt()
}
```

Polar edge cases (`sunsetHour >= 24` or `sunriseHour <= 0`) naturally degrade to a midnight-ish center — acceptable for this widget's audience but worth a brief unit test.

### 4. `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

**4a.** Add a new sibling to `setupGraphDayClickHandlers` (`DailyViewHandler.kt:1237`) and `setupGraphBottomDayClickHandlers` (`DailyViewHandler.kt:1263`):

```kotlin
private fun setupNightRainClickHandlers(
    context: Context, views: RemoteViews, appWidgetId: Int,
    now: LocalDateTime,
    days: List<DailyForecastGraphRenderer.DayData>,
    lat: Double, lon: Double,
    displaySource: WeatherSource,
    numColumns: Int,
)
```

Behavior:
- Build the full half-zone id list (`graph_night_rain_zone_0_l`, `..._0_r`, `..._1_l`, …, `..._9_r`).
- Default every half-zone to `setOnClickPendingIntent(zoneId, null)` (so taps pass through to the underlying bottom-day zones).
- For each `day` in `days` where `day.rainData.nightRainLabelText != null`:
  - Resolve `colIndex = day.columnIndex ?: index`.
  - Pick a single active half-zone: `graph_night_rain_zone_${colIndex}_r` (right half of col *i*) — same id whether the renderer chose shifted or centered placement.
  - Build a night-rain intent — same shape as `buildDayClickIntent`'s non-history branch (`DailyViewHandler.kt:1215-1220`) but with:
    - `EXTRA_TARGET_VIEW = ViewMode.PRECIPITATION.name`
    - `EXTRA_HOURLY_OFFSET = DayClickHelper.calculateNightCenterOffset(now, day.date, lat, lon)`
  - Wire the half-zone to a PendingIntent using `WidgetRequestCodes.nightRainClick(appWidgetId, colIndex)`.

Refactor: extract the inner intent construction in `buildDayClickIntent` into a small helper, or factor out `buildPrecipNavIntent(targetMode, offset)` to avoid duplicating the extras shape. The existing `buildDayClickIntent` can stay; `setupNightRainClickHandlers` calls the new helper directly.

**4b.** Wire visibility into `setGraphModeViews` (`DailyViewHandler.kt:673-688`):
```kotlin
views.setViewVisibility(R.id.graph_night_rain_zones, View.VISIBLE)
```
And to `setTextModeViews` (`DailyViewHandler.kt:690-725`):
```kotlin
views.setViewVisibility(R.id.graph_night_rain_zones, View.GONE)
```
Also hide in `ApiSourceWarningHelper`, `PrecipViewHandler`, `CloudCoverViewHandler` — searching for `graph_day_zones` View.GONE callsites identifies all spots that need parallel treatment (`grep` already catalogued these).

**4c.** Call `setupNightRainClickHandlers` from `DailyViewHandler.updateWidget` right after `setupGraphBottomDayClickHandlers` is invoked.

### 5. Tests

- Unit: `DayClickHelperTest` — assert `calculateNightCenterOffset` returns ~7h for a `now=2026-05-01T17:00`, `targetDay=2026-05-01`, mid-latitude lat/lon (sunset ≈ 19:30, sunrise ≈ 06:00, midpoint ≈ 00:45). Add a polar-night case (sunsetHour=0, sunriseHour=0) to confirm graceful degradation.
- Unit (existing harness): `DailyViewHandlerTest` — assert that for a `DayData` list with `nightRainLabelText` set on column 2, `setupNightRainClickHandlers` (made `@VisibleForTesting internal`) wires intents on `zone_2_r` + `zone_3_l` and clears intents on the rest. Verify centered-fallback selection for last column.

## Verification

1. Build: `./gradlew installDebug`
2. Run unit tests: `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DayClickHelperTest"` and `…DailyViewHandlerTest`
3. Manual on emulator/device:
   - Open the existing widget on the home screen; resize to 5+ columns to enable graph mode.
   - Pick (or wait for) a forecast where at least one future day shows the blue "💧 X% night" label. If no real data, force night precip values via DB (`adb pull /data/data/com.weatherwidget/databases/...`, edit, push back) — see `CLAUDE.md` debugging workflow.
   - Tap the night rain label area: the precipitation graph should open zoomed wide, with the now-line/center-line at the night midpoint of that day.
   - Tap a *daytime* area of the same column: the existing icon-home routing (precipitation centered on noon, or temperature/cloud per icon) should still fire. No regression.
   - Two adjacent days both showing night labels: confirm each tap independently selects its day's night.
4. Logs: `adb logcat | grep CLICK_DAILY` — confirm new dispatches show `mode=PRECIPITATION` with the night-centered offset (large negative or positive integer depending on date offset).
5. Screenshot check: `adb exec-out screencap -p > /tmp/s.png && convert /tmp/s.png /tmp/s.jpg` (per `CLAUDE.md`) and read `/tmp/s.jpg` to confirm the visible widget state matches the user's tap.

## Out of scope

- Hourly data lookahead beyond +60h: if the user taps a night rain zone for a day far enough out that no hourly precip data is cached, the precip graph falls back to its existing empty-state behavior. No change here.
- Text-mode (1-row) widgets: night rain labels aren't drawn there, so no zone is needed.
- Daytime rain label tap zones: explicitly out of scope per the user's request (night-only).
