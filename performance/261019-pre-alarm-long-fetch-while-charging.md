# Pre-alarm LONG fetch while charging

**Status:** implemented 2026-10-19 (see "As built" at the end)

## Problem

A phone charging overnight with the screen off fetches on the `ForecastHorizonCadence` screen-off
rows: LONG every 24 h, SHORT every 8 h. The phase of those fetches is whatever the last fetch left,
not tied to when the user wakes. So at the first glance of the morning — unplug, deciding what to
wear, arguably the most important view of the day — the forecast can be up to 8 h old (SHORT) and
the far days up to 24 h old (LONG). It also misses NWS's ~03–04 local reissue and the overnight model
runs, which land in the early morning.

Screen-on does not fix it: it refreshes current temp / actuals, and the forecast only when due by the
cadence (`ViewingRefreshPolicy`). Once due, that fetch happens off the charger, after the user has
already looked.

## Idea

Most people with a routine set an alarm and charge overnight. **Move the overnight LONG fetch to just
before the alarm**, while the phone is still on the charger. Re-phase it; don't add a fetch:

- The pre-alarm LONG fetch resets the LONG and SHORT markers, so the 24 h screen-off LONG no longer
  comes due in the middle of the night. In steady state the daily LONG locks onto the alarm and the
  request count stays about the same. Google's 4–6 requests per LONG fetch move to a different time
  of day; they are not added.
- The signal is `AlarmManager.getNextAlarmClock()`: the next alarm the user's clock app set with
  `setAlarmClock`. It needs no permission and no learning, and it is null when no alarm is set.

Users without an alarm, or whose alarm app doesn't use `setAlarmClock`, keep exactly today's
behaviour.

## Rule (`:shared`, `ForecastHorizonCadence`)

`decide(…)` gains `wakeAtMs: Long?`, the predicted wake / unplug time; null when unknown.

LONG is due early ("pre-wake") when **all** of these hold:

1. `isCharging`. On battery the rule never applies; unplugging early simply loses it.
2. `wakeAtMs − PRE_WAKE_LEAD_MS ≤ now < wakeAtMs`, with `PRE_WAKE_LEAD_MS` = 45 min.
3. `wakeAtMs − now ≤ PRE_WAKE_MAX_AHEAD_HOURS` (14 h). This ignores an alarm two days away, such as
   a weekend with no alarm.
4. The last LONG fetch was before `wakeAtMs − PRE_WAKE_MIN_GAP_HOURS` (3 h). If a LONG fetch already
   happened this morning, don't fetch again; a snoozed alarm (whose new time is ≤ 45 min later)
   therefore never fetches twice.

`Decision` carries the pre-wake instant. `longDueAtMs` becomes `min(regular, wakeAtMs − LEAD)` when
conditions 1, 3 and 4 hold, so `nextDueAtMs` and `ForecastSchedule` see it. `logFragment` appends
`preWake=HH:mm` when present, e.g. `NWS:LONG(long=24h/9h,short=8h/5h,preWake=06:10)`.

Why the 45-minute lead: it is late enough to pick up the overnight reissues, and early enough that a
slow fetch (10–50 s each, all sources) and a WorkManager start delay still finish before the alarm.
The charging tick is 60 min, longer than the window, so the window alone cannot guarantee a check
lands inside it; see the scheduling below.

## Android

**Wake source.** `PreWakeClock.nextWakeAtMs(context)` returns `AlarmManager.nextAlarmClock?.triggerTime`.
It is passed into `ForecastFetchContext` (new field `wakeAtMs`), both where the context is built
(`DataFreshness`, `FullSyncPipeline`, `ForecastRepository`) and through
`ForecastFetchCoordinator.horizonDecision`.

**A timed check.** New unique one-time WorkManager work `pre_wake_fetch`:

- `initialDelay` = `wakeAtMs − PRE_WAKE_LEAD_MS + 5 min − now`, so it lands inside the window with
  slack; constraints `requiresCharging` + `CONNECTED`.
- On run: if `now ≥ wakeAtMs`, it ran late (it was waiting for a charger after an early unplug), so it
  logs `PRE_WAKE_FETCH outcome=missed` and returns. Otherwise it enqueues the normal unforced sync.
  The cadence (rule above) decides LONG, so there is no special fetch path and the quota checks and
  `SourceFetchGate` still apply.
- Armed (`REPLACE` only when the target time changed, else `KEEP`) from: the charging plug-in job
  (`PowerConnectedJobService`), every periodic tick, app start, and the
  `AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED` broadcast. That broadcast is on the implicit-broadcast
  exemption list, **but given the `USER_PRESENT` / `ACTION_POWER_DISCONNECTED` history (declared,
  never delivered), verify on device that it arrives**. If it doesn't, the plug-in job plus the
  hourly charging tick still re-arm it before any alarm set in the evening.
- Cancelled when `nextAlarmClock` is null or more than 14 h away.

The phone is not in Doze while charging, so a charging-constrained job runs close to its delay. That
is unlike the off-charger deferrals in `pixel_adaptive_battery_saver_follows_charger`.

