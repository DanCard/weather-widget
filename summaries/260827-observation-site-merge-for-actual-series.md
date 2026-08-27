# Merge nearby device-site fragments for observation reads

**Date:** 2026-08-27
**Plan:** [plans/260827-observation-site-merge-for-actual-series.md](../plans/260827-observation-site-merge-for-actual-series.md)

## What happened

The Samsung widget's actual cloud line broke into two segments with a 12:00–13:15 hole. The user
supplied the decisive fact mid-investigation: *"I went to play basketball approximately 800 meters
away during that time."*

Computed from the stored coordinates, the two device-site fragments are **783 m** apart. Every
cloud-carrying observation in that window was filed under the court fragment; back home,
`selectNearestObservationSite` picked the home fragment and deleted all 65 of the other's rows. NWS
had reported normally throughout — KSJC gave `44/75` every five minutes across the whole gap.

## The reframe that made it a small fix

**A device site is not where the weather is.** `locationLat`/`locationLon` on an observation row
records where the phone was standing when it fetched. The weather is at the stations — KSJC 16 km
out, KNUQ 4 km out — and both fragments blend *the same stations reporting the same sky*.

Single-site collapse is right for forecasts (model output for a point) and wrong for observations.
The read path could not tell them apart.

## Following the user's pointers

The user asked whether the temperature actual line already handled this, and named the fix they were
thinking of: `674ab7b0`, "Pick the freshest noon row for the daily cloud bar, not the first."

- **Temperature does not handle it — it has the identical bug.** Temperature and cloud are columns
  on one `observations` row and both read through `getObservationsInRange`. So the temperature actual
  line had the same 75-minute hole, and one fix covers both. That is why the fix lives in the read
  path rather than the cloud blender.
- **`674ab7b0` names the canonical rule** and `HourlyForecastSelector` spells it out: keep the source,
  drop rows that are not the same physical site, take the freshest remaining. Its insight was that
  step 2 *cannot* separate same-site fragments and deliberately does not try. This case is the mirror
  image — 783 m is outside `SAME_SITE_TOLERANCE_DEG`, so step 2 fires and deletes the right fragment.
- **`LocationMatch.selectNearestSiteWith`** was written for this exact scenario (its docstring says
  "a ~0.8 km GPS excursion created a second fragment") and still cannot close it: it only skips sites
  where *every* row is unusable, and the home fragment holds plenty.

The user then asked whether the plan would work for temperature, and that question found the one
thing that would have made the fix a new bug — see below. They also corrected the desktop scoping
twice: first that a stationary machine cannot hit this, then that a **laptop moves**.

## What changed

**`ObservationSiteMerge`** (shared, pure): keep rows from any device site within
`MERGE_TOLERANCE_DEG = 0.01` (~1.1 km) of the query centre; drop sites beyond it; dedupe on
`(station, timestamp, api)` preferring the nearest site's copy, `fetchedAt` breaking ties. With no
site in range it falls back to `selectNearestSite`, preserving today's behaviour after a genuine
relocation.

Three constraints shaped it:

1. **The `api` in the dedup key is load-bearing.** `MetarCloudBlender` already collapses the
   NWS/Synoptic *transport* duplicate and prefers the requested provider. Deduping on
   `(station, timestamp)` here could keep the Synoptic copy and that preference would never fire.
   This owns the **site** axis only.
2. **The temperature blend has no `(station, timestamp)` dedup** — it goes straight to
   `groupBy { stationId }` and its own comment warns it is order-sensitive. Merging fragments
   without the shared dedup would have delivered KSJC's reading twice and double-weighted it in the
   IDW. This is the step that makes the fix safe for temperature, and it exists because the user
   asked whether the plan covered it.
3. **`distanceKm` is stored relative to the fetch location**, drives the IDW weights, and cannot be
   recomputed (no station coordinates, and a distance has no bearing). That sizes the tolerance:
   across 783 m, KNUQ reads 4.1 km from the court against 3.8 from home — 7.5% distance error, ~16%
   of one station's weight, against the alternative of no data. Across the 11 km read box the same
   error would make the weights meaningless.

**Both platforms**, which failed in opposite directions from the same missing concept:

| | before | after |
|---|---|---|
| Android | collapsed to one site — deleted a 783 m fragment | merges within 1.1 km, deduped |
| Desktop | no collapse at all — merged everything within 11 km, undeduped, mixed frames | merges within 1.1 km, deduped |

**`ObservationProximityQueryAllowlistTest`**, the observation-side sibling of the 2026-07-10 guard
that has caught every recurrence in this family.

## Two float bugs found while testing

Both real, both in the new code. Write coordinates are quantized to 3 dp, so a fragment exactly at
the tolerance is an ordinary case — and in doubles `37.427 - 37.417` is `0.00999999999999801` while
`37.417 - 37.407` is `0.01000000000000512`. A raw `<=` admitted one and rejected the other on float
noise alone; the same noise in the site *ranking* separated two genuinely equidistant fragments by
one part in 10^13, so the `fetchedAt` tie-break was unreachable. Both comparisons now round to the
write grid first. The first surfaced as a failing test I initially mistook for a bad fixture.

## Verification

All ten plan rows pass: `:shared` 2599, `:desktop` 887, `:app` 3156, zero failures. One unrelated
failure along the way — `AviationWeatherLivenessTest` — was a live-network flake; the upstream API
returned 200 on a direct check and the test passed on rerun.

**On device:** built and installed; the actual cloud line is continuous from 11a through the current
hour. The curve's step shape is data, not artifact: 27–31% while KSJC's 3,048 m deck is *inside*
KNUQ's ceilometer range (so KNUQ's 0 is a real contradicting measurement), 75% while KPAO's 5,486 m
deck is in the anchor window (so `CeilometerBlindSpot` excludes KNUQ), and 0% at the right edge
because the deck genuinely cleared and both stations agree.

I called that shape a defect before checking it, and it is not one.

## Left open

The ceilometer rule toggles on KPAO's hourly reporting cadence, so the curve steps between the two
regimes rather than easing. Whether an above-ceiling deck should persist across buckets — cloud aloft
does not vanish between one station's hourly reports — is a real question and is not decided here.

Also unchanged: `CurrentObservationReader` still picks a single row by nearest site. That asks "which
observation is now", where site identity is the question rather than an obstacle; whether an
excursion should widen it too is separate.
