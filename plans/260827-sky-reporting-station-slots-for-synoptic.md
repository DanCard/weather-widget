# Reserve station slots for sky-reporting stations

**Date:** 2026-08-27
**Status:** Proposed — implementing on request

## The report

With Silurian selected (borrowing its actuals from Synoptic), the actual cloud line broke into two
segments with a 48-minute hole. Under NWS at the same minute the line was continuous.

## Root cause

`SynopticObservationFetcher` keeps the nearest ten stations:

```kotlin
val stations = outcome.value.sortedBy { it.distanceKm }.take(limit)   // DEFAULT_LIMIT = 10
```

The app's own `SYNOPTIC_FETCH` log shows which ten:

```
stations=10 ids=E7138,AW020,F4751,KNUQ,496PG,G6550,G4110,KPAO,E0597,F3725
```

| station | cloud rows in 6 h |
|---|---|
| E7138, AW020, F4751, 496PG, G6550, G4110, E0597, F3725 | **0 — none, ever** (601 rows between them) |
| KNUQ | 23 |
| KPAO | 9 |

Eight of ten slots go to personal weather stations that have never reported sky condition once. They
win purely on proximity. `NwsApi.Observation.cloudLayers` even documents it: *"Empty = not reported;
personal stations always return empty."*

That leaves the cloud curve resting on two stations, and it broke when both went quiet at once:

```
12:55  KNUQ 271955Z AUTO 35006KT 24/15 A2998 RMK AO2    ← no sky group
13:15  KNUQ 272015Z AUTO 35007KT 10SM 24/16 A2998       ← no sky group
```

KPAO reports hourly at :47, so cloud-carrying reports ran 12:47 → 13:35 — 48 minutes, past the
30-minute bridge in `CloudActualSeries`, and the line correctly split rather than inventing data.

NWS showed no gap because it carries KSJC, which was reporting `44/75` every five minutes
throughout. Synoptic's cap drops KSJC at 16 km in favour of PWS at 2–9 km.

## Backfill is already happening and cannot help

Every Synoptic call uses `recent = max(hours × 60, 120)` minutes and REPLACE-upserts the window, so
those reports have been re-fetched many times since. They still carry no cloud, because upstream they
never did. **A measurement that was never made cannot be backfilled.** The fix has to be *more
stations*, not more re-fetching.

## Why not simply raise the limit

The cap ranks by proximity, so reaching KSJC means admitting every closer station first. From the
app's log, ~197 stations are returned and ~187 discarded:

| fetch | kept | rows stored | rows available |
|---|---|---|---|
| 2 h | 10 | 139 | 2,743 |
| 24 h | 10 | 1,666 | 34,207 |

A limit high enough to reach KSJC by distance is plausibly ~100 — roughly **10× the stored rows and
parse cost** for one useful station, and still luck rather than a rule. The limit is not too small;
the ranking is measuring the wrong axis for this question. No limit is high enough to fix that.

## The rule

**Additive reserved slots.** Proximity stays the right axis for temperature IDW, so nothing is
displaced:

1. Take the nearest `DEFAULT_LIMIT` stations exactly as today.
2. If fewer than `MIN_SKY_STATIONS` of them report sky condition, add the nearest sky-reporting
   stations not already included, until the quota is met or none remain.

The set grows from 10 to at most `10 + MIN_SKY_STATIONS`, never to 100. Temperature keeps every
station it has today; cloud gains stations that can actually answer it.

`MIN_SKY_STATIONS = 3`. Two is what shipped and is what broke: one station going briefly silent
halved the cadence and a 20-minute reporter plus an hourly one left a 48-minute hole. A third
independent reporter is the smallest number that survives one going quiet.

The predicate is free: `RadiusStation.observations` already carries parsed `cloudLayers` for all ~197
stations before the truncation discards them. No extra request, no extra parsing.

## Unverified, and deliberately not depended upon

I could not confirm that Synoptic offers **KSJC** specifically: the `SYNOPTIC_API_TOKEN` in
`local.properties` is stale (`Invalid key` from both `/stations/timeseries` and `/auth`), and the
phone's working token is not accessible here. What is known is that ~187 stations are discarded per
fetch and that KNUQ and KPAO — the same ASOS class as KSJC — are in the network.

The rule does not name KSJC. It admits whichever sky-reporting stations exist, so if KSJC is absent
the outcome is still strictly better than eight cloudless PWS holding the slots. A log line naming
the admitted sky stations makes the answer visible on the next fetch rather than leaving it a guess.

## Verification

| # | Kind | What it pins | Result |
|---|---|---|---|
| 1 | Unit (shared) | `SkyReportingStationSlotsTest` — a station silent across every observation does not report sky; one observation carrying layers is enough | 10/10 pass |
| 2 | Unit (shared) | With 10 cloudless PWS nearer than the airports, every station the cap kept before is still kept **and** the sky reporters are added; growth is exactly the shortfall | included |
| 3 | Unit (shared) | A selection already meeting the quota does not grow, and a distant sky station is not pulled in unnecessarily | included |
| 4 | Unit (shared) | Additions are the nearest qualifying stations (four available, shortfall of three, the farthest left out); no station is added twice; order stays nearest-first | included |
| 5 | Unit (shared) | Fewer sky stations than the quota returns what exists rather than failing | included |
| 6 | Unit (shared) | Temperature is unaffected — every station kept today is still kept | included |
| 7 | **Integration** (shared) | `SkyStationSlotsGapIntegrationTest` — rows → `MetarCloudBlender` → `CloudActualSeries.segments`, which is what actually decides one line or two: the two-reporter scene splits, a third 5-minute reporter makes it one, and the station that closes it is the **farthest** of the three | 3/3 pass |
| 8 | Mutation check | Disabling the shortfall branch fails the new tests. Restored | caught |
| 9 | Full suites | `:shared` · `:desktop` 899 · `:app` 3169 | 0 failures |
| 10 | On-device | Installed on the Samsung | pass — see below |

### The unverified assumption, now resolved

The plan could not confirm Synoptic offers KSJC. The first fetch from the new build settles it:

```
stations=11 hours=24 rows=34159 stored=1975
ids=E7138,AW020,F4751,KNUQ,496PG,G6550,G4110,KPAO,E0597,F3725,KSJC
sky=KNUQ,KPAO,KSJC
```

KSJC **is** in the network and was being discarded by the proximity cap. The set grew 10 → 11, not
to 100: KNUQ and KPAO were already inside the nearest ten, so the shortfall was one. Stored rows went
1,666 → 1,975 — **19%**, against the ~10× that raising the limit to reach KSJC by distance would have
cost.

### The gap is closed with real measurements

Because the Synoptic call already re-fetches a 24-hour window, admitting KSJC also backfilled its
history. The window that was empty now holds a reading every five minutes:

```
12:40 KSJC 44/75   12:50 KSJC 44/75   13:05 KSJC 44/75   13:20 KSJC 44/75
12:45 KSJC 44/75   12:55 KSJC 44/75   13:10 KSJC 44/75   13:30 KSJC 44/75
12:47 KPAO   /75   13:00 KSJC 44/75   13:15 KSJC 44/75   13:35 KSJC 44/75
```

`12:55` and `13:15` — the two timestamps where KNUQ omitted its sky group and the line broke — are
now covered by a station that did report. The widget draws one continuous curve where it drew two
segments.

## Risk

A sky-reporting station admitted from beyond the proximity cap enters the IDW blend at its true
distance, so it is weighted down accordingly — it cannot dominate a nearer station. The cost is a
slightly larger fetch (at most three more stations' rows). If no sky-reporting station exists beyond
the ten, behaviour is unchanged.
