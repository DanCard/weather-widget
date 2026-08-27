# Merge nearby device-site fragments for observation reads

**Date:** 2026-08-27
**Status:** Proposed — awaiting implementation approval
**Scope:** Android observation read path. Fixes the temperature actual line and the cloud actual
line with one change, because they share it.

## The report

The Samsung widget's actual cloud line broke into two segments with a 12:00–13:15 hole. The user
had walked ~800 m to play basketball during exactly that window.

## Root cause

`location_mode` is `follow_device`, so while the phone was away the widget re-keyed its fetches to
the court's coordinates. Two device-site fragments now exist, **783 m apart** (measured):

| stored site | rows | span | cloud rows |
|---|---|---|---|
| `37.424, -122.088` (court) | 99 | 09:25 → 13:30 | 65 |
| `37.417, -122.089` (home) | 63 | 09:30 → 13:55 | 36 |

Cloud-carrying rows per 15-minute block show the hole exactly:

| block | home | court |
|---|---|---|
| 11:45 | 8 | 6 |
| **12:00 – 13:15** | **0** | **3–5** |
| 13:30 | 3 | 1 |

`app_logs` confirms the resolver settled back at `lat=37.4167942 lon=-122.089014`, so
`selectNearestObservationSite` picks the home fragment and discards every court row. The data is in
the database and invisible to the graph. NWS was fine throughout — KSJC reported `44/75` every five
minutes across the whole gap.

## Why this is the same bug in both curves

Temperature and cloud are **columns on one `observations` row**, keyed by one device coordinate, and
both read through the same helper:

```
TemperatureStateResolver:553   ─┐
                                ├─► getObservationsInRange ─► selectNearestObservationSite
ObservationDao.getCloudActuals ─┘
```

So the temperature actual line has the identical hole. One fix covers both — that is the point of
doing it here rather than in the cloud blender.

## What the existing precedents do, and why neither closes this

**`674ab7b0` (freshest-wins for the noon cloud bar)** names the canonical rule for duplicate rows,
and `HourlyForecastSelector` spells out its three steps: keep the display source; drop rows that are
not the same physical site; take the freshest remaining row. Its insight was that step 2 *cannot*
separate same-site fragments and deliberately does not try — "dropping same-site fragments is what
blanked the forecast line once" — so only `fetchedAt` can.

This case is the mirror image. 783 m is *outside* `SAME_SITE_TOLERANCE_DEG` (0.002° ≈ 222 m), so
step 2 fires and deletes the court fragment outright. The noon bug was "two fragments survived and
we picked the wrong one"; this is "two fragments survived the query and we deleted the right one."

**`LocationMatch.selectNearestSiteWith`** was written for this scenario — its docstring literally
says "a ~0.8 km GPS excursion created a second fragment inside the ± TOLERANCE_DEG box" — and still
does not fix it. It only skips sites where *every* row is unusable; the home site has 36 usable
cloud rows, so it wins on distance and the court's 65 are discarded anyway. It is also wired only
into the Observations screen, never the graph path.

## The insight that makes the fix small

**A device site is not where the weather is.** `locationLat`/`locationLon` on an observation row
records where the phone was standing when it fetched — fetch provenance. The weather is at KSJC
(16 km) and KNUQ (4 km), and both fragments blend *the same stations*. Collapsing to one device site
discards observations of the same sky because the phone moved 783 m.

That collapse is right for forecasts (model output for a point) and wrong for observations. The read
path cannot currently tell them apart.

## Proposed implementation

### 1. Shared `ObservationSiteMerge`

Pure, no platform types.

- `MERGE_TOLERANCE_DEG = 0.01` (≈1.1 km lat, ≈0.9 km lon at 37°N) — deliberately between
  `SAME_SITE_TOLERANCE_DEG` (0.002) and the read box `TOLERANCE_DEG` (0.1). Sized by the
  `distanceKm` argument below, not by taste.
- Keep rows from every site within `MERGE_TOLERANCE_DEG` of the query centre; drop sites beyond it,
  preserving today's protection against a neighbouring town's rows.
- Dedupe survivors by **`(stationId, timestamp, api)`**, preferring the row whose device site is
  nearest the query centre.

### 2. The dedup key must include `api`

`MetarCloudBlender` already dedupes by `(stationId, timestamp)` to collapse the **transport**
duplicate — NWS and Synoptic storing the same physical METAR — and its comparator prefers
`api == providerApi`. If the shared step deduped on `(stationId, timestamp)` alone it could keep the
Synoptic copy and the blender's provider preference would never fire. The shared step collapses only
the **site** axis; the transport axis stays downstream where it already works.

### 3. Both consumers

- **Cloud:** `MetarCloudBlender`'s own dedup becomes partly redundant and stays — it is idempotent
  and still owns the transport axis.
- **Temperature:** `ActualTemperatureSeriesBuilder` has **no** `(stationId, timestamp)` dedup — it
  goes straight to `groupBy { it.stationId }` and its own comment warns the blend is order-sensitive.
  Merging fragments without the shared dedup would deliver KSJC's 11:55 reading twice and
  double-weight it in the IDW. **This is the step that makes the fix safe for temperature**, and
  without it the fix would introduce a new bug in the actual line.

### 4. `distanceKm` is stored relative to the fetch location

It drives the IDW weights in both blends and cannot be recomputed from stored fields (we keep no
station coordinates, and distance alone has no bearing). Two consequences:

