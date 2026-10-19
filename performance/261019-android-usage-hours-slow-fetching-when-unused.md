# Android: learn when the phone is used, and fetch less in the hours it isn't

**Status:** proposed — awaiting approval (2026-10-19). Supersedes
`performance/261019-android-dormant-on-charger-slows-background-fetching.md` (never implemented).
Companion: `performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md`. Desktop
reacts to the user's return instantly, so it needs no prediction.

## Why

Several rules each guess one slice of "is anyone going to look soon?":

- the learned unplug time (pre-alarm LONG);
- the overnight SHORT skip;
- the proposed overnight observations every 2 h;
- the proposed dormant-on-charger state.

One model answers it for every hour of the week. Fetch less in hours that are usually unused, and
fetch ahead of hours that are usually used. Nights, weekends, a work phone's evenings and a spare
tablet are all the same case.

Scale, on the user's Pixel on 2026-10-18 (on AC all day): about 730 NWS requests (`points` 203,
observations 530), almost all made with the screen off. Most hours had 7–10 screen-off syncs and none
with the screen on.

## Part 0 — independent, ships first: charging screen-off observations 16 → 30 min

- `:shared` `CurrentTempCadence.CHARGING_SCREEN_OFF_MINUTES` = 30 (user, 2026-10-19). That halves
  the volume (~730 → ~400 a day). The first glance is ≤ 30 min old; the displayed current temperature
  is interpolated from the hourly forecast between observations, so the number barely moves.
- **Charging screen-on catch-up:** `CurrentTempCadence.screenOnCatchUp` returns NONE on a charger
  today ("charging has its own 10-min loop"). Change it to FETCH_NOW when the last observation is
  ≥ 15 min old, so a glance while charging is fresh whenever the process is alive to hear `SCREEN_ON`.
  Otherwise the 30-min cap holds.
- The table is shared, so **desktop** also goes 16 → 30 min with the screen off on AC. With the away
  state this matters only for the first hour of a blank screen. The desktop's unblank catch-up
  (`kickObservationCatchUp("screensaver:wake")`) gets the same ≥ 15 min rule.

## Part 1 — record usage, log only (about 2 weeks)

**Storage: aggregated, no timestamps** (privacy, as for `source_view_days`, user 2026-10-10). Prefs
`usage_hours`: 168 slots (7 days × 24 local hours), each the probability that the phone is used in
that hour of the week. It is an exponentially weighted average updated once per hour:
`p ← p·(1−α) + α·used`, with α = 0.25, which is about the last 4 weeks. A slot is "used" for an hour
if any sighting fell in it. Local only; never sent anywhere; deleted with app data.

**Night prior** (user, 2026-10-19: assume most people don't use the phone in the middle of the
night). The table starts with 00:00–05:00 local at p = 0.05 (unused) and every other hour at p = 0.5
(used); it does not start empty. The window is conservative: plenty of people are up at 23:00 or by
06:00. A known wake before 05:00 (alarm or learned unplug) ends it earlier. Learning moves each slot from
there: a shift worker's or night owl's real use fills those hours within a week or two. The prior also
offsets Android's undercounting. Missing a sighting only matters when it would have proved the prior
wrong, while a sighting is reliable positive evidence.

**Sightings** (any one marks the current hour used):

- `ScreenOnReceiver` screen-on (only while the process is alive);
- any worker run that measures `isInteractive == true` (`measureDeviceContext`);
- widget taps (arrows, source toggle, day taps, refresh);
- app or activity opens;
- source switches.

**Known bias:** Android hears screen-on only while the process lives, and a tick sees the screen on only
by chance. So the table undercounts use, most of all for light users. Phase 1 exists to measure that
before anything acts on it.

**Logs, once a day:**

- `USAGE_HOURS weekday=07-09,12,17-23 weekend=09-23 weeks=3` — hours with p ≥ 0.5, per day type,
  and how many weeks the table has.
- `USAGE_PREDICTION_CHECK predictedUnused=02-06 sightingsInUnused=1 wouldSaveObs=8 wouldSaveShort=1`
  — yesterday's use that fell in hours the table called unused (misses), and what phase 2 would have
  saved.

Decide on phase 2 from these logs: misses should be rare (say < 1 a week) on both phones. With the
night prior, misses are measured from day one, so about a week of logs should be enough (was 2).

