# Desktop source drag acted on a stale list; forecast-delta test depended on time of day

## User prompts

1. "Desktop. I'm havng a hard time grabbing and dropping an API source in settings. I'm trying to move
   tomorrow.io up in priority. Are you able to capture a video? Are there sufficient logs? How to debug?"
2. "ok" (approved the proposed fix)
3. "I was able to drag and drop tomorrow.io to the top this time" (sent mid-turn)
4. "fix failing test."
5. "write session summary to summaries/ dir"

## Summary of Changes

1. **Fixed the stale drag list (`SettingsWindow.kt`)**:
   * `pointerInput(id)` keeps its gesture coroutine across list changes, but `order` came from
     `remember(enabled)`, which creates a new state holder on each change. The coroutine kept editing
     the old holder.
   * Symptoms: after a drag or ✕, the row followed the mouse but its neighbours did not step aside, and
     a removed source could return on release.
   * The working copy is now `dragOrder`, held in an unkeyed `remember` and non-null only during a drag.
     `order = dragOrder ?: enabled`, so there is nothing to keep in sync.
   * Android was not changed: `ItemTouchHelper` works in one long-lived adapter, so it has no equivalent
     copy. This was judged by reading `SettingsActivity.kt`, not by a test.

2. **Added drag logging**: `SOURCE_DRAG start` and `SOURCE_DRAG end` at `Log.d`, with the order at each.
   `CONFIG_SAVE` alone could not separate drags, because autosave collapses quick edits into one entry.

3. **Fixed the failing `DesktopForecastDeltaViewsIntegrationTest`**:
   * `loadCached(now)` did not pass `now` to `getHourlyWithHistory`, and that method did not pass `nowMs`
     to `getHourlyHistory`. Both fell back to the wall clock.
   * Before 10:48, the fixture's fixed time, the read treated the 10:00 hour as not yet started and used
     the late run: 3.44 instead of 2.48. It failed the same way on clean `HEAD`.
   * Production passes the real time, so users were not affected.
   * Fixed in `DesktopWeatherRepository.kt` and `DesktopWeatherDao.kt`. The test fixture is now dated
     tomorrow, so its time stays ahead of the wall clock and the test checks this wiring at every hour.

4. **Added regression tests (`SettingsWindowSourceListTest.kt`)**: drag a hovered row after a remove, and
   drag the same row twice with a mid-drag check on the neighbour. Both failed before the fix.

## Verification

* `./scripts/unit-tests.sh --fresh`: 6413 tests passed, 1 skipped. Shared and desktop results were from
  the 08:50 run on the same code.
* `DesktopForecastDeltaViewsIntegrationTest` passed at 08:50, before 10:48, when it had been failing. The
  afternoon case was not run.
* Live desktop: the user dragged Tomorrow.io from third to first. `SOURCE_DRAG` logged the move at 08:48
  and autosave wrote it. Repeat drags and drag-after-✕ are covered by tests only, not checked live.
* The desktop app was rebuilt and restarted on the final code at 08:52. Both processes were running; the
  window was not listed yet when checked right after launch.
* Android's `ForecastDeltaViewsIntegrationRoboTest` passes, but it also uses a fixture dated today and was
  not checked for the same time-of-day dependence.

## Session side effects

* A synthetic `xdotool` drag overlapped the user's own edits and moved Google Weather to the top at
  08:42:00. The user's list was then edited at 08:42:09, removing it.
* A misplaced gear click hit the widget's source toggle and changed the displayed source from NWS to
  Tomorrow.io at 08:47:43. It was left that way, so config now has `TOMORROW_IO` as the displayed source,
  with order `TOMORROW_IO, OPEN_METEO, NWS, SILURIAN`.
* The click missed because `wmctrl -lG` reported y = 1281 while the true window top was 1252.

## Debugging notes

* `import -window <id>` captures window stills reliably. The one `ffmpeg x11grab` recording showed other
  windows instead of Settings, and the reason was not found.
* `Save •` means an autosave is pending, not that a change needs a manual Save.
* `unit-tests.sh` can exit 0 while printing "not run this time", so use `--fresh` after changing `:shared`.

Nothing from this session has been committed.
