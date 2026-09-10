# Session summary — Tomorrow.io five-minute history actuals

**Date:** 2026-09-10 · **Plan:**
[plans/260910-tomorrow-five-minute-history-actuals.md](../plans/260910-tomorrow-five-minute-history-actuals.md) ·
**Status:** implemented and runtime-verified on Pixel 7 Pro, Android emulator, and desktop; full
staggered test gate passes; **not committed**

## User prompts

> Large current temperature variation between different weather API providers. Any thoughts on
> that? Makes the data not trustworthy.

> Why does app request timestep hourly? Would 15 minutes, 10 minutes, or 5 minutes be better?
> Should old inconsistent values, be wiped out by newer actual data?

> I suggest not capturing real time readings from tomorrow since they aren't trustworthy. Just
> capture 5 minute bucket readings. What do you think?

> create plan for this

> tomorrow forecast fetching isn't changing? correct?

> proceed

## Outcome

Tomorrow.io forecast fetching remains the existing combined `1h + 1d` Timeline request. Actual
temperatures now come from a separate aligned `5m` Timeline request implemented in `:shared` and
used by both Android and desktop.

1. Realtime and elapsed-hourly Tomorrow.io values are excluded from the actual-temperature series.
2. Every returned five-minute timestamp is stored exactly; response records are not locally
   rounded.
3. Request windows end at the latest five-minute UTC boundary, so overlapping fetches revise
   stable exact timestamps.
4. A later fetch replaces the prior value for the same timestamp.
5. Android and desktop use the same shared parsing, provenance filtering, normalization, and
   actual-series policy.
6. The source is labeled `Tmrw 5-minute history`, rather than presented as a physical-station
   observation.

## Runtime defects found and corrected

### Unsupported five-minute precipitation field

The first live request included `precipitationAccumulation`. Tomorrow.io rejected that field for a
`5m` timestep with HTTP 400. It was removed from the actuals request; precipitation and all other
forecast behavior remain unchanged in the separate forecast request.

### Unstable request-minute intervals

Using literal `endTime=now` caused Tomorrow.io to return request-minute-relative intervals such as
`:09`, `:04`, and `:59`. Calls made in different minutes therefore could not update the same exact
keys. The request start and end are now anchored to the latest five-minute UTC boundary. Returned
`startTime` values are still persisted unchanged.

### Premature legacy cleanup during quota failure

During rollout testing, shifted development rows were incorrectly counted as replacement coverage.
A subsequent forecast HTTP 429 returned no aligned rows, but cleanup still removed the Pixel's
older rows. The cleanup gate now requires at least one aligned
`TOMORROW_IO_5M_HISTORY` row (`timestamp % 300000 == 0`) before deleting anything. Off-grid
intermediary rows are retired only after aligned replacement coverage exists. Failed five-minute
requests retain cached actuals.

## Runtime verification

1. A direct Tomorrow.io quota probe returned HTTP 200 after the temporary rate limit cleared.
2. Pixel `2A191FDH300PPW` was verified by device properties as a Google Pixel 7 Pro. Its incremental
   refresh stored 13 aligned rows covering 12:40–1:40 PM and rendered the pink actual line with an
   87.7 F current label.
3. Emulator `emulator-5554` stored 277 aligned rows covering 2:45 PM the prior day through 1:45 PM.
   Cleanup retired 260 legacy/off-grid rows only after replacement coverage existed. The widget
   rendered the pink actual line and an 87.7 F current label.
4. Desktop stored 277 aligned rows covering 2:40 PM the prior day through 1:40 PM and rendered the
   same actual-line shape with an 87.7 F current label.
5. The 13 timestamps shared by Pixel and desktop had zero temperature mismatches.
6. Runtime screenshots were captured at:
   `/tmp/pixel-final/home.png`, `/tmp/emulator-home-left1.png`, and
   `/tmp/weather-widget-desktop-final.png`.

## Automated verification

1. Combined `:shared:test`, `:desktop:test`, `:app:testShortDebugUnitTest`, and
   `:app:testMediumDebugUnitTest`: passed in 43 seconds.
2. Emulator suite: 95 total, 93 passed, and 2 skipped in 30 seconds.
3. `:desktop:createDistributable`: passed.
4. `git diff --check`: passed.
5. The full staggered gate passed 4,092 unit tests: 1,023 Short, 26 localization, 66 Medium,
   1,040 Long, 1,559 shared, and 378 desktop tests.
6. The same gate passed the emulator suite (95 total, 93 passed, 2 skipped) and installed the debug
   APK on both connected Android targets.

## Follow-up Long-test correction

`WeatherWidgetProviderDayTapSourceGapRoboTest` initially failed with
`DAY_CLICK_RENDER_OK breadcrumb must be persisted; got []`. The identical failure reproduced on a
clean detached `HEAD`, proving it was not caused by the Tomorrow.io implementation.

The receiver test used a detached `SupervisorJob` and called `advanceUntilIdle()` once. That drains
the virtual coroutine scheduler but does not wait for Room's real executor threads. The widget mode
changed before the first DAO suspension, while the terminal `DAY_CLICK_RENDER_OK` breadcrumb was
written only after rendering returned, so the assertion raced the still-running render.

The test-only correction now pumps the virtual scheduler without advancing the broadcast watchdog
and waits, with a five-second real-time bound, for either `DAY_CLICK_RENDER_OK` or
`DAY_CLICK_FAIL`. It also clears the tracked receiver job during teardown. The focused test passed,
then all 1,040 Long tests and the complete staggered gate passed. No production code changed for
this correction.

## Lines changed at implementation handoff

These counts describe the Tomorrow.io implementation immediately before this summary file was
added:

1. Production: `+587 / -245`.
2. Tests: `+669 / -160`.
3. Code total: `+1,256 / -405` — 1,661 lines of churn, net `+851`.
4. Documentation and plans: `+688 / -0`.
5. Total including documentation: `+1,944 / -405`.

## Repository state

The implementation, tests, plans, and this summary remain uncommitted. No push was performed.
