# Dim started hours whose forecast was stale when the hour began

**User, 2026-11-04:** "Why desktop and fold-4 rain chance graph for NWS differ?" →
"I think we need a different rule about retrieving old hourly data for devices that were not able to
fetch." → approved: keep the freeze, dim stale hours with reduced opacity on all three hourly graphs.

## Evidence

| | last NWS fetch ≤ 1a | ≤ 3a | drawn 1a / 3a |
|---|---|---|---|
| Fold 4 | 00:13 (25%) | 01:13 (25%) | 25% / 25% |
| Desktop | 18:45 (7%) | 18:45 (4%) | 7% / 4% |

Desktop suspended 19:33 (`xfce4-power-manager`), resumed ~03:10; its 03:10 fetch said 3a = 25% but
`SettledForecastPick` freezes a started hour at the last fetch at or before it.

## Rejected: switch to an in-hour fetch when the pre-hour one is stale

Proposed first, then withdrawn: it is the "6 h stale exception" dropped on 2026-10-27
(plans/261027-pre-hour-freeze-retention-and-shared-pick.md). Open-Meteo's 12:27 in-hour fetch
redrew noon as a 94% hindcast; user: "Freeze wins always." `SettledForecastPick` and
`HistorySnapshotRetention` stay unchanged.

## Fix

1. `:shared` `StaleForecastSpans`:
   - `isStale(hourMs, fetchedAtMs, nowMs)`: hour started, real fetch (`fetchedAt > 0`), and fetched
     more than `STALE_AGE_MS` (3 h) before the hour.
   - `spans(xs, stale, left, right)`: each hour owns the x-range between the midpoints to its
     neighbours; consecutive equal flags merge. Renderers draw the forecast line once per span,
     clipped, at `DIM_ALPHA_FACTOR` (0.4 × the paint's alpha) where stale. No path splitting, so
     dash phase and smoothing are untouched.
2. Android: `PrecipHourData`, `CloudHourData`, and shared `HourData` gain `isStaleForecast`; the three
   hour builders set it from the stitched row's `fetchedAt`; the three renderers clip-draw the
   forecast line. Fill, labels, and actual lines are unchanged.
3. Desktop: `PrecipitationGraph`, `CloudCoverGraph`, `TemperatureGraph` compute the same flags from
   `HourlyForecast.fetchedAt` and clip-draw the same way.
4. Cloud graph: hours drawn from the deliberate day-ago prediction (`isFrozenForecast`) are never
   dimmed — that curve is stale by design.

## Tests

1. `:shared` `StaleForecastSpansTest`: 3 h boundary, unstarted hour, synthesized `fetchedAt = 0`,
   in-hour fetch; span midpoints, merging, all-fresh = one span.
2. Android: hour builders flag the desktop-like case (18:45 fetch for 03:00) and not a 01:13 fetch.
3. Desktop: the same flags for its points.
4. `./scripts/unit-tests.sh`.

## Verification

1. Fold: NWS rain-chance graph, no stale hours → unchanged.
2. Desktop: NWS rain-chance graph over 1a–3a (18:45 fetch) → line dimmed there, full from 4a on.
