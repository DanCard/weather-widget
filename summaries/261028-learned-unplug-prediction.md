# Learned unplug prediction replaces alarm-driven pre-wake (2026-10-28)

Plan: [plans/261028-learned-unplug-prediction.md](../plans/261028-learned-unplug-prediction.md)

## Why

- The Fold-4 fetched every source at 21:33 with the screen off and the phone on its charger. The
  `reason=pre_wake` sync ran 45 min before a 22:00 alarm. The phone then stayed plugged in for another 17 h.
- A fetch timed before an alarm only helps if the phone actually leaves the charger. If it stays
  plugged in, turning the screen on can fetch then at no battery cost.
- The old learner (`UnplugRoutine`) only looked for one overnight unplug time per weekday or weekend,
  so it found nothing on either phone. It also discarded real habits, such as the Fold being unplugged
  Tue 10-20 at 11:50 and Tue 10-27 at 11:40 (basketball).

## What changed

1. **Learning unplug habits** (shared code in `:shared`, used by both Android and desktop):
   - `ChargeHistory` records every charge of 15 min or more, not just overnight ones, plus every alarm
     that fires. History covers 8 weeks.
   - `UnplugPredictor` finds habits:
     - **Weekly** (same weekday): active after 2 matching weeks.
     - **Daily** (weekdays or weekend days): active after 4 matching days in 21 days.
     - Recent weeks count more than old ones (half-life 3 weeks).
   - A day counts against a habit only if the device stayed on the charger through that time. Days off
     the charger count as neither.
2. **Alarms are a hint that has to earn trust.** Each alarm time starts at 50% confidence, goes up when
   you unplug within 90 min after it, and down when you stay on the charger. It's used only while above
   50%, so a bare alarm with no history does nothing.
3. **One shared runner, `PreUnplugFetchRunner`, owns the rule on both platforms.** It records sessions,
   predicts the next unplug, schedules a check before it, and logs whether the prediction was right.
   Each platform supplies only a small host:
   - **Android** `PreUnplugFetchScheduler`: prefs storage, a WorkManager check, and the clock app's alarm.
   - **Desktop** `DesktopPreUnplugHost`: a file store, and a wake-up of the forecast loop. No alarms.
4. **Turning the screen on while charging fetches the forecast** if it's more than 4 h old
   (`ForecastHorizonCadence.screenOnCatchUp`, Android `ScreenOnReceiver`). Desktop already re-checked on
   every screen wake.
5. **Desktop** records AC sessions through UPower power events plus a check every 5 min, and its
   forecast loop plans around the predicted unplug. A machine with no battery never unplugs, so it never
   predicts.
6. **Logs:**
   - `UNPLUG_RECORDED`: each charge session.
   - `UNPLUG_PATTERNS`: the learned habits, once a day.
   - `PRE_UNPLUG_FETCH`: each check scheduled or cancelled.
   - `PRE_UNPLUG_OUTCOME`: hit (with minutes early or late), miss, or unknown.
   - The sync reason is now `pre_unplug`.

## Changes from the plan

- The fetch stays 45 min before your earliest unplug in a habit, rather than 15 min, because the Fold's
  scheduled check ran 13 min late on 2026-10-28.
- Alarms aren't split into weekday and weekend, so a daily alarm builds trust twice as fast.
- A bug was fixed where a prediction was cleared before its hit or miss could be logged. The scheduled
  check and the prediction being judged are now stored separately (`trackedWakeAtMs`).
- The repo's guard test requires a shared contract test for every new `Host` interface, so
  `PreUnplugFetchHostContract` was added with Android (`PrefsPreUnplugHostContractTest`) and desktop
  (`DesktopPreUnplugHostContractTest`) versions.
- Android's WorkManager job keeps its old name (`weather_widget_pre_wake`) so a check that was already
  scheduled can still be found and cancelled. On first load, the old prefs (overnight unplugs and the
  alarm-era check) are converted to the new format.

## Flaky test and the build-cache race

- **Cause:** `scripts/unit-tests.sh` runs two Gradle processes in parallel. It already built `:shared:jar`
  once beforehand to stop them compiling it at the same time, but both processes also build `:shared`'s
  test fixtures. After a fixture changed, they compiled it concurrently and one failed while storing its
  build-cache entry: `:shared:compileTestFixturesKotlin FAILED — Failed to store cache entry … Could not
  get file mode for … shrunk-classpath-snapshot.bin`.
- **Fix:** the script now also builds `:shared:testFixturesJar` once, before splitting into two processes.
- Every new and changed test class was rerun 3–5 times without the cache, with no failures.

## Verification

- Full JVM suite (`./scripts/unit-tests.sh`): 6289 tests passed.
- `installDebug` installed on all 3 connected devices; desktop was rebuilt and restarted with
  `scripts/buildStart-desktop.sh`.
- **Fold-4:**
  - The old 08:00 alarm check was cancelled (`PRE_UNPLUG_FETCH action=cancelled reason=no_prediction`).
  - The daily log shows `UNPLUG_PATTERNS tue=11:40-11:50 p=1.00(2/0) on; alarm=08:00 p=0.50(0/0) off;
    sessions=7`.
  - The Tuesday check should be scheduled on Monday night and run around 10:55 on 11-03.
  - Tomorrow's 08:00 alarm is saved, so it will be judged against whether you unplug after it.
- **Desktop:** `UNPLUG_PATTERNS patterns=none; alarm=none; sessions=0`. This machine has no battery, so
  it never unplugs and never predicts, as intended.
- **Pixel:** the new build is installed, but it hadn't run a full sync before its logs were pulled, so its
  pattern line hasn't been seen yet.
- The debug `REFRESH` broadcast used on the Fold turned out to be a forced refresh, so it fetched all five
  sources once.

## Still to check

- 2026-11-03 (Tue): a `PRE_UNPLUG_FETCH action=armed … source=weekly(tue)` line the evening before, a
  `pre_unplug` sync around 10:55, and a `PRE_UNPLUG_OUTCOME` hit.
- The Pixel's first `UNPLUG_PATTERNS` line.
- No `pre_unplug` sync before the Fold's 22:00 alarm.
