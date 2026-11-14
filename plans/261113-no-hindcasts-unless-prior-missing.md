# No hindcasts unless the prior forecast is missing

**User, 2026-11-13:** "I hate how the APIs change their forecast after the forecast period. It makes
it impossible to judge accuracy. Had a rule to stop this before but it caused problems when there
was no prior data. So the rule is don't allow hindcasts unless prior data is missing."

Follow-up answers:
1. **Actuals:** late data is always fine. Observations and actuals are not affected.
2. **Forecasts:** "missing" includes a stale prior. The maximum prior age is a **tunable setting
   for experimentation**, default **6 h**, to be deleted after the experiment ends.
   The user's concern: "Sometimes data missing for 24 hour old data that isn't there. Hopefully this
   doesn't make it worse."
3. **Scope:** hourly **and** today's daily low/high.

## Evidence: why the earlier rules were removed

| Date | Rule removed | Case that caused it | Kind |
|---|---|---|---|
| 2026-11-04 | Hourly pre-hour freeze (`a2f157d8d` → `acbf4e40c`) | Desktop asleep 19:33–03:10. NWS 3am = 4% (18:45 fetch) while its own 03:10 fetch said 25% | **stale** prior (8 h 15 m) |
| 2026-11-06 | 06:00 same-day low freeze (`a920b1f4`) | Emulator Meteo today low 57° from a Nov 4 03:32 fetch (53 h old) while the hourly graph showed 46.6° | **stale** prior (~50 h) |
| (inside the old pick) | — | Jitter-fragment weeks-old long-range row counted as "pre-hour" and beat a fresh 66.6 with 81.3 (`HourlyStaleFragmentCollapseTest`) | **stale** prior (weeks) |

The old pick already handled a truly missing prior: it fell back to the earliest later fetch. Every
failure was a **stale** prior. The age limit addresses all three cases.

## New rule (one shared rule in `:shared`)

For a forecast period that has started (an hour that has begun, or today's low after 06:00 / high
after 16:00), with `T` = the max prior age:

1. **Fresh prior:** the newest fetch at or before the period start, if `periodStart − fetchedAt ≤ T`.
2. **Otherwise (no prior or a stale one): the earliest fetch after the start.** It is the closest
   to a prediction, and it does not change when more hindcasts arrive.
3. **Otherwise (only a stale prior, nothing after):** the stale prior. It is all there is, and it
   stays dimmed.

Periods that have not started keep taking the newest fetch, as they do today.

Null-field backfill order (NWS near-term hours carry no skyCover): the rule's pick first, then
pre-start rows newest-first, then post-start rows earliest-first.

### Desktop-asleep case under T = 6 h

At 3am the 18:45 prior is 8 h 15 m old, so it counts as missing. The 03:10 fetch (the earliest after
the start) stands in: **25%**. A 3:40 or 5:00 refetch no longer replaces it. With T = 12 h it would
show 4%, dimmed.

## Tunable setting (temporary)

- **Name:** "Hindcast guard: max forecast age" (Settings → near the bottom, an "Experimental" card).
  Slider 0–48 h in 1 h steps, default **6 h**. 0 means "always accept a hindcast", the current
  behavior, so the experiment can be switched off.
- **Platforms:** Android `activity_settings.xml` + `SettingsActivity` (SeekBar, the same pattern as
  `personal_station_discount`), and desktop `SettingsWindow.kt` + `DesktopConfig` (Slider). Kept in
  parity, with an entry in the bug report dump on both.
- **Plumbing (Claude proposal):** a small `:shared` `HindcastGuard` object holding `@Volatile var
  maxPriorAgeMs`. Each platform sets it at startup and whenever the setting changes. This avoids
  threading a parameter through the stitcher, retention, noon cloud, current temp, and daily-write
  call chains for a setting that will be deleted. Deleting it later means removing the object
  field and the two UI controls, then hard-coding the chosen value.
- Changing the value does **not** rewrite `daily_history` columns that are already frozen. It
  affects graphs and archives from that point on.

## Pruning must keep the rows the rule reads

`HistorySnapshotRetention` rule 2 changes from "newest" to: per (source, site, hour), keep **the
newest pre-start row and the earliest post-start row**, plus per coalesced field the newest non-null
pre-start row and the earliest non-null post-start row. These do not depend on `T`, so moving the
slider never needs rows that were already pruned. Rule 1 (newest, for the full window), rule 3
(prior-day band), and rule 4 (rain accuracy) are unchanged. The Android and desktop SQL prunes
change to match, with the existing SQL-equals-spec test.

**Not recoverable:** since the 11-04 install, the prune has deleted pre-hour rows for past hours
(except rows kept by rules 3/4). Hours from 11-04 until this install keep their newest rows. The
rule applies from the install onward.

## Daily low/high (today)

