# A past day's blended actuals need the whole day's coverage

## Bug (fold-4, 2026-10-22)

Silurian's Tuesday 2026-10-20 actual bar read 61.9° / 60.4° — a 1.5° day between two 56.8° lows.
NWS for the same day and site: 68.3 / 51.0.

- 2026-10-21 21:56 the user pointed Silurian's and Google's actuals at Synoptic. Until then they
  borrowed NWS (the default inside coverage) and Oct 20 was stored at 68.3 / 51.0.
- Synoptic's first successful fetch (22:42) used the 24 h deep window, so for Oct 20 it held only
  22:43–23:55 — 73 readings, 58–64°.
- The recompute 3 s later reduced that sliver to a day and wrote it over the full-day row:
  `DAILY_HISTORY_OVERWRITE date=2026-10-20 src=SILURIAN high=68.33->61.95 low=51.03->60.44`
  (highAt 22:55, lowAt 22:43).

`ActualsAggregator.aggregate` emits a past day's extremes from whatever the provider's rows cover,
and `DailyActualsStore.persistExtremes` (desktop's equivalent too) overwrites without asking. Any
provider whose stored history begins part-way through a day does this: a provider switch, a feed
back from an outage, a new site (the 2026-08-22 GPS-excursion overwrite was the same shape).

## Fix

`:shared` `StoredBlendGuard.keep` decides whether a recompute keeps a stored row's high/low:
- `PULL_FROZEN` — a past day built from the NWS station pull (the rule both platforms already had,
  each in its own copy);
- `UNCOVERED` — a past day with a stored high/low whose **provider's** readings miss the pre-dawn
  (00–07) or afternoon (12–18) window (`StationDailyExtremes.poolCoversDay`, moved there from
  `NwsDailyExtremesFetch` so both use one check).

Only the temperatures and their times are kept; precip and condition update as before. A row with
nothing stored is written as before, and today is never kept. Android `DailyActualsStore.persistExtremes`
and desktop `recomputeDailyExtremes` both call it; a keep that changed something logs
`DAILY_HISTORY_KEPT_UNCOVERED kept=…/… partial=…/…`.

First attempt put the guard in `ActualsAggregator.aggregate` (no row for an uncovered past day).
17 Android tests failed: it also dropped such a day's precip, and the high/low of a day with nothing
stored — broader than the bug, which is a sliver *replacing* a whole day. Reverted.

## Tests

- `:shared` `StoredBlendGuardTest`: evening-only → kept; dawn + afternoon → rewritten; coverage
  judged on the provider (NWS's whole day does not cover Synoptic's sliver); nothing stored → written;
  today → written; pull-derived → frozen.
- Android `ObservationRepositoryDailyMergeTest`: the fold-4 case through the real recompute — stored
  68.3/51.0, Synoptic 22:43–23:55 only → still 68.3/51.0. Fails on the old code (62.0).

## The fold-4's rows

Repaired themselves: the user switched both sources back to NWS, and the recompute at 11:08:47
rebuilt Oct 20 from NWS's full day (`DAILY_HISTORY_OVERWRITE … high=61.95->68.33 low=60.44->51.03`).
Had the user stayed on Synoptic, the bad rows would have stood until they aged out — the guard stops
the overwrite, it does not undo one.
