# unifyToNearestSite must keep hours the nearest site cannot cover

**Date:** 2026-09-03 · **Report:** `summaries/260903-nws-cloud-gap-4a-10a-unify-drops-borrowed-fragment.md`

## The defect

`HourlyForecastStitcher.collapse` lets an hour with **no** same-site row borrow from a fragment
within `NEARBY_FALLBACK_TOLERANCE_DEG` (0.01°) rather than render blank.
`GraphDataLoader.unifyToNearestSite` → `LocationMatch.selectNearestSite` then keeps only rows
`sameSite` (0.002°) with the single nearest site, deleting exactly those borrowed rows.

Harmless while the winning site covers those hours itself. Total data loss when it does not:
2026-09-03, site `37.424,-122.088` had no NWS row for 00:00–10:00, the only coverage was the
01:26 fetch's fragment at `37.417,-122.089` (0.007° away), and the cloud graph rendered
`missing=7 ranges=4a–10a`.

## The fix

In `unifyToNearestSite`, after the existing collapse, **re-admit one row per
`(dateTime, source)` the winning site does not cover**, drawn from the nearest fragment within the
stitcher's own tolerance.

Three properties matter:

1. **Same predicate as the stitcher.** `HourlyForecastStitcher.withinNearbyFallback` becomes public
   and both layers call it. The bug is the two layers disagreeing; sharing one predicate is the fix,
   not a coincidence of equal constants.
2. **Keyed on `(dateTime, source)`, not `dateTime`.** These lists carry the display source plus
   `GENERIC_GAP`; keying on the hour alone would let a Generic row mark an hour "covered" and block
   the NWS borrow.
3. **Borrowed rows are re-stamped to the winning site's coordinates.** The output stays
   coordinate-homogeneous — the invariant every caller relies on today. Keeping the true coordinates
   would re-open the 2026-08-28 hazard where a downstream `firstOrNull()` adopts a foreign site as
   the render location and centres the observation blend hours in the past. A borrowed row is inside
   forecast-grid resolution (NWS ~2.5 km) of the centre, which is the stitcher's own stated
   justification for borrowing at all.

Selection among candidates is deterministic: nearest site, then freshest `fetchedAt`, then lat/lon.
Never row order — that nondeterminism is what produced the −13.7° today-column delta.

**This only ever adds rows for hours that would otherwise be blank.** It cannot resurrect the
`DailyNoonCloudCover` flap, which was a stale fragment beating a fresh row *for an hour both
covered*; those hours are covered and untouched.

## Scope

`unifyToNearestSite` is typed to `List<HourlyForecastEntity>`; all 7 callers pass raw proximity-box
DAO rows. `LocationMatch.selectNearestSite` itself is NOT changed — observations use it with
different semantics (site = fetch provenance).

## Tests

- `GraphDataLoaderUnifyToNearestSiteTest` — existing 4 cases must still pass unchanged (they all use
  a single `dateTime`, so nothing is borrowable).
  New: borrows an uncovered hour from a nearby fragment; re-stamps its coordinates; does **not**
  borrow from beyond 0.01°; does not let a `GENERIC_GAP` row block an `NWS` borrow; picks the
  freshest of two equidistant candidates; input row order does not change the result.
- New integration test (2+ classes: stitcher + loader) seeded from the measured device shape —
  winning site covering 11:00–23:00 only, a 0.007° fragment covering 01:00–12:00 — asserting the
  4a–10a hours survive to the render list.
- `HourlyCollapseChokepointTest` must still pass (the new `groupBy` consults `fetchedAt`).

## Diagnostics

`HOURLY_UNIFY_DROP` (added earlier today) gains a `borrowed=` field, so a report shows the fix
engaging and still names anything genuinely dropped as too far.

## Open question carried forward

The mechanism explains 7 of the 10 hours that were uncovered at the winning site; hours 01–03
rendered and were never reconciled. The fix is correct regardless — it restores every uncovered
hour — but `borrowed=` in the next report is what confirms the full story.
