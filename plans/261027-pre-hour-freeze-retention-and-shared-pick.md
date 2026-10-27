# Pre-hour freeze: one shared pick, retention that keeps it, all past hours

**User, 2026-10-27:**
> "Yesterday there was a checkin : Freeze past hours at pre-hour forecast and align today noon cloud. Please code review it"
> "fix it"

> "approved, freeze all past hours"
> Google refill: "Refill wins when stale"; desktop DAO: "Yes, pass raw rows, make sure android and
> desktop work the same with shared code"
> Then, shown that a 6 h stale exception brought back Open-Meteo's 94% noon hindcast on desktop:
> "Freeze wins always. Why was there an exception?"

Follow-up to `2a3293ef` / [plans/261026-hourly-forecast-settle-at-hour-and-noon-cloud-parity.md](261026-hourly-forecast-settle-at-hour-and-noon-cloud-parity.md).

## 0. Found during implementation (approved 2026-10-27)

1. **The current hour stays live.** Without a "now", the hour in progress froze, and a weeks-old
   jitter fragment's pre-hour row beat the live fragment's fresh one (`HourlyStaleFragmentCollapseTest`,
   real device rows). The settled rule applies only to `hourMs < nowMs`. That gate does not depend on
   the calendar day, so midnight stays stable.
2. **Google history refill vs the freeze.** Freezing every past hour let yesterday's stale 71.7
   snapshot beat the refilled 69.5 (`DesktopGoogleHistoryRefillTest`). A 6 h stale exception was
   tried and dropped. The desktop DB showed routine >6 h gaps: Open-Meteo's last pre-noon fetch on
   10-26 was 22:18 the night before, so the exception redrew the 94% hindcast. **The freeze always
   wins.** A refill now draws only for hours with no pre-hour snapshot.
5. **Deferred: `AtSite` noon cloud (finding 7).** `DailyHistoryRun.snapshotDisplayedRainChance`
   hands it the live table only, so it cannot see the pre-noon snapshot. Fixing it means stitching
   live + history there, which also changes the frozen rain chance. That is a separate change.
   `AtSite` keeps its freshest-per-hour selection for now; under freeze-always, feeding it raw live
   fragments would let a weeks-old jitter fragment win.
3. **Desktop never froze.** `DesktopWeatherDao.getHourlyHistory` merged snapshots to the freshest
   per hour before the stitcher ran. A probe on a copy of the live desktop DB still drew the 12:27 94%
   hindcast at 10-26 noon. It now returns every snapshot, like Android's query.
   `StitchedHourlyReadContract` runs both platforms' real reads (Room `HourlyForecastLoader.withHistory`,
   desktop `getHourlyWithHistory`) against the same seeds and expectations.
4. **Live rows that are older than history are impossible.** Every fetch overwrites the live row as
   it writes its snapshot. Three fixtures (`NwsStationActualsRunTest`, `NwsStationActualsHostContract`,
   `DesktopWeatherRepositoryTest`) seeded live rows older than history or post-hour-only, and were
   made realistic.

## 1. Evidence

Desktop DB copy, 2026-10-27, `hourly_forecast_history` per elapsed hour (`pre` = rows with `fetchedAt <= dateTime`):

| source | hour | rows | pre | last pre-hour fetch | newest fetch |
|---|---|---|---|---|---|
| NWS | 10-25 12:00 | 16 | 15 | 10-25 11:44 | 10-25 12:01 |
| OPEN_METEO | 10-26 12:00 | 10 | 9 | 10-25 22:18 | 10-26 12:27 (the 94% hindcast) |
| SILURIAN | 10-26 18:00 | 10 | 9 | 10-26 08:23 | 10-26 18:27 |

No prune has run on desktop yet (8–16 rows per hour). Once one runs, `HistorySnapshotRetention` keeps:
newest by bucket/fetchedAt, newest non-null per field, the prior-day band row, and the newest row
captured on the previous calendar day. **NWS 10-25 11:44 matches none of them**, so it is deleted.
On the next paint the stitcher falls back to the post-hour row, and the freeze silently reverts.
OPEN_METEO 22:18 survives only because the previous-day rule happens to cover it.

## 2. Review findings confirmed in code (`2a3293ef`)

1. Retention does not keep the newest pre-hour row (above).
2. Stitcher fallback when no pre-hour row exists is `liveRows.maxBy(fetchedAt)`, the freshest
   hindcast, and then `historyRows.firstOrNull()`, which is query order. The KDoc says
   "earliest fetch". `DailyNoonCloudCover` uses `minBy`.
3. `preHourLive ?: preHourHistory` lets an older live row beat a newer pre-hour snapshot.
4. Null-field backfill uses `firstNotNullOfOrNull` in list order, so a field can come from a
   long-range snapshot.
5. The freeze covers only today (`time in todayStartMs until nowMs`), so at midnight yesterday's
   hours switch back to freshest-wins. `resolveMeasuredNoonCloudCoverPercent`, however, applies
   pre-noon-wins to **every** date, so the two rules already disagree for past days.
