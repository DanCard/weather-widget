# Daily icon gap: future columns anchored to an undrawn snapshot low

Date: 2026-11-04

## Prompt

1. "Desktop: daily forecast view: distant between bottom bar and weather indicator icon not consistent."

## Evidence

1. Desktop screenshot: Thu/Sat/Sun/Mon/Tue icons sat ~4 px under their bars; Fri and Wed sat ~26-30 px
   below, with the same low value as neighbours (Thu and Fri both 49°).
2. Temporary `DAILY_ICON_ANCHOR` trace of the anchor inputs per column:
   - col 4 (Fri): `solidLow=49 snapLow=48` -> `anchorLow=48`
   - col 9 (Wed): `solidLow=53 snapLow=52` -> `anchorLow=52`
   - columns with equal or warmer snapshot lows had a normal 9 px icon offset.
3. 1° is about 27 px on this desktop scale, matching the observed extra gap.

## Root cause

`DailyDayValueResolver.iconAnchorLow` takes the lowest of mercury bottom, forecast low and snapshot
("yesterday's forecast") low. Only today and past columns draw a snapshot bar. For future columns a
colder snapshot low pulled the icon below the visible bar. Android uses the same call, so it had the
same latent bug.

## Fix

1. `shared/.../DailyDayValueResolver.kt`: `iconAnchorLow` gains `snapshotBarDrawn` (default true);
   the snapshot low only counts when true.
2. `app/.../DailyColumnRenderer.kt` and `desktop/.../DailyForecastGraph.kt` (draw path and tap-layout
   path) pass `snapshotBarDrawn = isToday || isPast`.
3. `DailyDayValueResolverTest`: new test for the Fri case (49° bar, 48° snapshot -> anchor 49°).
4. Kept a VERBOSE `DAILY_ICON_ANCHOR` trace in the desktop draw path (logcat/console only).

## Verification

1. `:shared:test --tests "*DailyDayValueResolverTest*"`, `:desktop:compileKotlin`,
   `:app:compileDebugKotlin` pass.
2. Desktop rebuilt and restarted; screenshot shows Fri and Wed icons now sit under their bars like
   the other future columns.
3. Not run: emulator suite, Android widget on a device (Android covered by compile and the shared test).

Changes are uncommitted.
