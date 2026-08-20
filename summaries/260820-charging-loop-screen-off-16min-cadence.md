# Current temp "every 10 min while charging" not observed on Samsung — screen-off 16-min cadence (2026-08-20)

## Problem

Samsung (SM-F936U1, RFCT71FR9NT):

"Device is suppose to update current temps every ten minutes while charging. I'm on the 'Current
Observations' activity and I don't see that happening. Says last update was 4:52 am, current time
is 5:06 am."

## Diagnosis (evidence-first)

Device state at 05:07 PDT: charging via USB (`status: 2`), battery 65%, screen interactive/awake.

The charging current-temp loop is healthy and running continuously — but at **16-minute intervals
overnight**, not 10. `app_logs` shows an unbroken `charging_loop` chain all night:

| Time | Event | Screen |
|------|-------|--------|
| 04:52:46–04:52:54 | fetch runs, NWS + Open-Meteo succeed → the "4:52" the user sees | off (`isInteractive=false`) |
| 04:52:55 | loop reschedules `delayMinutes=16` → due **05:08:55** | off |
| 04:59:00 – 05:06:00 | screen comes on; scheduler re-checks with `intervalMinutes=10 interactive=true`, decides **`keep`** (pending work only ~3 min away) | on |
| 05:08:55 | the 16-min-scheduled fetch fires, completes 05:09:00 | on |
| 05:09:01 / 05:09:12 | loop reschedules at **10-min** cadence (`delayMinutes=10`, due ~05:19:12) | on |

So at 5:06, "last update 4:52" is the expected state — the next fetch was already queued for
5:08:55, exactly 16 minutes after the 4:52 fetch.

## Why 10 minutes didn't apply

The 10-minute interval is **only while the screen is on**. Deliberate, per
`CurrentTempFetchPolicy.kt` (commit `af1a76ac` "Fetch current temp every 16 min while charging with
screen off"):

```kotlin
const val CHARGING_INTERVAL_MINUTES = 10L              // screen interactive
const val CHARGING_SCREEN_OFF_INTERVAL_MINUTES = 16L   // screen off
```

All night the screen was off, so the loop correctly ran at 16 min. It switched to 10 min once the
screen came on (confirmed by the 05:09 reschedules).

## The one real gap

When the phone is unlocked, the loop does **not** fetch immediately, and it does **not** shorten an
already-scheduled 16-minute timer. Two reasons:

1. `ScreenOnReceiver.handleUserPresent` (which would enqueue an immediate `screen_unlock_charging`
   fetch) is **dead code** — `ACTION_USER_PRESENT` is an implicit broadcast a manifest-registered
   receiver doesn't receive on API 26+. The class docstring documents this explicitly.
2. The fallback `CurrentTempUpdateScheduler.decideChargingLoopWork` **`keep`s** the pending 16-min
   work when the screen wakes, because its due time (05:08:55) falls inside the 10-min + 2-min grace
   window. So the first on-screen fetch still lands ~16 min after the last screen-off fetch, then
   10-min cadence resumes.

Net effect: unlock mid-interval and you can wait up to ~16 min (not 10) for the first fresh fetch,
then it's 10 min thereafter.

## Conclusion

Not a failure of the charging loop — it's the screen-off 16-minute policy doing what it was designed
to do, plus the known dead screen-unlock fetch path.

## Open options (not yet implemented)

1. Leave as-is (16 min screen-off is intentional to save battery).
2. Restore an immediate fetch on unlock (replace the dead `ACTION_USER_PRESENT` path with a reliable
   trigger).
3. Make waking the screen shorten/replace an in-flight 16-min timer so the first on-screen fetch
   lands at the 10-min mark.
