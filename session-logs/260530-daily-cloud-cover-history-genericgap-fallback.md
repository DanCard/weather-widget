# Daily Cloud Cover Missing in History — GENERIC_GAP Fallback Fix

## Summary
- Investigated a report that cloud-cover shading on the daily-forecast vertical bars is "slow to
  show up" when scrolling history — reported on both Samsung `SM-F936U1` and Pixel 7 Pro.
- The Pixel report ruled out the initial Samsung-network/NWS-gridpoints-latency theory; the cause is
  shared render/data logic, not a device quirk.
- Added persisted DB logging to the existing `DAILY_RENDER` app_logs line, which captured two distinct
  failure modes from `app_logs` alone.
- Per user direction, fixed the primary cause: inappropriate `GENERIC_GAP` (climate-normal) fallback
  being used for history / today / +1 / +2. Fallback is now restricted to long-term future
  (`date > today+2`).
- Verified on-device: the transient flicker is gone (stable `cloud=8/8` on history offsets).
- A second cause (the hard 72h hourly-window floor) was diagnosed and deferred per user decision.

## User Prompts (verbatim, in order)
1. "Samsung: daily forecast view: history, cloud cover on vertical bar is often very slow to show up.
   Review logs, add db logging if that helps."
2. "Same thing happens on pixel 7 pro"
3. "The issue is reproducible.  I just scroll forward and backward in history and issue occurs.  The
   issue is now currently happening on samsung and pixel 7 pro"
4. "Issue is visible on samsung"
5. (AskUserQuestion answers)
   - Fix scope: "There should be no fallback in history.  Remove fallback in history first, then we can
     discuss fixing other issues.  Also there should be no fallback for current day and days +1 and +2.
     Fallback is only when the API doesn't provide long term forecast."
   - Mechanism: "This should be an optimized approach.  Most users will never scroll back 30 days.  A
     week at most."
6. "Yes record to memory"
7. "implement"
8. "The fix works well.  I'm wondering if we should address the other issues or skip."
9. "write a session log to session-logs/ dir .  Include all prompts."

## Evidence Collected
- Device: Samsung `SM-F936U1` (`RFCT71FR9NT`) plus two emulators connected.
- `logcat` had no matching lines for the key tags — the most diagnostic line,
  `resolveNoonCloudCoverRatio`, is a `Log.d` (logcat-only), never persisted to `app_logs`. That gap is
  why the intermittent issue was un-reviewable after the fact.
- Pulled `weather_database` via `run-as` and queried `app_logs`:
  - `hourly_forecasts` has full 24/24 cloud-cover hours for every date back past mid-May (NWS,
    Open-Meteo, Silurian, Tomorrow.io) — the **data exists**; loading/use was the problem.
  - Added a cloud diagnostic to `DAILY_RENDER` (`cloud=resolved/total`, `cloudMissing=<dates+daysFromToday>`,
    `hourlyRows`, `hourlyWithCloud`, `hourlyWindow=<min..max>`), built, installed, and captured live:
    - **Cause B (flicker, fixed here):** widget 345 @ offset −4 rendered `cloud=4/8` then `cloud=7/8`
      ~1s apart on **identical** hourly data (`227 rows`, same window). Same data ⇒ not a data/window
      issue. Recent past days briefly resolved `weather.source = GENERIC_GAP`, so
      `resolveNoonCloudCoverRatio` looked up GENERIC_GAP hourly cloud (none) and returned null until the
      full display-source reload landed.
    - **Cause A (window floor, deferred):** `hourlyWindow=05-27..06-05` (72h back / 168h fwd) while
      history nav reaches 30 days; days outside the window (e.g. d−4=05-26, d+7=06-06) are permanently
      unshaded.

## Changes Made
- **Diagnostic logging** (`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`):
  extended `logDailyRenderSummary` with optional `cloudDays`/`hourlyForecasts` and a new
  `buildCloudCoverDiagnostic`; wired into the GRAPH-mode render call. Kept as the verification instrument.
- **Fix — restrict GENERIC_GAP to long-term future only** (gate: `date.isAfter(today.plusDays(2))`):
  - `DailyViewHandler.kt` `weatherByDate` build: switched `mapValues` → `mapNotNull`; GENERIC_GAP
    substitution and the `items.first()` gap-win are allowed only for long-term future. History /
    today / +1 / +2 use the real display source, or drop the entry (bar renders missing) if absent.
  - `DailyViewLogic.kt` `prepareGraphDays` (~line 324): the `forecastSnapshots[date].firstOrNull`
    snapshot fallback now excludes GENERIC_GAP rows unless the day is long-term future.
  - No change needed to `resolveNoonCloudCoverRatio` — once `weather.source` is never GENERIC_GAP for
    these days, its GENERIC_GAP branch is dead code and cloud resolves against the real source.
- **Tests** (`app/src/test/.../DailyGapFallbackGraphIntegrationTest.kt`): added two regression cases —
  past day with only a GENERIC_GAP snapshot must NOT be gap-fallback; long-term future (today+3) with
  only a GENERIC_GAP snapshot still IS gap-fallback.

## Verification
- `./gradlew :app:compileDebugKotlin` — clean.
- `./gradlew testDebugUnitTest` for daily/history/renderer suites — 89 tests + 2 new, all pass
  (DailyGapFallbackGraphIntegrationTest 6/6).
- On-device (Samsung), post-install: same widget/offset that previously flickered now logs stable
  `cloud=8/8 cloudMissing=-` across repeated renders. Only remaining `cloudMissing` is days genuinely
  outside the hourly window (e.g. `2026-06-06(d7)`) — the deferred Cause A, not the fallback bug.
- Changes not committed (left to user).

## Deferred / Open
- **Cause A — hourly window floor.** Bounded by `HOURLY_LOOKBACK_HOURS=72` /
  `HOURLY_GRAPH_LOOKAHEAD_HOURS=168` in `WeatherWidgetProvider`, loaded in
  `WidgetIntentRouter.refreshDailyView` and `WeatherWidgetWorker.fetchHourlyForecasts`. Recommended fix
  (per user "a week at most, optimized"): a lightweight near-noon `HourlyForecastDao` query (~5 rows/day)
  over ~8 days back, threaded into `resolveNoonCloudCoverRatio` — not a widen of the main hourly list.
  User is deciding whether to implement or skip (low severity: only bites >3 days back on cloudy days).

## Notes
- Plan file: `/home/dcar/.claude/plans/samsung-daily-forecast-view-radiant-hearth.md`.
- Memory recorded: `generic_gap_long_term_only` — GENERIC_GAP filler is long-term-future-only
  (`date > today+2`); near-term/history filler masks real data and was the root of the cloud-cover bug.
- Key lesson: the flicker was a source-routing bug, not a data or timing problem — `hourlyWithCloud`
  proved the data was always present. The fix was *removing* a fallback, which made the resolver's
  GENERIC_GAP branch dead code one layer down.
