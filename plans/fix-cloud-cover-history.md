# Daily forecast (history) cloud-cover fallback fix

## Context

On both Samsung (SM‑F936U1) and Pixel 7 Pro, scrolling the daily-forecast widget back/forward
in history shows cloud-cover shading on the vertical bars "slowly" or not at all. The user asked
to review logs and add DB logging, then redirected the fix: **GENERIC_GAP fallback must not be used
for history days or for today / +1 / +2. Fallback (climate-normal GENERIC_GAP rows) is only valid
for long-term future days the selected API does not cover.**

### What the logs proved (already done)
Added a persisted cloud-cover diagnostic to the existing `DAILY_RENDER` app_logs line
(`DailyViewHandler.logDailyRenderSummary` + new `buildCloudCoverDiagnostic`), built and installed to
the Samsung device. Live capture (widget 345, NWS, today=2026-05-30) showed two failure modes:

- **Transient flicker (this plan):** identical hourly data (`hourlyRows=227`, `hourlyWindow=05-27..06-05`)
  produced `cloud=4/8` then `cloud=7/8` ~1s apart during a slow 2227ms nav. Same hourly data ⇒ the early
  miss is not a data problem. The recent past days briefly resolved their daily `weather.source` to
  `GENERIC_GAP`, so `resolveNoonCloudCoverRatio` looked up GENERIC_GAP hourly cloud (none exists) and
  returned null until the full display-source reload landed.
- **Hard window floor (DEFERRED, see below):** `hourlyWindow` only spans 72h back / 168h fwd while history
  nav reaches 30 days, so days beyond the window (e.g. d−4 = 05‑26) are permanently unshaded.

The DB confirms the data exists: `hourly_forecasts` has 24/24 cloud-cover hours for every date back past
mid-May for NWS/Open‑Meteo/Silurian/Tomorrow.io.

## Scope of THIS change (do first)

Remove GENERIC_GAP fallback for **past days and today / today+1 / today+2**; permit it only for
`date.isAfter(today.plusDays(2))` (long-term future where the API genuinely lacks a forecast).

Because the daily `weather.source` then can never be GENERIC_GAP for these days,
`resolveNoonCloudCoverRatio`'s GENERIC_GAP branch becomes a no-op there and cloud shading resolves
consistently against the real display source — eliminating the flicker. The `isSourceGapFallback`
flag (which drives `gapFallbackBarPaint` in `DailyForecastGraphRenderer`) also stops being set for
these days, so they render in normal style.

### Files / changes

Introduce one small predicate (duplicate a 1-line private helper in each file, matching existing style):
`fun allowsGapFallback(date: LocalDate, today: LocalDate) = date.isAfter(today.plusDays(2))`

1. **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`** — `weatherByDate`
   build (lines ~227–243). Today is `now.toLocalDate()` (line 161).
   - Currently groups `displaySource OR GENERIC_GAP`, then `preferred ?: items.first()` (so a GENERIC_GAP
     row can win when no display-source row exists), and substitutes GENERIC_GAP for non-today days with
     missing temps.
   - Change: only allow the GENERIC_GAP substitution and the `items.first()` GENERIC_GAP win when
     `allowsGapFallback(date, today)`. Otherwise use `preferred` only (display source); if `preferred`
     is null, omit the date (bar renders as missing) — this is the intended behavior per the user.

2. **`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt`** — `prepareGraphDays`
   (and mirror in `prepareTextDays` if it has the same pattern):
   - Line ~324: `weather = weatherByDate[date] ?: forecastSnapshots[date]?.firstOrNull()` — the snapshot
     `firstOrNull()` can be a GENERIC_GAP row. Gate the snapshot fallback so it cannot introduce a
     GENERIC_GAP `weather` for non-long-term dates.
   - Lines ~338–343: the general `forecast` (overlay) selection includes `GENERIC_GAP`. Drop GENERIC_GAP
     from this selection unless `allowsGapFallback(date, today)`. (The dedicated past-day overlay path at
     ~376–380 already excludes GENERIC_GAP — leave it.)
   - `resolveNoonCloudCoverRatio` (~585–616): no change required for correctness once `weatherSourceId`
     is never GENERIC_GAP for these days; the existing GENERIC_GAP branch simply won't fire. Leave as-is.

### Risks / expected behavior
- Near-term/history bars that legitimately lack the selected source's data will now show **missing**
  rather than climate-normal filler. This is the user's explicit intent. Verify per display source by
  toggling the API indicator (NWS ↔ Open‑Meteo) and scrolling history — real data should still render
  because both sources have full coverage in the DB; only genuinely-absent days go blank.
- Long-term future (>+2) is unchanged and still uses GENERIC_GAP/`gapFallbackBarPaint`.

## Verification

1. `./gradlew :app:compileDebugKotlin` then `ANDROID_SERIAL=RFCT71FR9NT ./gradlew installDebug`.
2. Repaint after install: `adb -s RFCT71FR9NT shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/.widget.WeatherWidgetProvider`.
3. Scroll history on the device; pull DB and read the diagnostic:
   ```bash
   for f in weather_database weather_database-shm weather_database-wal; do \
     adb -s RFCT71FR9NT shell "run-as com.weatherwidget cat databases/$f" > /tmp/wwdb/$f; done
   sqlite3 -separator ' | ' /tmp/wwdb/weather_database \
     "SELECT datetime(timestamp/1000,'unixepoch','localtime'), substr(message,1,400) \
      FROM app_logs WHERE tag='DAILY_RENDER' AND message LIKE '%cloud=%' ORDER BY timestamp DESC LIMIT 15;"
   ```
   Expect: no transient `cloud=4/8`→`7/8` drop for in-window days; `cloudMissing` should contain only
   days outside `hourlyWindow` (the deferred Cause A), never recent days flipping in/out.
4. Screenshot check (per CLAUDE.md, strip prepended bytes → convert to JPG): history bars for today/+1/+2
   and recent past days no longer use the gap-fallback paint and (when in the hourly window) carry the
   gray cloud segment.
5. Unit tests: `./gradlew testDebugUnitTest --tests "*DailyForecast*" --tests "*DailyView*"` (and any
   GENERIC_GAP daily tests). Update/add a test asserting GENERIC_GAP is excluded for date ≤ today+2 and
   allowed for date ≥ today+3.

## Deferred (discuss after this lands)
- **Cause A — window floor.** Cloud lookup is bounded by `HOURLY_LOOKBACK_HOURS=72` /
  `HOURLY_GRAPH_LOOKAHEAD_HOURS=168` (`WeatherWidgetProvider`), loaded in
  `WidgetIntentRouter.refreshDailyView` (~718) and `WeatherWidgetWorker.fetchHourlyForecasts` (~257).
  Per the user, extend coverage **optimized to ~1 week** (not 30 days): add a lightweight near-noon
  `HourlyForecastDao` query (~5 rows/day vs 24) over the modest range and thread it to
  `resolveNoonCloudCoverRatio`, rather than widening the main hourly list consumed by precip/rain/
  current-temp (which would worsen the already-slow ~2.2s nav).
- The diagnostic logging added to `DAILY_RENDER` is intentionally kept as the verification instrument;
  decide later whether to trim it.
