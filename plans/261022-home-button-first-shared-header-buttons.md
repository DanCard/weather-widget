# Home button first in every header row; header buttons decided in `:shared`

## Problem

The centred header buttons end on a fixed line (`HeaderFit.SEAM_OFFSET_DP`) and grow leftwards, so
the date never moves. The buttons themselves still move:

- **Daily** order is observations · home · months · history. When home appears or hides (on a source
  switch, or when today is panned away), the observations button moves sideways by one slot.
- **Hourly** order is graph selector · observations · home · history (Fold 4 screenshot, 2026-10-22).
  Home is third here and second on daily, so the same icon sits in a different place on each view.

The hourly row has also drifted between screens and platforms. Which buttons show and in what order
is decided separately in each place:

| Where | Observations button on hourly | Order source |
|---|---|---|
| Android temperature (`TemperatureViewBinder`) | hidden unless first/last hour is today | XML child order |
| Android precip / cloud (`PrecipViewHandler`, `CloudCoverViewHandler`) | hidden unless `centerTime` is today | XML child order |
| Desktop (`DesktopWidgetHeader`) | always shown (opens the viewed day) | `Row` call order |

There are three different "is today" rules. The graph selector's next view and emoji are also
written twice (`setupGraphSelectorShortcut`, desktop `when (config.viewMode)`). Android hides
observations with `View.GONE` **after** the fit, inside a container centred on the span HeaderFit
computed for that count. That mismatch can move the row by half a slot.

## Decision (user, 2026-10-22)

Home goes **first, at the left end**, and observations second, on both views and both platforms:

| View | Before | After |
|---|---|---|
| Daily | observations · home · months · history | **home** · observations · months · history |
| Hourly | selector · observations · home · history | **home · observations** · selector · history |

With four buttons, home and observations sit at the same x on both views and history is last on
both, so switching daily ↔ hourly changes only the third icon (months ↔ graph selector). On daily,
home's "back to today" appears when today is panned off screen, about when observations hides
(today and yesterday both off), so a pan usually swaps one for the other in the same slot.

The row is pinned on its right edge, so its leftmost button is the only one that can appear or
disappear without moving the others. Home is also the first button dropped when the header is
narrow. Forecast history stays last on both views, so it does not move when switching daily ↔ hourly.

Logic for the hourly header row is shared as much as possible (CLAUDE.md: runner + platform Host,
not a planner fed by per-platform glue).

## Design: `:shared` `HeaderButtons`

New file `shared/.../util/HeaderButtons.kt`, next to `HeaderFit`:

```kotlin
enum class HeaderButton { HOME, GRAPH_SELECTOR, OBSERVATIONS, MONTHS, HISTORY }

object HeaderButtons {
    /** Display order, left to right. The one place the order lives. */
    val ORDER = listOf(HOME, OBSERVATIONS, GRAPH_SELECTOR, MONTHS, HISTORY)

    data class Inputs(
        val hourly: Boolean,
        val observationsInView: Boolean, // shared rule below, not computed per screen
        val homeAction: PreferredSourceHome.HomeAction?, // daily only; hourly home is always shown
        val wantMonths: Boolean,         // daily only
    )

    /** What HeaderFit.Measured needs: baseButtons, wantHome, wantMonths. */
    fun measuredCounts(i: Inputs): Counts

    /** The buttons the fitted plan shows, in ORDER. Every renderer draws exactly this list. */
    fun shown(i: Inputs, plan: HeaderFit.Plan): List<HeaderButton>

    /** Hourly: whether the window shows any of today, i.e. whether current observations apply. */
    fun hourlyObservationsInView(windowStart: LocalDateTime, windowEnd: LocalDateTime, today: LocalDate): Boolean
}
```

Also in `:shared`: `GraphSelector.next(viewMode): Pair<ViewMode, String>`, the next view and its
emoji (`ViewMode` is already in `:shared`). It replaces both copies.

Each platform keeps only its Host work: measuring widths (Android `HeaderWidthChecker`, desktop text
measurer), binding PendingIntents / click lambdas, and drawing.

### Platform changes

**Android**
- `widget_weather.xml`: reorder the children of `hourly_center_header_container` **and** of the
  inline zone row to `ORDER`. Both views use this one container.
- Replace `positionCenterIcons` (hourly) and `positionDailyIcons` (daily) visibility code with one
  `positionHeaderButtons(views, shown, placement, …)`. It maps `HeaderButton` → view ids and makes
  every id not in `shown` GONE. This replaces the hard-coded months-GONE and the post-fit
  observations-GONE.
- One rule: `TemperatureViewBinder`, `PrecipViewHandler` and `CloudCoverViewHandler` call
  `HeaderButtons.hourlyObservationsInView` (replacing three different "is today" checks). `HourlyHeaderBinder` and `TemperatureViewBinder` get
  their `baseButtons`/`wantHome` from `measuredCounts` instead of `if (isToday) 4 else 3` /
  `wantHome = false`.
- `setupGraphSelectorShortcut` reads `GraphSelector.next`.