6. `todayStartMs` is hard-coded to `ZoneId.systemDefault()`.
7. `resolveMeasuredNoonCloudCoverPercentAtSite` first reduces rows with
   `HourlyForecastSelector.selectForecastsByTime` (freshest per hour). On past days it returns the
   hindcast, while the non-site variant returns the pre-noon value. `DailyHistoryMaintenance`
   freezes noon cloud via `AtSite`.
8. `DailyNoonCloudCover.forColumn` can call `hourlyNoon()` twice, and gates the hourly value on
   `rowIsDisplaySource`.
9. Stale docs: the stitcher call-site comment in `GraphDataLoader`, the `HistorySnapshotRetention`
   KDoc, `arch/data-pruning.md`, the `DailyNoonCloudCover` object KDoc ("freshest wins"), the
   `CloudPoint.forecastCover` KDoc, and the `PriorDayCloudForecast` comment.

Not a defect: `CloudSeriesBuilder` reporting `isFrozen=false` for today's elapsed hours.
`frozenCoverage()` has no production caller, and dropping the day-ago swap for today was the
approved rule #3. Only the KDoc changes.

## 3. Fix

### 3a. One shared pick (`:shared` `data/model/SettledForecastPick.kt`)
```kotlin
object SettledForecastPick {
    /** Hour already started: newest fetch at/before it, else the earliest fetch. Future: newest. */
    fun pick(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): HourlyForecast?
    /** Candidates ordered for null-field backfill: the same window as [pick], newest first. */
    fun backfillOrder(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): List<HourlyForecast>
}
```
- Live and history rows are merged into one candidate list, and `fetchedAt` decides (finding 3).
- Fallback is `minBy(fetchedAt)`, which is deterministic and matches the KDoc (finding 2).
- Backfill reads newest-first within the pre-hour window (finding 4).
- `HourlyForecastStitcher.stitch` applies it to **every elapsed hour** (`hourMs < nowMs`), not only
  today's. `todayStartMs` and the zone go away (findings 5 and 6).
- `DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent` calls `pick(rows, noonMs, nowMs = Long.MAX_VALUE)`
  instead of its inline copy.
- `resolveMeasuredNoonCloudCoverPercentAtSite` filters to same-site candidates (new
  `HourlyForecastSelector.sameSiteCandidates`, extracted from `selectForecastsByTime`) without
  reducing to freshest, so both variants apply the one pick (finding 7).

### 3b. Retention keeps what the pick reads (`HistorySnapshotRetention`)
- Active tier, new rule 5: per group, if any row has `fetchedAt <= dateTime`, keep the newest of
  those plus the newest non-null pre-hour row per coalesced field. Otherwise keep the earliest
  `fetchedAt`. Both windows are stable once the hour passes, so pruning stays idempotent.
- Settled tier (>30 days, one row per hour): the winner becomes `SettledForecastPick` (newest
  pre-hour, else earliest) instead of the latest fetch, so archived hours match the graph.
- Android `HistorySnapshotPruner` and desktop `DesktopWeatherDao` already call `keptKeys`, so
  there is no SQL to mirror.

### 3c. Cleanups
- `forColumn`: `if (isToday) hourlyNoon() ?: rowPercent.takeIf{…} else …`, a single scan (finding 8).
- Update every doc listed in finding 9, including `arch/data-pruning.md`.

## 4. Behaviour change to accept
Past days (not only today) now draw temperature, precip and cloud at the pre-hour forecast. Shared
consumers that stitch past days change with this:
- `DailyHistoryMaintenance` chance/frozen-display backfills (one-shot, already run on existing installs)
- `NwsStationActualsRun.nwsHourlyForDay`, which extrapolates actuals across observation gaps.
  It already uses the pre-hour rule for today since `2a3293ef`.
- Past-day cloud graph forecast curve: unchanged where a day-ago `priorForecast` exists.

## 5. Tests
- `SettledForecastPickTest` (new, pure JVM): newest pre-hour wins; live vs history decided by
  fetchedAt; no pre-hour → earliest; future → newest; backfill order.
- `HourlyForecastStitcherTest`: yesterday 23:00 gives the same value at 23:59 and 00:01 (midnight
  continuity); fallback is deterministic under shuffled input; backfill skips the long-range snapshot.
- `HistorySnapshotRetentionTest`: the NWS 11:44/12:01 case keeps 11:44; no-pre-hour keeps the
  earliest; settled tier winner; `stitch(all) == stitch(kept)` over a randomized fixture (the
  invariant the spec claims). Each must fail on `2a3293ef`.
- `DailyNoonCloudCoverTest`: `AtSite` and non-site agree on a past day with an 11:30 forecast and a
  14:00 hindcast; `forColumn` table.
- Existing `HistorySnapshotPrunerTest` / `DesktopHistoryPruneTest` fixtures updated as needed.

## 6. Verification
1. `./scripts/unit-tests.sh`
2. `scripts/daily_parity_diff.py` reports 0 differences
3. Desktop: `:desktop:createDistributable`, restart, then check the 10-26 12:00 Open-Meteo cloud
   point (expect the 22:18 forecast, not 94%) and check that yesterday's temperature line does not
   jump at midnight.
4. Android emulator: `installDebug`, widget screenshot + `HOURLY_LOAD` logcat.