Logs: `PRE_WAKE_FETCH action=armed at=06:10 alarm=06:55`, `action=cancelled reason=no_alarm|too_far`,
`outcome=sync_enqueued|already_fresh|missed`.

## Desktop

There is no alarm-clock API on Linux, so `wakeAtMs` = null and behaviour is unchanged. The rule lives
in `:shared`, so a laptop source could feed it later; that is out of scope here.

## Not in scope (possible follow-ups)

- **Learning unplug times** for users with no alarm. This needs a recorded unplug time, which the
  app does not have: `ACTION_POWER_DISCONNECTED` in `AndroidManifest.xml:151` is never delivered at
  targetSdk 26+, the same trap as `ScreenOnReceiver`. Revisit only if logs show many charging nights
  with `action=cancelled reason=no_alarm`.
- Skipping the overnight screen-off SHORT fetch when a pre-wake LONG fetch is coming. That changes a
  separate rule; decide it after measuring.

## Tests

- **`:shared` unit** (`ForecastHorizonCadenceTest`): pre-wake due inside the window; not before it;
  not at or after `wakeAtMs`; not on battery; not when the alarm is > 14 h out; not when LONG was
  fetched < 3 h before the alarm; snooze (alarm +9 min after a pre-wake fetch) not due again;
  `longDueAtMs` pulled forward only under conditions 1, 3 and 4; null `wakeAtMs` identical to today
  (table-driven, against the existing cases); `logFragment` format.
- **Android Robolectric**: arming from `ShadowAlarmManager.setNextAlarmClock` gives the right delay
  and constraints; REPLACE vs KEEP when the alarm moves or stays; cancel on null or far alarm; the
  worker logs `missed` when run after the alarm.
- **Integration** (coordinator + cadence + `WidgetStateManager` markers): a fetch context with a
  charging, screen-off `wakeAtMs` 30 min out plans LONG for every gated source, then plans nothing
  after the markers are written.
- **Steady-state cost simulation** (`:shared`): replay 7 days of
  "charge 22:30–06:50, alarm 06:30, screen pattern X" through `decide` and assert the LONG count per
  day is ≤ today's for the same pattern.

## Device verification (Pixel)

1. While charging, screen off, set an alarm ~55 min ahead.
2. `app_logs`: `PRE_WAKE_FETCH action=armed`, then about 45 min before the alarm
   `outcome=sync_enqueued`, `FETCH_PLAN … LONG(…preWake=…)` and `FORECAST_HORIZON horizon=LONG`.
3. Change the alarm time and check that the broadcast (or the next tick) re-arms it.
4. Unplug before the window and check for `outcome=missed` (or no run), and that no fetch happens on
   battery because of this rule.
5. Compare `api_usage_stats` Google rows for a few days before and after: about the same per day.

## Docs

On landing, add a sentence to CLAUDE.md's cadence section and update the `forecast_long_short_horizons`
memory.

## As built (2026-10-19)

- **Cost.** The "re-phase, don't add" claim holds at realistic screen use, but not in the worst case.
  The `:shared` week simulation (`ForecastHorizonCadenceTest`, steady state):
  - screen on at glances (06:30–07:30, 12–13, 19–22:30): **2 LONG fetches a day with or without the
    alarm**;
  - screen on all 16 waking hours: **3 with the alarm vs 2 without**. Starting the day's 8-hourly
    screen-on LONG chain 45 min earlier fits a third one in before bed.

  The test asserts "equal at glances, at most +1 with the screen always on". For Google that worst
  case is +4–6 requests a day, against a 200-a-day quota.
- **No `missed` outcome.** The timed check is an ordinary unforced full sync
  (`WidgetWorkScheduler.enqueuePreWakeSync`, unique name `weather_widget_pre_wake`, REPLACE, constraints
  charging + network, `reason=pre_wake`). If it runs late, after an early unplug, it is a plain
  cadence-judged sync at the next plug-in, which is harmless, so no special handling is needed.
- **No app-start hook.** `WeatherWidgetApp.onCreate` deliberately avoids WorkManager and DB work.
  Re-arming comes from every non-UI full sync (`trigger=sync`; hourly while charging), the plug-in job
  (`trigger=power_connected`) and `NextAlarmChangedReceiver` (`trigger=alarm_changed`). The receiver
  always logs `PRE_WAKE_FETCH action=alarm_changed result=…`; if those rows never appear on a device,
  the broadcast is not being delivered there.
- The pure check time is `ForecastHorizonCadence.preWakeCheckAtMs`: 40 min before the alarm
  (lead 45 − slack 5), now if already inside the lead, null when the alarm is missing, has passed, or
  is more than 14 h away.
- Arming state: prefs `pre_wake_fetch_prefs/armed_wake_at_ms`. The work is re-enqueued only when the
  alarm changes. Logs: `PRE_WAKE_FETCH action=armed at=… alarm=… delayMin=… trigger=…` and
  `action=cancelled reason=no_alarm|too_far|passed`. `FETCH_PLAN` fragments carry `preWake=HH:mm`.
