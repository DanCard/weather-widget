# Desktop notebook: a fresh forecast when the user comes back

**Status:** implemented 2026-10-19. Related: `performance/261019-pre-alarm-long-fetch-while-charging.md`
and `performance/261019-learned-unplug-time-and-overnight-short-skip.md` (Android).

## Goal

A notebook user opening the lid sees a recent forecast for the displayed source and for the source
they switch to next, whatever the battery level.

## What was wrong (this machine, resume at 2026-10-19 07:56)

- The displayed source (Google) was refreshed 16 s after resume, by the catch-up refresh. That part is
  fine; the 15–25 s hold-off is deliberate.
- The other sources waited for the forecast loop's fixed first-plan settle
  (`FORECAST_LOOP_STARTUP_SETTLE_MS`, 5 min): NWS, Open-Meteo and Silurian arrived at 08:01. A switch
  in those 5 minutes showed last night's forecast.
- On battery below 70 %, the catch-up refetched the displayed forecast only once it was 24 h old
  (the cadence's LONG interval), so a notebook opened at a café could show an 18 h old forecast.

## Changes

1. **The loop plans when the catch-up is done** (`forecastLoopWaitsForLaunch`, `awaitLaunch`). After
   a startup or an automatic catch-up (resume, network restore), the loop's first plan waits for that
   launch refresh to finish, capped at the old 5 min, instead of always waiting 5 min. The settle
   existed only so the loop wouldn't fetch the displayed source beside the launch refresh; once that
   refresh is done, its markers are moved and the loop sees the source as not due. Source and
   location changes keep the full settle: clicking through sources restarts the loop each time, and a
   loop fetch started between two clicks would be cancelled after it was billed. Log:
   `FORECAST_LOOP_START reason=… endedBy=launch_done|settle waitedSec=…`.
2. **Catch-ups never leave the displayed forecast more than 8 h old** (`CATCH_UP_FORECAST_MAX_AGE_MS`):
   `launchForecastStaleAfterMs` for `resume:*` / `network:*` = min(cadence, 8 h). This covers network
   restores because on a resume the `network:restored` kick supersedes the resume kick (last-wins;
   07:56:19 → 07:56:26). A resume is the user arriving with the screen on, not a background wake, and
   the background loops keep the user's cadence table. Daemon startup keeps the cadence, as before.

## Considered and dropped

- **Learning the morning return on a notebook that stays awake** (the phone's pre-wake, desktop
  version). On desktop, unblanking already wakes the forecast loop at once
  (`FORECAST_LOOP_WAKE reason=screen`), which re-plans with screen-on intervals that are always due
  after a night, so a pre-wake would save seconds. That isn't worth a session tracker, a state file and
  the away-edge parsing. The phone needs it because Android cannot fetch promptly on screen-on.
- **Waking a suspended notebook** (`rtcwake`, `WakeSystem=` timers): needs root, and wakes a closed
  notebook in a bag.

## Tests

- `ForecastLoopStartTest` (virtual time): a resume plans when the launch refresh finishes (40 s, not
  5 min); a refresh still running at 5 min hits the cap; no refresh or a finished one → plan at once;
  a source change keeps the whole settle.
- `DesktopApiUsageAndRefreshGateTest`: resume / network restore at a 24 h cadence → 8 h, and an 18 h
  old forecast is refetched; a 4 h cadence still wins; startup keeps 24 h; a catch-up with the cadence
  off → 8 h (previously never).

## Verification

- Desktop restart: `FORECAST_LOOP_START reason=startup endedBy=launch_done` within seconds, not 5 min.
- Next real suspend/resume: the other sources' `REFRESH` rows within about 1 min of `RESUME_DETECT`,
  not +5 min.
