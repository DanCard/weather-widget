# Learned unplug prediction (replaces alarm-driven pre-wake), unified Android + desktop

## Problem

`ForecastHorizonCadence` pre-wake fetches every source 45 min before the next clock alarm whenever
the device is charging. The alarm is a poor stand-in for "the user is about to leave the charger":

- Fold-4, 2026-10-27/28: pre-wake fetched at 21:33 for a 22:00 alarm (and 07:20 for 08:00) while the
  phone stayed plugged in for another 17 h. Every such fetch is wasted — a phone still charging when the
  screen comes on could have fetched at that moment for free.
- `UnplugRoutine` only learns one overnight time per weekday/weekend group (≥ 4 samples, IQR ≤ 60 min).
  Both phones report `weekday=none weekend=none`.
- Irregular weekly habits are discarded, though they are in the data: Fold unplugged Tue 10-20 11:50 and
  Tue 10-27 11:40 (basketball).
- Desktop has no pre-wake at all, though a notebook leaves AC like a phone.

A pre-unplug fetch pays off only when the device actually leaves the charger; otherwise screen-on can
fetch for free.

## Design

### 1. Charge sessions, not overnight unplugs (`:shared` `ChargeHistory`)

Record every charge session `(pluggedAtMs, unpluggedAtMs, exact)` for 8 weeks (cap 400). Top-ups and
daytime charges are kept — they are exactly the irregular habits. Sessions also give the denominator:
a window is *evidence* only when the device was known to be charging at its start.

- Store interface `ChargeHistoryStore` in `:shared`; Android: existing `charge_session_prefs`
  (migrate the current `unplugs` string as sessions); desktop: file store like
  `DesktopSynopticBackoffStore`.
- Android observation stays `ChargeSessionTracker` (runtime receiver exact, else last charging sighting,
  unbounded gaps dropped). Desktop: UPower `line_power` `Online` changes (already monitored in
  `DesktopProcess`) plus `PowerDetector` sightings. A battery-less PC never unplugs → never predicts.

### 2. Patterns (`:shared` `UnplugPredictor`, replaces `UnplugRoutine`)

Two pattern kinds, both cluster unplug minute-of-day within ±45 min:

| Kind | Groups | Active when |
|---|---|---|
| Weekly | same weekday, last 8 weeks | ≥ 2 hits and recency-weighted hit rate ≥ 0.5 |
| Daily | weekday set / weekend set, last 21 days | ≥ 4 hits and hit rate ≥ 0.5 |

- *Hit* = an unplug inside the cluster window that day; *miss* = charging at window start, no unplug
  inside it. Unknown days (no sighting) count as neither.
- Recency weight: half-life 3 weeks, so a new habit is active after two occurrences and a dropped one
  fades in about a month.
- Fold today: `tue 11:40–11:50 2/2 → active`; other days single samples → none.

### 3. Alarm as a learned prior

Each fired alarm (time-of-day bucket ±30 min, per weekday set) keeps a Beta(α, β) posterior, starting at
Beta(1, 1). Outcome = charging at the alarm and unplugged within 90 min after → hit (α+1), else miss
(β+1), recency-decayed like patterns. The alarm counts as a predicted unplug only while the posterior
mean ≥ 0.5, and then at `alarm + learned median offset`. When an alarm and a pattern agree, the alarm
pins that day's time.

- Fold today: 22:00 alarm — 2 misses → 0.25 → ignored. 08:00 alarm — misses → ignored.
- Desktop host has no alarms (`nextAlarmMs = null`); the rest is identical.

### 4. Pre-unplug fetch (shared runner, platform host)

`PreUnplugFetchRunner` in `:shared` owns: candidate selection (earliest active prediction within 14 h),
check time (cluster's earliest observed unplug − 15 min, clamped 20–60 min before its centre), the
existing gates (charging, last LONG ≥ 3 h old before the unplug), the overnight-skip
(`waitsForPreWake` only for an active prediction), and logging. Hosts supply store, power state, next
alarm, and a timer: Android WorkManager (charging-constrained, delayed `REPLACE` only for this delayed
work), desktop the daemon's `ForecastSchedule` sleep.

### 5. Charging screen-on forecast catch-up

Shared `ForecastHorizonCadence.screenOnCatchUp`: on a charger, screen-on/unlock fetches the forecast now
when it is past the 4 h screen-on interval. Android `ScreenOnReceiver` enqueues a full sync with `KEEP`;
desktop calls it from its screen-unlock/resume path. This is what makes dropping the bare alarm safe.

### 6. Logs

- `UNPLUG_PATTERNS` (daily): `tue=11:40±5m 2/2 p=0.86 alarm22:00 p=0.25(0/2) daily=none`
- `PRE_UNPLUG_FETCH`: `armed at=… predictedUnplug=… source=weekly(tue)|daily|alarm p=…`, `cancelled …`
- `PRE_UNPLUG_OUTCOME`: hit/miss with minutes early/late, so accuracy is queryable on both platforms.

## Tests

- `:shared` JVM: clustering, hit/miss with unknown days, recency decay, weekly Tuesday case, alarm
  posterior drops after misses and recovers after hits, alarm offset, candidate/lead selection,
  screen-on catch-up, `waitsForPreWake` only with an active prediction. Each must fail on the current
  alarm-only code.
- Runner guard tests with fake hosts for Android and desktop (2+ classes).
- Android Robolectric: prefs migration of old `unplugs` string; `ScreenOnReceiver` charging catch-up.
- Desktop: file store round-trip; UPower Online transition records a session.

## Verification

Install on Fold/Pixel and run desktop; confirm the 22:00 alarm logs `alarm p=…` below threshold and no
`reason=pre_wake` sync, `UNPLUG_PATTERNS` shows `tue=11:4x`, and on Tue 11-03 a fetch near 11:25.

## Docs

`arch/hourly-forecast-update-cadence.md` (pre-wake → pre-unplug section), KDoc on the new classes.

## Implementation notes (deviations, 2026-10-28)

- Lead: the check stays at the existing 45-minute lead before the cluster's earliest observed unplug,
  not "earliest − 15 min". The Fold's armed check ran 13 minutes late on 2026-10-28 (21:20 → 21:33), so a
  shorter lead risked landing after the unplug.
- Alarm buckets are not split weekday/weekend: an alarm is identified by its time of day, and a daily
  alarm would otherwise get half its evidence. Trust starts at Beta(1, 1) = 0.5 and needs > 0.5, so a bare
  alarm with no history predicts nothing (no pre-unplug fetch for the Fold's 22:00 alarm on rollout).
- The armed check and the prediction awaiting its hit/miss are tracked separately
  (`trackedWakeAtMs`), so a prediction that has passed is still judged after its window closes.
- Desktop screen-on catch-up already existed (the forecast loop re-plans on every screen wake); only
  Android needed `ScreenOnReceiver` wiring.
- Android's WorkManager unique name keeps its old value (`weather_widget_pre_wake`) so an already
  enqueued check stays addressable; the migration carries the alarm-era armed check so the first arm
  cancels or replaces it.