`SameDayExtremeCutoff` / `DailyForecastWrite.plan`, both writers:

- **Low, after 06:00:** keep the stored low if it came from a fetch made at or before 06:00 and
  at most `T` before it, or from the first fetch after 06:00. Otherwise this fetch writes it.
  When the sent value is not written, it goes to `hindcastLowTemp` (the column is still present).
- **High, after 16:00:** the same rule with 16:00. Today a stale or missing pre-16:00 high is never
  replaced. Under the new rule the first fetch after 16:00 fills it.
- This needs the fetch time of the stored **value**, not of the row: a row rewritten after the
  cutoff keeps the frozen value but gets a new `fetchedAt`. If the schema does not already record
  the low's and high's source fetch time, add `lowFetchedAt` / `highFetchedAt` columns (Room
  migration + desktop migration, recreating the table where needed). This will be checked first
  thing during implementation.
- `keptTodayLow` (a fetch that sends no low) stays as it is.
- The 11-06 case under T = 6 h: the 53 h-old low counts as missing, so the first fetch after 06:00
  writes 46.6°. **Fixed, not reintroduced.**

## Unchanged

- Actuals, observations, `hourly_actuals`, the IDW blend: late data always fills.
- `ElapsedForecastBackfill` still files every elapsed hour a fetch carries. Those rows are post-start,
  so they are read only when the prior is missing or stale.
- The past-day overlay settle, `daily_history` window freezes, `PriorDayForecast`, the day-ago cloud
  curve, and the `ForecastEvolutionCutoff` trim.
- `StaleForecastSpans` dimming (fetch > 3 h before the hour) stays. Under the new rule it marks
  case 3 and a fresh-but-older prior.

## Changes

1. `:shared` `HindcastGuard` (the setting holder) and `SettledForecastPick.pick` / `backfillOrder`
   take `hourMs` and `nowMs` again and implement rules 1–3. Callers: `HourlyForecastStitcher`,
   `HistorySnapshotRetention`, `DailyNoonCloudCover`, `CurrentTemperatureResolver`, plus the
   readers that went through the stitcher in 11-04 (`DailyHistoryRun`, `NwsStationActualsRun`,
   `CloudSeriesBuilder`) for comment updates.
2. `HistorySnapshotRetention` rule 2 + the Android and desktop SQL prune.
3. `SameDayExtremeCutoff` / `DailyForecastWrite`: low and high age rule; per-value fetch time
   (migration if needed).
4. Settings UI and prefs on both platforms; startup + on-change wiring into `HindcastGuard`;
   bug-report dumps; 19-locale strings.
5. Docs: `arch/hourly-forecast-update-cadence.md` (started hours), `arch/data-pruning.md` rule 2,
   `arch/daily-history-extremes.md` if it describes the same-day cutoffs. `notes/261106-freeze-audit.md`
   is historical and stays as it is.

## Tests (each fails on the current code)

1. `SettledForecastPickTest`: fresh prior beats a later hindcast; stale prior (8 h 15 m, T = 6 h) →
   earliest post-start (desktop 18:45 4% vs 03:10 25% → 25%, and a 05:00 fetch does not replace
   it); T = 12 h → 4%; no prior → earliest post-start; only a stale prior → stale prior; T = 0 →
   newest; jitter fragment weeks-old row loses to the fresh post-start row; null-field backfill
   order.
2. `HistorySnapshotRetentionTest` + SQL equivalence (Android, desktop): pre-start newest and
   post-start earliest survive the prune; the pick is unchanged before and after the prune for
   T ∈ {0, 6 h, 48 h}.
3. `DailyForecastWrite` tests: 11-06 replay (53 h-old low, T = 6 h → 46.6 written, a later fetch
   does not replace it); fresh 05:30 low held against a 09:00 hindcast; the same for the high at
   16:00; migration test if columns are added (real SQLite, emulator).
4. Update the stitcher, noon cloud, current temp, daily history, and NWS actuals tests that assert
   "newest wins" for started hours.
5. Settings parity: Android Robolectric + desktop Compose test that the slider persists and updates
   `HindcastGuard`.
6. `./scripts/unit-tests.sh`; `./scripts/emulator-tests.sh -c` for the migration/prune tests.

## Verification

1. Backups first: `python3 scripts/backup_databases.py`, plus a copy of the desktop `weather.db`.
2. Install on all connected devices; restart desktop.
3. Over the next hour boundary, a refresh after hh:00 must not change hh's drawn rain %/temp
   (logcat/stitcher log); a source with a fresh prior keeps it.
4. After 16:00 today, a refresh leaves today's high unchanged in the daily view on both platforms.
5. Slide to 0 h: past hours show the newest fetch again (escape hatch works); back to 6 h.
6. The first prune log line after install, then a spot check that a past hour keeps both its
   pre-hour and its first post-hour rows.
