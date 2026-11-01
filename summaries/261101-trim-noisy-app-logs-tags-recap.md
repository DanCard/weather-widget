# Trim noisy app_logs tags: recap

The noisy tags are trimmed and running on both phones and desktop. Nothing is committed. All 6,552 unit
tests pass, but only about a minute of post-install log activity was available to judge from, so the
savings below are estimates. Emulator tests were not run, since this only changes logging.

## Changes

| Tag | Change | Why |
|---|---|---|
| All VERBOSE lines on desktop | No longer stored; console only | **This was a bug:** `DesktopWeatherDao.log` wrote them to the database, unlike Android. `DAILY_HISTORY_BLEND` alone was 43% of desktop's log text. |
| `DAILY_RENDER`, `TODAY_BAR_DEBUG`, `TODAY_OVERLAY` | VERBOSE | These fired on every render. `DAILY_RENDER_EMPTY` still persists. |
| `DAILY_HISTORY_STABLE`, `DAILY_RECOMPUTE_SKIP` | VERBOSE on both platforms | These only say nothing changed. `DAILY_HISTORY_OVERWRITE` still records real changes. |
| `HOURLY_UNIFY_DROP` | VERBOSE when hours were only borrowed | Rows where hours were lost still persist as WARN. |
| `WIDGET_RENDER_PERF` | "Slow" threshold raised from 150 to 1000 ms | At 150 ms nearly every render counted as slow. |

## Expected reduction in log text

- **Samsung:** about 39%
- **Pixel:** about 27%
- **Desktop:** about 55%

## Verification after installing

- **Phones:** the install triggered daily-view paints on both, and none of the trimmed tags reached
  `app_logs`. They still appear in logcat at VERBOSE on both phones.
- **Desktop:** there were no VERBOSE rows or `DAILY_HISTORY_BLEND` rows, even though
  `DAILY_HISTORY_OVERWRITE` rows show a recompute ran.
- **Tests:** added a desktop test for the VERBOSE fix and moved two Android tests from `app_logs` to
  logcat, because their tags no longer persist.

## What to expect

- **Less after-the-fact evidence:** bug-report databases no longer include these tags.
  `TODAY_BAR_DEBUG` has helped diagnose past bugs, such as the `fHigh=null` case, and that now needs
  live logcat or a repro. If it is missed, a row written only when the values change would be the fix.
- **Files shrink slowly:** old rows age out over 72 hours. `VACUUM` only runs at 8 MB or more free, so
  the log trim alone may not shrink the file; freed pages get reused first.

## Noticed but not changed

- **Render count:** the Samsung logged 4,689 renders of 150 ms or more in 72 hours, about 65 an hour.
  That seems high and may be worth a battery check.
- **`CURR_TEMP_RESULT`:** kept because it is a key diagnostic, but about 70% of its desktop rows repeat
  the previous one, so a change-only version could save more.
- **`notes/prompts-for-llm.txt`:** showed as modified, but not from this work.

## Open questions

- Whether to commit this.
- Whether to delete the old backups in `~/.local/share/weather-widget/`. They take 153 MB, about five
  times the live 29 MB database, and were not touched. One is from today
  (`weather.db.bak-pre-prune-20261101`) and may be worth keeping.

## Notes

- **Two paths to the database:** the rule that VERBOSE is never stored only held on Android, because
  Android's `AppLogDao.log` enforces it. Shared runners pass log levels through their `Host`, so desktop
  needed the same check. Putting it at the write point covers every caller.
- **Thresholds go stale:** the 150 ms limit made sense once, but renders got slower until the "slow" log
  recorded almost everything. The distribution (Samsung's median is 524 ms) showed this, and the limit
  is now back to flagging outliers.
