# One forecast cadence: long horizon (8 days) and short horizon (3 days)

User, 2026-10-16: "Possibly best to unify daily forecast refresh with hourly forecast."

## The rule (all sources alike)

| Battery | Screen | Long horizon (≈ 8 days) | Short horizon (3 days) |
|---|---|---|---|
| ≥ 70 % or charging | on | every 8 h (was 12 h; user changed it the same day) | every 4 h |
| ≥ 70 % or charging | off | every 24 h | every 8 h |
| < 70 % | on or off | every 24 h | none |

- **Long fetch**: daily rows for the source's whole reach (up to 8 days) plus hourly. The download
  covers 8 days (Google asks 192 h), and 72 h of hourly is stored. The far-day summaries on the daily
  row (noon cloud, day/night rain maxima) are computed from the whole download.
- **Short fetch**: daily rows and hourly for the next 3 days (72 h), and only those 3 days are
  stored. Daily rows for days 4+ stay as the last long fetch left them.
- A long fetch also counts as a short one: it restarts both timers.
- **Same rule for every source.** No displayed-vs-other split and no Google exception. This removes
  the view-age bands (`HourlyCadence`), the NEAR/FULL windows (`HourlyWindowPolicy`) and the
  displayed/other intervals (`ForecastCadence`).
- "Charging" counts as ≥ 70 % (assumed; the user's table names only battery level).
- "Due" is measured per source + site from the newest successful fetch of that horizon.

## Unchanged

- Current temperature and observations: `CurrentTempCadence`.
- A source not viewed in 8 days is not fetched in the background (`SourceFetchGate`).
- A user's Refresh: long fetch of the displayed source, any battery.
- A setup-screen location change: long fetch at once.
- Source toggle onto a source more than 4 h old: short fetch.
- On-demand hourly for a tapped day past 72 h (`HourlyOnDemand`, `fillHourlyGaps`).
- Quota blocks: a blocked part is not due until the block ends.

## Code

`:shared`:
- New `ForecastHorizonCadence`: `(isCharging, batteryLevel, isScreenOn) → (longIntervalH, shortIntervalH?)`,
  and `plan(lastLongMs, lastShortMs, now) → LONG | SHORT | null`.
- `ForecastParts.forRoutine` is replaced by the horizon plan. Each horizon is a full request (daily
  and hourly). The ask/store hours come from the horizon: SHORT = 72 h / 3 days, LONG = source reach
  (≤ 192 h asked, 72 h hourly stored).
- Delete `HourlyWindowPolicy` (NEAR/FULL, `FullMarker`, 20 h interval), `HourlyCadence` view-age
  bands, `ForecastCadence` displayed/other split, `ForecastParts.withFullWindow` / `forForced`
  hourly-limited handling (screen-on refresh stays actuals-only; toggles become SHORT).
- Markers: one LONG and one SHORT marker per source + site (Android prefs, desktop `app_logs`).
  Replaces the FULL and hourly markers.
- Short-fetch storage: daily rows outside the 3 days are not written; summaries for those days carry
  forward (unchanged rule: never blanked).

Android: `ForecastFetchCoordinator`, `ForecastFetchPolicy`, `WeatherWidgetWorker` (periodic tick must
be ≤ 4 h with the screen on and ≤ 8 h off when ≥ 70 %; ≤ 24 h below). Desktop: `DesktopForecastPlanner`,
`DesktopHourlyWindow` (deleted), `ForecastSchedule` sleep-until-due over the two horizons.

Logging: `FETCH_PLAN horizon=NWS:SHORT,GOOGLE_WEATHER:LONG … long=12h(last=…) short=4h(last=…)`.

## Tests

- `ForecastHorizonCadenceTest`: every row of the table; 69/70 % boundary; charging below 70 %; a long
  fetch satisfies short; screen-off → screen-on makes a 5-h-old short due.
- Plan tests: quota-blocked horizon not due; SHORT stores only 3 days of daily rows and never blanks
  days 4+ summaries; LONG asks 192 h of Google and stores 72 h.
- Integration (Android Robolectric worker + Room; desktop planner + DB): a simulated day at 85 %
  screen on fetches each source LONG ×2 and SHORT ×4 (6 total); at 60 % LONG ×1, SHORT ×0.
- Update/delete tests for the removed policies. Update CLAUDE.md (cadence tables) and ARCHITECTURE.md.
- Device: install on Fold 4 + Pixel, pull `FETCH_PLAN` rows after a few hours.

## Implementation notes (2026-10-16)

- **Daily reads had to change on both platforms.** Desktop `getDailyForecasts` read only the newest
  batch, and Android `ForecastSnapshotStore.getCachedDataBySource` kept only the newest batch and
  filled the other days with climate normals — a SHORT fetch would have hidden days 4+. Both now
  take each day's newest row at the newest fetch's site, from that fetch's first day on
  (`shownRows`; same lower bound as before). Desktop `getDailyForecastSnapshots` skips exactly those
  shown rows instead of "the newest batch".
- Android periodic tick at exactly 70 % was 480 min (old `> 70` tier); now 240, so a 4-h SHORT fetch
  is never held to an 8-h tick.
- `BatteryTier.cadenceTier` (desktop loop wake on power change) now has two tiers: high / low.
- Markers are new keys (`forecast_long_fetch_` / `forecast_short_fetch_`, desktop
  `FORECAST_LONG_FETCH` / `FORECAST_SHORT_FETCH`): the first check after the upgrade makes one LONG
  fetch per gated source.
- Removed: `hourlyLimited` on every refresh path (worker input, schedulers, desktop launch/viewing
  refreshes), `SourceLastView` (view age; the `source_last_view` table is still written, read by
  nothing), `DesktopHourlyWindow` → `DesktopForecastHorizon`.
- A user Refresh now writes the LONG marker (the old FULL marker was withheld so a daytime tap would
  not hold off the night's charger FULL; there is no charger FULL any more).
