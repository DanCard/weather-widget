# Every fetch saves an hourly snapshot

User, 2026-11-01: "if it is past 3 am we shouldn't be changing the forecast for before 3 am. Same is
true for every other api." Approved: save a snapshot on every fetch now; merge the two hourly tables
later as separate work.

## Evidence
Desktop, Tomorrow.io, night of 2026-10-31 (`FREEZE_RAIN_CHANCE`, `HOURLY_SNAPSHOT`, `hourly_forecast_history`):

| Fetch | Forecast for 03:00 | Snapshot | Night chance |
|---|---|---|---|
| 17:46 | 30 (02:00 = 35) | saved | 35 |
| 21:44 | not stored | skip, `recent` | 55 |
| 02:31 | 97 | skip, `recent` | 97 |
| 03:17 | 20 | saved | 35 |

Samsung did not fetch during the 03:00 hour, so it kept 97. No later `FREEZE_RAIN_CHANCE` line changed
either value and the night window closed at 08:00, so the two now disagree permanently.

## Root cause
`HourlyForecastStitcher` freezes an hour at the last forecast fetched at or before it, which needs that
forecast to still exist. The 02:31 row existed only in `hourly_forecasts`, and `HourlyForecastWrite.plan`
replaces hours `>= now - 1 h`, so the 03:17 fetch erased it. The stitcher fell back to the 17:46 snapshot.
Not a parse bug, and not specific to Tomorrow.io: the path is shared by every source. Fully elapsed hours
are already protected; the hole is the hour in progress of a source whose pre-hour fetch was skipped.

## Change
Remove the `recent` and `not_viewed` skips, so `hourly_forecast_history` holds every fetch.

1. `:shared` `HourlySnapshotRun`: every fetch saves under its fetch time; a call within `SAME_FETCH_MS`
   of the last snapshot reuses that label. Remove `isDisplayed`, `viewedRecently`, `batteryHigh`,
   `OTHER_GAP_*`. A failed read still saves. Log `reason=fetch` at VERBOSE (steady state).
2. Android `RoomHourlySnapshotHost` and desktop `DesktopHourlySnapshotHost`: reduced to the database read
   and log. Remove the plumbing that only fed the gates.
3. `HourlyForecastWrite` is unchanged: the live row keeps updating during the hour, so readers of the
   current hour see no change. This replaces the started-hour write guard first proposed.
4. `ForecastPersistRun.Host.snapshotLabel` stays nullable to keep this change small; the merge removes it.
5. Update `arch/data-pruning.md`, and the other living docs and comments that describe the skip rule.

## Trade-off
This reverses part of the user's 2026-10-24 rule
(`performance/261024-hourly-snapshot-windows-by-view-and-battery.md`), which reduced snapshot volume.
`app_logs` shows 13 skips a day on desktop and 20 on Samsung; at up to 72 rows each that is about 950 and
1,400 more rows a day, against 48,613 and 27,533 stored. `HistorySnapshotRetention` prunes them at most
every 24 h; on Android pruning needs housekeeping conditions, so rows can build up between prunes. Upside
of dropping `not_viewed`: a source switched back to has its history. The elapsed-hour backfill now also
runs on these fetches, and only fills hours with no snapshot.

Past values are not repaired: a provider does not re-send what it predicted, so 2026-10-31 stays 35 on
desktop.

## Tests
1. `:shared`, integration: replay the table through `ForecastPersistRun`, `HourlyForecastStitcher` and
   `DailyRainLabels`; the night stays 97. A control skipping the 02:31 snapshot must give 35, so the test
   proves it separates the old behaviour from the new.
2. Update `HourlySnapshotRunTest`, `DesktopHourlySnapshotRuleTest` and the Host contract tests.
3. `./scripts/unit-tests.sh`.
4. Runtime: run the desktop app, confirm a non-displayed source logs a save and gets a new label, then
   install on the phones and confirm the same.

## Not in this change: one hourly table
Once history holds every fetch, `hourly_forecasts` is a rebuildable cache and can go, in its own plan:
- Replace the 72 h snapshot limit (`ROUTINE_HOURS`), so one table has one horizon. Rows per fetch go from
  72 to about 190 for the longest-reaching sources.
- Measure "latest row per hour" on widget paint paths before committing to it.
- Redefine `fetchedAt` as "last confirmed" and the null-field merge.
- Migrate Room and desktop SQLite. `hourly_forecasts` appears in 49 production and 98 test files.
