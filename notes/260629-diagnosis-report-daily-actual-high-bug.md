# Diagnosis Report: Daily Forecast View Actual High Temp Bug

We investigated the issue where the actual high temperature for days starting from about a week ago is wrong or missing (degenerate high and low temps). 

Here is the step-by-step diagnosis of the root cause, backed by evidence from the SQLite databases on the active devices.

---

## 1. Executive Summary of the Root Cause

1. **Split Retention Policy**: 
   * Raw `observations` (from weather stations) are pruned hourly and kept for only **6 days** to reduce database size.
   * `daily_extremes` (the daily summaries displayed in the forecast view) are kept for **30 days**.

2. **The Recomputation Loop**:
   * During every background widget update (in `WeatherWidgetWorker`) and whenever the History Activity is opened (`ForecastHistoryActivity`), the system attempts to recompute `daily_extremes` from stored raw observations for the last **30 days**.

3. **Pruned Gaps & Midnight Overwrites**:
   * For days older than 7 days, there are zero observations remaining in the database, so the recompute is safely skipped.
   * However, for the day that is **exactly 6 days ago**, the observation pruning (which deletes observations older than exactly 144 hours from the current millisecond timestamp) has **partially deleted** the day's observations. The morning and afternoon observations are deleted, but the late-evening/night observations (closer to midnight) still remain because they are technically under 144 hours old.
   * Since the database still returns a few observations (e.g., between 10 PM and midnight), the recompute check `dayObs.isEmpty()` is `false`.
   * The app recalculates the daily high/low temperatures using only this biased, late-night slice of data. This results in **degenerate values** (where `highTemp` and `lowTemp` are extremely close or identical, representing night-time temperatures around 58°F–60°F instead of the day's actual high of 80°F).
   * It then **overwrites** the previously correct daily extremes with these corrupt, degenerate values.
   * Once that day becomes 7 days old, all observations are gone, so it is never touched again, locking the wrong values in `daily_extremes`.

---

## 2. Collected Evidence from the Device Database

### A. Observations Count by Date
Querying the `observations` table on both the Pixel 7 Pro and Samsung SM-F936U1 backups shows that raw observations **only exist from June 24 onwards** (exactly 6 days ago relative to today, June 29):

```sql
SELECT date(timestamp/1000, 'unixepoch') AS date_utc, count(*) 
FROM observations 
GROUP BY date_utc 
ORDER BY date_utc DESC;
```

**Output:**
```
2026-06-30 | 239   (partial tomorrow)
2026-06-29 | 1035  (today)
2026-06-28 | 1016
2026-06-27 | 1043
2026-06-26 | 858
2026-06-25 | 1024
2026-06-24 | 784   (6 days ago)
-- ZERO observations exist before June 24!
```

### B. Degenerate Extremes in the DB
Querying `daily_extremes` for dates older than June 24 shows they have been overwritten with degenerate values (high == low or extremely close) and shows the exact time they were corrupted (written exactly 6 days after the date):

```sql
SELECT date(date/1000, 'unixepoch') AS formatted_date, source, highTemp, lowTemp, 
       datetime(updatedAt/1000, 'unixepoch', 'localtime') AS updated_local 
FROM daily_extremes 
WHERE date >= 1781481600000 
ORDER BY date DESC, source;
```

**Selected Rows Output:**
* **June 24 (within 6 days, observations complete):**
  * NWS: High `72.33` / Low `58.87` (Correct)
* **June 23 (exactly 6 days ago, observations partially pruned):**
  * NWS: High `60.80` / Low `58.90` (Degenerate) — **Updated on 2026-06-29 22:00:07** (6 days later)
  * SILURIAN: High `59.67` / Low `59.67` (Degenerate) — **Updated on 2026-06-29 22:00:07**
* **June 22 (7 days ago, observations fully pruned):**
  * NWS: High `60.32` / Low `59.00` (Degenerate) — **Updated on 2026-06-28 23:23:46** (6 days later)

### C. App Log Audit Traces
The `app_logs` show the exact recomputation process taking place. On **June 29 at 22:17:06**, when processing the past extremes for **June 23**, it found only a few remaining observations (e.g., `n=23` for KSJC, `n=6` for OPEN_METEO, `n=1` for SILURIAN) and declared them stable or overwrote them:

```
2026-06-29 22:17:06|DAILY_EXTREME_BLEND|date=2026-06-23 src=SILURIAN computed_hi=59.675533 computed_lo=59.675533 stations=[SILURIAN_MAIN(d=0.00km,hi=59.675533,lo=59.675533,n=1)]
2026-06-29 22:17:06|DAILY_EXTREME_BLEND|date=2026-06-23 src=NWS computed_hi=60.8 computed_lo=58.9022 stations=[KSJC(d=15.93km,hi=60.8,lo=57.199997,n=23),KNUQ(d=3.82km,hi=60.8,lo=59.0,n=6)...]
2026-06-29 22:17:07|DAILY_EXTREME_STABLE|date=2026-06-23 src=SILURIAN high=59.675533 low=59.675533
```

---

## 3. Recommended Fix

To resolve this issue permanently, we should restrict the `recomputeDailyExtremesFromStoredObservations` method so that it **only processes dates within the safe observation window (the last 5 days)**. 

If a date range includes days older than 5 days (e.g., the 30-day lookback requested by the worker or History Activity), the method should skip those dates. This will:
1. Prevent the deletion-fringe (day 6) from ever being recomputed with incomplete data.
2. Avoid unnecessary database queries for days 7 to 30.
3. Allow the correct daily extremes (originally computed when the observations were full) to remain intact in `daily_extremes` for their full 30-day lifecycle.

### Code Locations to Modify (upon your approval)
1. **`ObservationRepository.kt`**: Inside `recomputeDailyExtremesFromStoredObservations`, skip any date that is before `LocalDate.now().minusDays(5)`.
2. **Add a regression test** to verify that recomputation is skipped for older dates.
