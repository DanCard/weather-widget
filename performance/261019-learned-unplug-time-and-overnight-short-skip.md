# Learned unplug time, and no overnight SHORT before a pre-wake LONG

**Status:** implemented 2026-10-19 (see "As built" at the end). Follows
`performance/261019-pre-alarm-long-fetch-while-charging.md` (the pre-alarm LONG fetch).

Two follow-ups that plan left out:

1. **No alarm → predict the unplug from history.** Users who wake without a `setAlarmClock` alarm
   (no alarm, or a third-party app) get no pre-wake fetch today.
2. **Skip the overnight SHORT that the pre-wake LONG makes redundant.** On a charger with the screen
   off, SHORT runs every 8 h; one landing at 01:00 is overwritten at 06:15 before anyone has looked.

## 1. Recording unplug times

The app cannot hear an unplug in the background: `ACTION_POWER_DISCONNECTED` in the manifest is never
delivered at targetSdk 26+, and JobScheduler has no "not charging" constraint. Two observations,
combined:

- **Exact, while the process is alive.** Register `ACTION_POWER_CONNECTED`/`DISCONNECTED` at runtime,
  beside the screen receiver in `WeatherWidgetApp.onCreate` (`ScreenOnReceiver.registerScreenReceiver`).
  Runtime-registered receivers *do* get implicit broadcasts; this is how `SCREEN_ON` already works.
- **Bounded, always.** Every observation of the battery state — each worker run's
  `measureDeviceContext`, the plug-in job, the opportunistic job — records "seen charging at t". The
  charging current-temp loop runs every 10–16 min, so the last "seen charging" before the first
  "seen on battery" is within about 16 min of the unplug. The estimate is that last charging sighting
  (a lower bound, which errs early, the safe direction).

`ChargeSessionTracker` (Android, prefs only, no DB) keeps `chargingSinceMs` and `lastSeenChargingMs`.
On a charging→battery transition it appends `(unplugMs, sessionHours, exact)` to a ring of the last
28 sessions, **only for sessions ≥ 4 h** (an overnight charge, not a top-up). It logs
`UNPLUG_RECORDED at=… estimate=exact|last_seen session=…h`.

Privacy: times of day stay on the device, in prefs only, never sent anywhere, and are trimmed to 28
entries — the same stance as `source_view_days`.

## 2. Predicting the next unplug (`:shared` `UnplugRoutine`, pure)

Input: the recorded unplugs, `now`, zone. Output: the predicted next unplug instant, or null.

- Split by day type: **weekday (Mon–Fri) vs weekend**, judged by the local date of the unplug.
- For the next morning's day type, take that type's unplugs from the **last 21 days**. Require
  **≥ 4 samples** and a spread (interquartile range of time of day) of **≤ 60 min**, so that only a
  routine counts.
- Predict the **25th percentile** time of day, not the median: a fetch slightly early is still fresh,
  while one after the unplug is lost.
- Only the next occurrence within `PRE_WAKE_MAX_AHEAD_HOURS` (14 h).

The wake time used is `nextAlarmClock ?: UnplugRoutine.predict(…)`; an alarm always wins. It feeds the
existing `wakeAtMs` path unchanged (`ForecastFetchContext`, `PreWakeFetchScheduler.arm`), and logs say
which source was used: `PRE_WAKE_FETCH action=armed … wake=alarm|learned`.

Thresholds (4 h session, 21 days, ≥ 4 samples, IQR ≤ 60 min, P25) are first guesses, named constants
in one place, and logged daily (`UNPLUG_ROUTINE weekday=06:42±18m n=9 weekend=none n=2`) so they can
be checked against real data before tuning.

## 3. Skip the overnight SHORT (`:shared` `ForecastHorizonCadence.decide`)

SHORT is not due when **all** of the following hold:

- charging and screen off;
- a pre-wake LONG is pending (`preWakeAtMs != null`);
- that pre-wake comes within `SHORT_SCREEN_OFF_HOURS` (8 h).

