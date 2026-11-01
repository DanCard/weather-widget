# Observed night rain boundaries

Date: 2026-11-01

## User prompts

1. fix night time rain boundaries
2. write above to summaries/

## Problem

Observed `precipNightMm` was filed by calendar date: midnight–8am plus 8pm–midnight of one date. The
night label is drawn in the gap between a date and the next, and its forecast chance covers 8pm to
the next 8am, so the two described different nights.

Evidence on the Fold, 2026-11-01: 1.0 mm at KNUQ at 01:55 sat on Nov 1's row, while Oct 31's night
row stayed 0.

Commit `3a50568d` (2026-05-28) chose the calendar split. Night was already 8pm–8am then, but the code
saw one calendar day, so midnight–8am rain counted toward the total and neither bucket. The commit
closed that gap by moving those hours onto the same date and adding a day + night = total rule. The
recompute now reads 24 h of context either side (`DAILY_BLEND_CONTEXT_MS`), so the original definition
works.

## Change

1. `ObservedPrecip.daily`: `night` is the night starting on the date, 8pm until 8am the next morning.
   `total` stays calendar-day and `day` stays 8am–8pm.
2. `DailyBlendRecomputeRun.day`: the skip signature runs through the next 8am. Without this, 2am rain
   never recomputes the previous day.
3. `ActualsAggregator.resolveDailyPrecip`: measured rain counts when either `total` or `night` is
   known, so a night measured only after midnight is kept. The forecast fallback (today only) uses the
   same night window.
4. Comments on `precipNightMm` in `DailyHistory.kt` and `DailyHistoryEntity.kt` state the new meaning.

No schema change. The changed signature makes each day in reach recompute once, which repairs recent
rows. `DailyBlendWrite` assigns `precipNightMm` directly, so a null clears an old value.

## Tests

1. `ObservedPrecipTest`: evening plus next morning, the 8am and 8pm edges, an unknown night, and a
   night known only after midnight.
2. `DailyBlendRecomputeRunTest`: an observation before the next 8am reopens the day; one after does not.
3. Rewritten for the new meaning: two `ObservationResolverTest` cases and `DesktopAccuracyTest`. No
   production code read day + night = total.

Gate: 6,507 JVM tests and 109 of 109 on both emulators.

## Verified on the Fold

Oct 31's NWS and Open-Meteo rows went from `nightMm=0` to `1.51`, and the NWS label reads `.059in`.
The sync at 03:21 logged `DAILY_RECOMPUTE_SKIP` for Oct 30 and 31, so settled days skip again.

## Behavior to expect

1. Below the 95 % amount threshold, a night under way shows the rain measured so far instead of a %,
   and it rises through the night.
2. Day + night no longer equals `precipAmountMm`.

## Open items

1. Today's row (Nov 1) still held the old `nightMm=1.51` at the last check. `recomputeDailyExtremesForDay`
   should clear it on the next NWS observation, or the range recompute once it is yesterday. Not seen
   yet. Today's night label uses the forecast, so the value is not displayed meanwhile.
2. Rows older than the 10-day observation retention cannot be rebuilt and keep the calendar split.
3. A source with no measured series, such as OWM, keeps an empty night row and shows a forecast amount
   or chance.
4. Uncommitted when this was written. The Fold and desktop have the build; the Pixel does not.
5. A `FULL_SYNC` was run once at 03:21 while checking today's row. It forces every source and counts
   against API quotas. The plain refresh had already done the job.
