# Show the current-observations button for yesterday too (v1)

**Source:** user request — "Instead of today, today and yesterday show the button?"

## Problem

The daily-view header's current-observations button (thermometer icon) is gated on
`todayInView` — it only shows while *today* is among the rendered days. The gate is a deliberate
design choice, documented in several places as "current observations are inherently now-ish"
(`DailyViewHandler.kt`, `HeaderWidthChecker.kt`, `DailyGraphRenderer.kt`, desktop `Main.kt`).

But the button's real payoff for the user is the **station-history affordance**: tapping a station
in the Observations screen opens that station's NWS time-series page
(`StationHistoryUrl.forStation` → `openStationHistory`), which is **date-independent**. So a user
panned back to *yesterday* ("when did the temperature drop yesterday?") still has a legitimate
reason to reach the button, yet it's hidden because today scrolled off screen.

## Decision (v1)

Widen the gate from "today in view" to **"today OR yesterday in view"**. Keep the forecast-history
button's target-date logic strictly today-vs-centre (unchanged): the history button opens *today*
only when today is on screen, otherwise the viewed centre date.

The Observations screen's *content* stays now-oriented (latest-per-station list, current blend,
current refresh) — the v1 accepts that mild mismatch in exchange for restoring the
station-history entry point on yesterday. A date-aware Observations screen is a follow-up, out of
scope here.

## Change

1. **`NavigationUtils.isTodayOrYesterdayInRange(today, visibleFrom, visibleTo)`** (new, shared):
   pure helper returning true when today or `today.minusDays(1)` falls inside the window.
2. **`DailyHeaderResolver`**: replace the inline `todayInView` range test with the helper, rename the
   field to `observationsInView`, and derive `iconCount` from it (2 when in view, else 1).
3. **`DailyViewHandler.HeaderState`**: rename `todayInView` → `observationsInView`; update the
   comment ("drives iconCount").
4. **`DailyGraphRenderer`**: keep `todayInView = displayDays.any { it.isToday }` for the history
   target date; add `observationsInView = displayDays.any { it.isToday || it.date == ctx.today.minusDays(1) }`
   and pass it as `showObservations`. Update the disagreement check to compare the new field.
5. **Comments** in `TemperatureTouchTargets.positionDailyIcons` and `HeaderWidthChecker.resolveDailyIconPlacement`
   ("drops when today is off screen" → "drops when today *and* yesterday are off screen").
6. **Desktop `Main.kt`**: split the single `todayInView` param into two — `todayInView` (history
   target, today-only) and `observationsInView` (observations button, today-or-yesterday). The daily
   branch already reports on-screen status up from the rendered days
   (`dailyState.days.any { it.isToday }`); add `dailyObservationsInView` using
   `it.isToday || it.daysFromToday == -1`.

No behaviour change to the hourly view's `positionCenterIcons(isToday)` (out of scope — the hourly
window is a "now" window, not a day window).

## Tests

- `NavigationUtilsTest` (app, ShortDuration): add cases for `isTodayOrYesterdayInRange` — today in
  range, yesterday in range (today off), neither in range, boundary dates.
- Existing `PositionDailyIconsRoboTest`, `DailyIconPlacementTest`, `HistoryIconVisibilityRoboTest`
  and `DesktopUiTest` exercise the mechanism/placement and remain valid (the `showObservations`
  boolean and `iconCount` plumbing are unchanged in shape).

## Verify

`./gradlew :app:assembleDebug :desktop:compileKotlin :shared:testShortShared` plus the affected
Robolectric tests (`NavigationUtilsTest`, `PositionDailyIconsRoboTest`, `DailyIconPlacementTest`,
`DailyViewHandlerTodayDropIntegrationTest`, `HistoryIconVisibilityRoboTest`) and `DesktopUiTest`.
