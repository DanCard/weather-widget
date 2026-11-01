# Hourly rows pruning: how it works and what strategy is optimal

Written 2026-11-01, after phase 3 of `plans/261101-one-hourly-forecast-table.md`. This is a dated note, not
current documentation. `arch/data-pruning.md` still describes the two-table design until phase 5.

The current design is the right one, but **the desktop prune has effectively stopped running**, and that
matters more than any change to the rules. No code was changed, and all measurements ran on read-only copies.

## How it works today

Since phase 3 there is one append-only `hourly_forecasts` table, and every fetch adds rows under its own fetch
time. Two separate deletes keep it in check.

**1. Age limit:** runs after every fetch and deletes rows whose hour or fetch time is over 18 months old
(`HOURLY_HISTORY_SETTLED_DAYS`).

**2. Snapshot prune:** `HistorySnapshotRetention` decides which rows to keep, grouped by source, hour and site.

| Age of the hour | Rows kept |
|---|---|
| Future, or under 30 days old | The union of the rows each reader needs (below) |
| 30 days to 18 months | One row: the settled pick, with sites within 0.1° merged |

The union for hours in the active window (future or under 30 days old) is:

1. The newest row by label and by `fetchedAt`
2. The newest non-null value for each cloud and precipitation field
3. The newest row at least 24 h before the hour that has mid or high cloud, for prior-day bands
4. The newest row captured on the previous local day, for rain accuracy
5. The settled pick: the last fetch at or before the hour, plus per-field fill-ins

When it runs:

- **Android:** at most once a day. `HistoryPruneWorker` is queued for when the phone is charging and idle, then
  runs `VACUUM` if at least 8 MB is free.
- **Desktop:** at most once a day, inline at the end of a refresh, and only on AC with the screen off.

## What the data shows

| Latest copy | Rows | Simulated prune would delete |
|---|---|---|
| Desktop | 52,360 | **32,025 (61.2%)** |
| Pixel | 69,846 | 2,416 (3.5%) |

The Samsung copy was still on v83, so it was left out. These numbers come from a Python port of `keptKeys`, not
the Kotlin itself, so treat them as close rather than exact.

**Desktop stall:** hours under 20 days old hold about 10 rows per source and hour, and older hours hold 1–3.
There are no `HISTORY_PRUNE` rows in the live log or in the Oct 21 and Oct 27 backups, though that log only keeps
72 hours. Android pruned normally on Oct 29, Oct 31 and Nov 1.

**Likely cause:** inferred from the code and row counts, not from a direct trace. The prune runs at the end of a
refresh and needs the screen off, but the away rule stops fetches after an hour away. So only a refresh in the
first hour of screen-off can prune. That gate landed Oct 9 (`6fce4195`), which roughly matches where the rows
pile up.

**Why it matters more now:** phase 2 widened each fetch's snapshot from 72 h to as many as 192 h. Render reads
also pull every snapshot in their window, so a stall can slow reads as well as use disk.

## Opinion: the optimal strategy

Keep pruning based on what readers need, and fix when it runs.

1. **Fix the desktop trigger.** Run the prune from its own daemon timer rather than from refresh, since it needs
   no network and can run while away. Add an override once it is about 3 days overdue, and log when it is skipped
   so a stall is visible.
2. **Keep 30 days and 18 months.** For a single site with 7 data streams, the estimate is about 98,000 rows, or
   about 21 MB:
   - The archive is about 87,000 of those rows.
   - The active window is about 10,000.
   - Future hours are about 1,300.

   Settling at 7 days instead of 30 would save only about 1 MB, and it would break the 30-day rain accuracy view.
   Shorten the archive only if storage becomes a real problem. Which screens read hours older than 30 days was
   not traced.
3. **Drop rule 2.** It protects no rows of its own on either database, so it looks like a leftover from before the
   freeze. First confirm that no reader that merges fields needs it.
4. **Leave the rest alone:**
   - **Single-row archive:** it lost no cloud or rain values across 2,678 recent desktop hours, so it's safe.
   - **Thinning by time:** keeping, say, four rows a day would drop rows that readers need. Rule 4 alone is the
     only protection for about 2,100 rows per database.
   - **Android's "incremental" scan:** it still covers 67 days, because recent `OPEN_METEO_PRIOR24` rows point at
     old hours. That costs about 20 s on a charger, which isn't worth fixing.

**Before the fix ships:** no prune has run on the merged table on any device, because the Pixel's last one (03:59)
came before its merge. Desktop's first run will delete about 32,000 rows, so take a fresh backup first.

## Insights

- **Each row has a named reader.** That's why the rule can be safe for future hours: each window only stops
  moving, so a row that isn't the newest now can never become it later.
- **Size comes from depth, not snapshots.** The rules hold about 2 rows per hour, but 18 months of single rows
  outweighs that about 9 to 1, so tuning the rules barely changes disk use.
- **Two separately sensible gates combined into a stall.** "Prune only when away" and "don't fetch when away" are
  each reasonable, but together they almost never let the prune run. Housekeeping shouldn't depend on a loop that
  has its own reasons to stop.
