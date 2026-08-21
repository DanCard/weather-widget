# Cloud actual curve discarded the current hour

**Date:** 2026-08-21
**Reported as:** "sky is ~10% cloud, widget says 100%" — NWS, both desktop and emulator.

## Root cause

Not stale data. The newer values were already in `observations` on both machines; the render threw
them away.

`CloudSeriesBuilder.build()` nulled `actualCover` for the hour containing `nowMs`, on the grounds
that the hour was "still in progress". That cost the actual curve its rightmost 1–2 hours, and left
the last drawn point pinned to a value up to ~2h old.

Measured 2026-08-21 11:16, recomputing the METAR blend straight from `weather.db`:

```
09:00  blend=100%   KNUQ@08:55M 100%  KSJC@08:53M 100%
10:00  blend=100%   KNUQ@09:50M 100%  KSJC@09:53M 100%
11:00  blend=65%    KNUQ@10:55M 75%  KPAO@10:47M 44%  KSJC@10:55 44%   <-- discarded
```

`MetarCloudBlender` computed 11:00 = 65% correctly and handed it over; the builder dropped it, so
the graph drew 10:00's 100% as its latest actual while the marine layer was visibly breaking up
(KNUQ `OVC015` → `BKN015`, KPAO `OVC010` → scattered within the hour).

The gate was wrong for the source it was hurting:

- **Non-NWS sources** get actuals from `HistoricalActualsBackfill`, which already applies
  `CloudActualSettling.hasSettled` **write-side**. The current hour has no row to draw regardless —
  the builder's gate was pure redundancy for them.
- **NWS** builds its actual as a live read-time METAR blend with no settling gate. A METAR is an
  *instantaneous* reading of the sky. Nothing about it is "in progress".

`CloudActualSettling`'s own class doc had already litigated and rejected exactly this lag — it sets
`SETTLE_MS = 0` and argues "roughly 40 minutes of a provisional value at the rightmost point"
beats "a permanently absent curve". The `currentHourStart` check was a second, independent gate
quietly reimposing the 1-hour lag that reasoning discarded.

## Fix

Graph the latest actual, no gate.

**`CloudSeriesBuilder.build()`** — `nowMs` now decides *only* which hours get the frozen day-ago
forecast (still correct: a day-ago prediction is a comparison for an hour that has happened). The
actual is drawn wherever `retroActual` has a value, full stop. Net effect is a branch removed, not
added — the early-return for non-past hours is gone.

**`CloudCoverViewHandler`** — `getCloudActuals(endTs = minOf(windowEnd, now))`, matching
`DesktopWeatherRepository`, which already bounded its read at `now`. This is what makes "no gate in
the builder" safe: cloud buckets round to the **nearest** hour, so a reading at 11:35 buckets to
12:00, and with the raw window end Android would have drawn a real observation to the *right* of the
NOW marker. A past-day window keeps its own end.

## Verification

- `CloudSeriesBuilderTest`: `the in-progress hour draws its actual` (new, pins the regression above),
  `current and future hours take the live forecast, never a frozen one` and `a future hour with no
  filed actual draws none` (replacing the two that asserted the old gate). Full class green.
- `:desktop:compileKotlin`, `:app:testDebugUnitTest --tests *CloudCoverViewHandler* --tests
  *CloudViewingRefresh*` green.
- **On-screen, both platforms**: actual curve now runs 100% → 65% and terminates at the NOW marker,
  matching the blend arithmetic above. Was: flat 100% terminating at 10:05a.

## Not fixed / out of scope

- The blend reads **65%**, not the ~10% the reporter saw out the window. That gap is not this bug:
  KNUQ (3.8 km) and KSQL still reported `BKN012`–`BKN015` at the time. The 100→65 gap was the defect;
  the 65→10 gap is the station network faithfully averaging a deck that had not finished clearing.
- Independent of `plans/260821-refresh-cloud-while-viewing.md`, which shortens the *forecast* fetch
  cadence (the dashed curve). This is the *actual* curve, where the data was never stale — only
  discarded.