**Desktop**
- `DesktopWidgetHeader`: the two button `Row`s (hourly `:287`, daily `:340`) become one loop over
  `HeaderButtons.shown(...)`. Each icon is placed at its slot, with one `when (button)` for icon and
  click. `baseButtons`/`wantHome`/`wantMonths` come from `measuredCounts`.
- Hourly observations follows `hourlyObservationsInView` (was always shown).
- The graph selector reads `GraphSelector.next`.

### Hourly observations button (user, 2026-10-22)

It opens **current** observations. Neither screen shows a past day: Android passes no date, and the
desktop's `observationsViewedDate` only picks which day a Refresh refills from Google history. So it
is shown **only while today's hours are on screen**, as Android's temperature view does now; desktop
(always shown) adopts it. No date extra, and `RetentionPolicy` is not involved: the 10 days of stored
readings feed the hourly graph's actual line and the daily actuals blend, not this button.

"Today on screen" is one rule for all three hourly views and both platforms. The window
`[centre − backHours, centre + forwardHours]` overlaps today, replacing Android temperature's
first/last-hour check and precip/cloud's `centerTime` check. Daily's rule (today or yesterday on
screen) is unchanged.

## Tests

`:shared` (unit, `HeaderButtonsTest`):
- `ORDER` starts with HOME and ends with HISTORY.
- Daily: home shown → `[HOME, OBSERVATIONS, MONTHS, HISTORY]`; home not wanted →
  `[OBSERVATIONS, MONTHS, HISTORY]`, and OBSERVATIONS' slot is **the same x** in both
  (the bug this fixes; fails on the old order).
- Hourly → `[HOME, OBSERVATIONS, GRAPH_SELECTOR, HISTORY]`; no today on screen →
  `[HOME, GRAPH_SELECTOR, HISTORY]`. With four buttons each, HOME and OBSERVATIONS slots are the same x
  on daily and hourly.
- `hourlyObservationsInView`: today → true; yesterday only → false; tomorrow only → false; a
  window from tonight into tomorrow → true; a window from yesterday evening to 00:00 today → true.
- `shown` never lists a button the plan dropped (home not fitting, months not fitting, HIDDEN placement).
- HISTORY's slot is the same x on daily and hourly at the same width and scale.
- `GraphSelector.next` cycles TEMPERATURE → CLOUD_COVER → PRECIPITATION → TEMPERATURE.

Android (Robolectric):
- Inflate `widget_weather.xml`: the child order of `hourly_center_header_container` and of the
  inline row, mapped to `HeaderButton`, equals `HeaderButtons.ORDER`. This catches XML drifting from
  the shared order.
- `positionHeaderButtons`: ids for buttons not in `shown` are GONE, including months on hourly after
  a daily render (sticky RemoteViews visibility).

Desktop (Compose UI test):
- Hourly and daily: the `daily_home_button` / `switch_to_daily` node is leftmost of the button nodes.
- Daily: `open_observations_header_daily`'s x does not change when switching to a non-preferred
  source (home appears) and back.

`CrossPlatformDuplicateNamesTest` must stay green, so no allowlist growth.

## Verification

- `./gradlew :shared:test :desktop:test testDebugUnitTest` (the relevant classes).
- `./gradlew installDebug` on the Fold 4 and Pixel 7 Pro: screenshots of daily (preferred source,
  then another source) and hourly temperature / cloud / precip. Home should be leftmost, with
  observations not moving on the source switch. Check the narrow 360dp emulator widget for the
  INLINE fallback.
- `scripts/buildStart-desktop.sh`, then the same checks on desktop.

## Files

- new `shared/src/main/kotlin/com/weatherwidget/shared/util/HeaderButtons.kt`, `GraphSelector.kt` + tests
- `app/src/main/res/layout/widget_weather.xml`
- `app/.../handlers/TemperatureTouchTargets.kt`, `TemperatureViewBinder.kt`, `HourlyHeaderBinder.kt`,
  `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`, `DailyGraphRenderer.kt`, `DailyHeaderResolver.kt`
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWidgetHeader.kt`
- CLAUDE.md "Header drop order" bullet: add the button order and `HeaderButtons`.

## As built (2026-10-22)

- No `slots()`: desktop keeps one `Row` from `buttonsSpan`'s left edge (the hourly emoji is wider
  than an icon, so equal slots would misplace it); the shared test computes slots itself.
- The "today on screen" window is end-exclusive (`hourlyObservationsInView(centre, backHours,
  forwardHours, today)`): yesterday's graph ending at 00:00 today hides the button, as Android's
  temperature view did.
- Android: one `showHeaderButtons(views, placement, shown)` over a `HeaderButtonViews` id table
  writes every button view, so sticky RemoteViews visibility cannot carry months/home across views.
  `setupGraphSelectorShortcut` no longer sets visibility (its own `widthDp < 420` rule was overridden
  anyway).
- Desktop observations keeps passing the viewed day: it scopes the window's Refresh (Google history
  refill), not what it shows.