Screen on (someone awake at 2 am) → the screen-on rules apply as now. A LONG that comes due by its
own interval still runs. The pre-wake is ≤ 8 h away, so nothing waits longer than the SHORT it
replaces would have. Log: the `FETCH_PLAN` fragment shows `short=skip(preWake)`.

## Tests

- `:shared` `UnplugRoutineTest`: too few samples → null; a scattered spread → null; weekday/weekend
  split; P25 chosen; DST weeks (local time of day, not UTC); prediction > 14 h ahead → null; a missing
  weekday leaves the others intact.
- `:shared` `ForecastHorizonCadenceTest`: SHORT skipped only when charging + screen off + pre-wake
  within 8 h; screen on still SHORT; a LONG due by interval still LONG. Extend the week simulation:
  with an alarm, overnight SHORT count drops (assert fewer total fetches at glance-level use).
- Android Robolectric `ChargeSessionTrackerTest`: transition records the last-seen estimate;
  broadcast records exact; sessions < 4 h ignored; ring capped at 28; repeated battery observations
  record once.
- Integration (tracker + `UnplugRoutine` + `PreWakeFetchScheduler`): seed 10 weekday unplugs at
  ~06:40, no alarm → armed with `wake=learned` about 05:55; then set an alarm → re-armed `wake=alarm`.

## Device verification

- Emulator: script charge sessions with `dumpsys battery set ac 1/0`, plus `date` changes, or seed
  the ring via a debug intent. Check `UNPLUG_RECORDED`, `UNPLUG_ROUTINE` and `PRE_WAKE_FETCH … wake=learned`.
- Pixel and Samsung over real nights (no alarm on the Pixel today): after 4+ weekday mornings, check
  that `UNPLUG_ROUTINE` matches when you actually unplug.

## As built (2026-10-19)

- **Only overnight charges count** (added during device testing). The Samsung was charging over a
  PC's USB during the day, and a 09:00–17:30 desk session is ≥ 4 h too: its 17:30 "unplug" would have
  scattered the morning routine, or for a desk worker become it. A session must also run through
  03:00 local (`UnplugRoutine.isOvernight`, `NIGHT_HOUR`). `ChargeSessionTracker` does not store
  other sessions (`UNPLUG_RECORDED action=skipped reason=daytime`), so desk days don't crowd nights
  out of the 28-entry ring.
- `UNPLUG_RECORDED` logs every charge that ends: `action=recorded … estimate=exact|last_seen n=…`, or
  `action=skipped reason=top_up|daytime|unbounded`.
- `PreWakeFetchScheduler.nextWake` returns a `Wake(atMs, source)`: alarm, else learned. `arm()`
  dedupes on the wake time, and its log says `wake=alarm|learned`. `UNPLUG_ROUTINE` is logged once per
  local day from the full sync.
- `ChargeStateReceiver` is registered at runtime in `WeatherWidgetApp.onCreate`. On the emulator, a
  plug followed by an unplug logged `UNPLUG_RECORDED action=skipped reason=top_up sessionMin=0
  estimate=exact`: both broadcasts were delivered to the running process.
- Gotcha for emulator tests: `dumpsys battery set status 2` keeps the battery "charging" after
  `set ac 0` (`BatteryStatePolicy` honours the status), so set `status 3` on unplug.
- Week simulation (glance-level screen use, 06:30 alarm): LONG 2/day either way, SHORT 1/day with the
  alarm vs 2 without.
- Emulator verification of the pre-alarm path (previous plan): the check ran at 08:33 for a 09:08 alarm
  (aimed 08:28; WorkManager latency), charging with the screen off, and fetched LONG for all four
  sources with `FETCH_PLAN … preWake=08:23`. `ACTION_NEXT_ALARM_CLOCK_CHANGED` was delivered within 1 s.
