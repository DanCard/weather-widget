# Android: a phone left on a charger, unused for days, slows its background fetching

**Status:** superseded 2026-10-19, before implementation, by
`performance/261019-android-usage-hours-slow-fetching-when-unused.md` (dormant is one case of the usage table). Companion to
`performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md`.

## Why not the desktop rule

The desktop daemon is always running and hears an unblank at once, so it can stop while the user is
away and catch up in seconds. Android cannot:

- `SCREEN_ON` reaches the app only while its process is alive. The manifest route is never delivered.
- A device unused for days is exactly when the process is likely to be dead.
- If the phone stopped fetching, the next glance would be stale until the next periodic tick: up to
  60 min on a charger, and up to 1440 min below 50 %.

"Unused" also can't be told from "lightly used": a phone glanced at three times a day for 30 s may
never be caught with its screen on by a tick.

So the rule is only for the clear case, and only **slows** fetching. It never stops it.

## The case

A spare phone, a tablet on a dock, or a phone forgotten on a charger over a long weekend. On a charger
with the screen off it fetches observations every 16 min (~90 a day), a SHORT forecast every 8 h and a
LONG one every 24 h, indefinitely. On battery, the existing tiers already back off: below 70 % no
observations and LONG every 24 h only.

## Rule (`:shared` `UserPresence`, shared with desktop)

**Dormant** = charging, screen off, and no sign of the user for ≥ `DORMANT_AFTER_MS` (36 h). That is
past a normal night and a day without looking at it.

Signs of the user, in prefs `last_user_presence_ms`, updated on:

- `ScreenOnReceiver` screen-on (when the process is alive);
- any worker run that measures `isInteractive == true` (`measureDeviceContext`);
- widget taps (arrows, source toggle, day taps, refresh);
- app or activity opens;
- source switches (already recorded in `source_view_days`).

A fresh install or upgrade starts the clock at install time, so the device is never dormant first.

## While dormant

- **Observations / current temp:** charging screen-off interval 16 min → 120 min
  (`CurrentTempCadence`, a dormant flag on the charging row).
- **Forecast:** SHORT not due; LONG stays every 24 h (`ForecastHorizonCadence.decide`, a dormant
  input).
- Data is never frozen, at most ~2 h old.
- User-asked fetches (Refresh, switches, day taps) are unaffected.
- Logged on transition: `DORMANT_STATE dormant=true lastPresence=…` / `dormant=false dormantHours=…`.

## Recovery

Any sign of the user ends it at once and records presence. The hourly charging tick
(`ForecastFetchPolicy.CHARGING_TICK_MINUTES`) finds `isInteractive` within ≤ 60 min even with the
process dead. Worst case after picking the device up: one glance at data ≤ 2 h old.

## Tests

- `:shared` `UserPresenceTest` (shared with desktop): dormant only on charger + screen off + ≥ 36 h; an
  install starts the clock; any sign resets it.
- `CurrentTempCadence` / `ForecastHorizonCadence`: dormant rows (120 min; no SHORT); unchanged when
  not dormant (table-driven against the existing cases).
- Robolectric: each presence hook writes `last_user_presence_ms`.
- Integration: a simulated 3-day weekend on a charger, screen off → observation fetch count about
  36/day instead of ~90, no SHORT; a screen-on worker run returns to normal on the next run.

## Verification

- Emulator: charger on, screen off, presence prefs backdated by 40 h (as done for the pre-alarm test).
  Check `DORMANT_STATE dormant=true` and that the charging loop's interval goes to 120 min. Then wake
  the screen and check `dormant=false`.