1. Preferring the nearest-site copy means the accurate frame wins wherever both sites have the row.
2. Where only the far fragment has it — the entire 12:00–13:15 gap — its distances are used, off by
   at most the merge tolerance. Measured here: KNUQ 4.1 km from the court vs 3.8 km from home, a
   7.5% distance error and ~16% of one station's weight. That is the *reason* for a 0.01° bound:
   across the full ±0.1° box the same error would be 11 km and the weights meaningless.

### 5. Recurrence guard

Extend `HourlyProximityQueryAllowlistTest` (added 2026-07-10 for precisely this bug family) to the
observation read surface, so a new call site that reads `observations` without going through the
merge fails the build. Every recurrence in this family has been a new call site skipping site
unification.

### 6. Desktop, which has the opposite exposure

A laptop moves — home, office, cafe — so it creates the same fragments as the phone. But
`DesktopWeatherDao.getObservationsInRange` performs **no site collapse at all**: it returns the raw
`±TOLERANCE_DEG` box read, so today it already merges every fragment within **11 km**, with mixed
`distanceKm` frames and no `(stationId, timestamp)` dedup on either blend.

So the two platforms fail in opposite directions from the same missing concept:

| | today | after |
|---|---|---|
| Android | collapses to one site — deletes a 783 m fragment's rows | merges within 1.1 km, deduped |
| Desktop | merges everything within 11 km, undeduped, mixed distance frames | merges within 1.1 km, deduped |

The shared step **loosens** Android and **tightens** desktop, and they converge on one rule. That
convergence is the argument for wiring both rather than patching the phone alone: a single
definition of "near enough to be the same observation" is what stops this family recurring on
whichever platform was not touched last time.

A desktop that genuinely never moves sees a no-op — one site, nothing to merge or drop.

## Verification

| # | Kind | What it pins | Result |
|---|---|---|---|
| 1 | Unit (shared) | `ObservationSiteMergeTest` — a 783 m fragment is kept, a 9 km one discarded, and with nothing nearby the nearest site is still returned | 12/12 pass |
| 2 | Unit (shared) | Dedup prefers the nearer site's copy (accurate `distanceKm` frame); a row only the far fragment holds is kept; distinct timestamps all survive | included |
| 3 | Unit (shared) | The `api` axis survives, so the blender's NWS/Synoptic transport preference still decides | included |
| 4 | Unit (shared) | Tolerance boundary is exact and symmetric on the write grid; one thousandth beyond is excluded | included |
| 5 | **Integration** (shared) | `ObservationSiteMergeBlendIntegrationTest` — the measured scene through `MetarCloudBlender`: all six reports reach the curve, no gap exceeds the 30-minute bridge that would split the line | 3/3 pass |
| 6 | **Integration** (shared) | The temperature-safety case: a station reported from both fragments collapses to one row, from the home frame — without this the order-sensitive temperature blend would double-weight it | included |
| 7 | Regression | A neighbouring town's fragment still never joins the series | included |
| 8 | Architecture | New `ObservationProximityQueryAllowlistTest`, the observation-side sibling of the 2026-07-10 guard | 1/1 pass |
| 9 | Full suites | `:shared` 2599, `:desktop` 887, `:app` 3156 | 0 failures |
| 10 | On-device | Built and installed on the Samsung | pass — see below |

### Two float bugs found while testing

Both were in the new code and both are real, not fixture artifacts. Write coordinates are quantized
to 3 dp, so a fragment exactly `MERGE_TOLERANCE_DEG` away is an ordinary case — and in doubles
`37.427 - 37.417` is `0.00999999999999801` while `37.417 - 37.407` is `0.01000000000000512`. A raw
`<=` admitted one and rejected the other for no reason but float noise, and the same noise in the
site *ranking* meant two genuinely equidistant fragments were separated by one part in 10^13, so the
`fetchedAt` tie-break could never be reached. Both comparisons now round to the write grid first.

### Live confirmation

The gap is gone: the actual cloud line runs continuously from 11a through the current hour, where
before it was two segments with a 75-minute hole.

The curve's step shape is data, not artifact, and worth recording:

| time | stations reporting | drawn |
|---|---|---|
| 11a–1:20p | KNUQ `CLR` 0 (3.8 km) + KSJC 75 @ 3,048 m | 27–31% |
| 1:20p–2:10p | KPAO 75 @ **5,486 m** enters the anchor window | **75%** |
| 2:05p onward | KSJC itself reports 0; KNUQ 0 | **0%** |

The 0% at the right edge is correct — the deck genuinely cleared, and both stations agree. The step
up to 75% is `CeilometerBlindSpot` firing: KSJC's 3,048 m deck is *below* the 3,658 m ceiling, so
KNUQ can see it and its 0 is a real contradicting measurement; KPAO's 5,486 m deck is above it, so
KNUQ's 0 is excluded for those buckets only.

**Known roughness, not fixed here:** the rule toggles on KPAO's hourly cadence, so the curve steps
rather than eases between the two regimes. Whether an above-ceiling deck should persist across
buckets — cloud aloft does not vanish between one station's hourly reports — is a real question and
is left open.

## Risk

A fragment up to ~1 km away now contributes rows whose `distanceKm` is off by up to that distance,
shifting IDW weights by roughly 15% for the nearest stations during windows the current site did not
cover. The alternative is what ships today: no data at all for those windows. The bound exists so
this stays a small weighting error rather than a wrong-place error.
