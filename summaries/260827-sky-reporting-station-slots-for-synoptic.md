# Reserve station slots for sky-reporting stations

**Date:** 2026-08-27
**Plan:** [plans/260827-sky-reporting-station-slots-for-synoptic.md](../plans/260827-sky-reporting-station-slots-for-synoptic.md)

## What happened

With Silurian selected (borrowing actuals from Synoptic), the cloud actual line broke into two
segments with a 48-minute hole. Under NWS at the same minute it was continuous. The user asked
whether there was a way to backfill automatically.

## Backfill was already running, and could not help

Every Synoptic call uses `recent = max(hours × 60, 120)` minutes and REPLACE-upserts the window, so
the gap reports had already been re-fetched many times. They still carried no cloud, because
upstream they never did:

```
12:55  KNUQ 271955Z AUTO 35006KT 24/15 A2998 RMK AO2   ← no sky group
13:15  KNUQ 272015Z AUTO 35007KT 10SM 24/16 A2998      ← no sky group
```

A measurement that was never made cannot be backfilled. The answer had to be *more stations*.

## Root cause

`SynopticObservationFetcher` kept the nearest ten of ~197 returned stations. The app's own log named
them, and eight had never reported sky condition once — 601 rows between them, zero cloud. They won
their slots purely on proximity. `NwsApi.Observation.cloudLayers` documents exactly this: *"Empty =
not reported; personal stations always return empty."*

That left the cloud curve resting on two stations, and it broke the moment both went quiet: KNUQ
omitted its sky group twice and KPAO reports hourly, so cloud-carrying reports ran 12:47 → 13:35 —
past `CloudActualSeries`' 30-minute bridge, which correctly split rather than inventing data.

## Why not just raise the limit

The user offered that as a fallback if the better fix was hard. It wasn't hard, and the limit would
have been the expensive option: the cap ranks by distance, so reaching a 16 km airport means
admitting every closer station first — plausibly ~100, roughly **10× the stored rows and parse
cost**, and still luck rather than a rule.

The limit was not too small. The ranking was measuring the wrong axis for this question, and no
limit is high enough to fix that.

## What changed

**`SkyReportingStationSlots`** (shared, pure): take the nearest `DEFAULT_LIMIT` exactly as today,
then — only if fewer than `MIN_SKY_STATIONS = 3` of them report sky — add the nearest sky-reporting
stations until the quota is met.

Additive, never displacing: proximity remains the right axis for the temperature IDW blend, so every
station kept before is still kept. The predicate was free — `RadiusStation.observations` already
carries parsed `cloudLayers` for all ~197 stations before truncation discards them, so no extra
request and no extra parsing.

Three, not two, because two is what shipped and what broke: a 20-minute reporter plus an hourly one
left a 48-minute hole the moment the first omitted a sky group.

The `SYNOPTIC_FETCH` log now also prints `sky=` — which stations can answer the cloud question at
all. A curve that breaks into segments with a low count there is short of reporters, not broken.

## Verification

All ten plan rows pass: `:desktop` 899, `:app` 3169, shared green, zero failures. A mutation check
(disabling the shortfall branch) fails the new tests. Two initial test failures were my own fixture
arithmetic, not the code — one assertion counted a station the cap had already displaced, the other
expected a station to be excluded when the shortfall of three meant it qualified.

**The assumption I could not verify is now resolved.** The plan flagged that I had no way to confirm
Synoptic offers KSJC — the `SYNOPTIC_API_TOKEN` in `local.properties` is stale (`Invalid key` from
both `/stations/timeseries` and `/auth`). The first fetch from the new build settles it:

```
stations=11  ids=…,KPAO,E0597,F3725,KSJC  sky=KNUQ,KPAO,KSJC
```

KSJC was in the network all along and was being discarded for being 11th nearest. The set grew
10 → 11 and stored rows 1,666 → 1,975 — **19%**, against the ~10× the limit route would have cost.

**The gap closed with real measurements.** Because the call already re-fetches a 24-hour window,
admitting KSJC backfilled its history too: the window that held nothing now holds a reading every
five minutes, including `12:55` and `13:15` where KNUQ was silent. The widget draws one continuous
curve.

So the answer to "is there a way to backfill automatically" turned out to be yes — but not by
re-fetching what was missing. By fetching the station that had been there all along.