## Part 2 — act on it (after phase 1, on your approval)

- **Unused hour** = p < 0.1 for this hour and the next. Because of the night prior, this holds for
  00:00–05:00 from the first day; other hours qualify only once learning has pushed them below 0.1.
- **In unused hours:** charging screen-off observations every 120 min instead of 30; SHORT not due.
  LONG stays every 24 h. **Staleness cap:** data is never older than 2 h, so a misprediction costs one
  glance at 2 h old data, never yesterday's.
- **Ahead of use:** the first used hour after a run of unused ones counts as a wake time for the
  existing pre-alarm rule (`ForecastHorizonCadence.preWakeAtMs`). The pre-alarm check then also
  fetches observations. Wake priority: the alarm when set, else the earlier of the learned unplug and
  the usage return. The earlier one is safer, and checking the phone in bed can come before unplugging.
- **Dormant falls out of it:** no sighting for 36 h → every hour counts as unused until the next
  sighting. That is the case of a spare device on a charger; the averages alone would take weeks to
  fade.
- **Never affected:** Refresh, source switches, day taps, history refills, location changes.

## Desktop

No usage table: it hears the return (unblank, unlock, resume) and reacts in seconds; see the away-state
plan. Part 0's 30 min reaches desktop through the shared table.

## Tests

- **Part 0:** `CurrentTempCadenceTest` charging screen-off 30, screen-on catch-up ≥ 15 min on a
  charger, desktop `shouldCatchUpObservations` for `screensaver:*` on AC.
- **`:shared` `UsageHours` (pure):**
  - slot update and decay;
  - local hour-of-week across DST;
  - night prior: 00:00–05:00 unused on day one; a week of 02:00 use lifts 02:00 above 0.1; a wake
    before 05:00 ends the window;
  - unused-hour test;
  - the next return after unused hours;
  - dormant after 36 h;
  - the `USAGE_HOURS` / `USAGE_PREDICTION_CHECK` log lines.
- **Android Robolectric:** each sighting hook marks the current hour; the hourly roll-up is
  idempotent; the prefs round-trip.
- **Part 2:**
  - cadence tables with the unused flag, unchanged without it (table-driven);
  - the staleness cap;
  - wake priority (alarm > earlier of unplug and usage return);
  - integration: 3 simulated weeks of a 07:00–23:00 user, then a night. Observations every 120 min
    from 00:00, the pre-alarm check before 07:00, and a 03:00 screen-on returns to normal at once.

## Verification

- **Phase 1:** after a few days on the Pixel and the Samsung, `USAGE_HOURS` should match how each
  device is really used: the Samsung as a tester's device, the Pixel as a personal phone.
- **Phase 2:** `api_usage_stats` NWS counts per day before and after, and `USAGE_PREDICTION_CHECK`
  misses near zero.

## As built (2026-10-19): Part 0 and Part 1

- **Part 0:** `CurrentTempCadence.CHARGING_SCREEN_OFF_MINUTES` = 30, and
  `CHARGING_SCREEN_ON_CATCH_UP_MINUTES` = 15. `screenOnCatchUp` on a charger returns FETCH_NOW when
  ≥ 15 min old, else NONE (never SCHEDULE: the charging loop is running). Android logs
  `reason=charging_screen_on_catchup` and `SCREEN_ON_OBS_LOOP … charging=true`. Desktop's
  `shouldCatchUpOnKick("screensaver:*")` follows it.
- **Part 1:** `:shared` `UsageHours` (pure state, encode/decode) and Android `UsageHoursTracker`
  (prefs `usage_hours_prefs`).
  - **Sightings:** `ScreenOnReceiver.handleScreenOn`, `WeatherWidgetWorker` (an interactive run
    sights; any other run advances), `WidgetActionReceiver.onReceive`, and `onActivityResumed` (an
    Application lifecycle callback, prefs only).
  - **Logs:** `USAGE_HOURS` once per local day, and `USAGE_PREDICTION_CHECK` once per completed day,
    from the full sync.
  - The `wouldSave…` counts in the plan's example log line were dropped. Whether a phone was charging
    in a given past hour is not recorded, so a saving cannot be stated honestly. `predictedUnusedHours`
    and the misses are what decide phase 2.
