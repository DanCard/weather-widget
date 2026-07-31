---
name: today-incomplete-repair-cross-site
description: "Today's \"NWS evening drop\" repair in DailyViewLogic picked a complete row from ANOTHER site, showing a different town's high"
metadata: 
  node_type: memory
  type: project
  originSessionId: b8e2e468-cebb-4d7a-af93-aba94980c760
  modified: 2026-07-31T05:43:36.547Z
---

Samsung showed today's forecast high as 84° on 2026-07-30 while the Pixel/desktop showed 81°, and
84° also disagreed with the device's own hourly graph (hourly max was 83°).

Root cause: `DailyViewLogic.prepareGraphDays`/`prepareTextDays` repair the "today's freshest batch is
high-only" case (the NWS evening drop) by swapping in the most recent COMPLETE row from
`forecastSnapshots[today]`. That filter matched on `source` only — never on site — and took
`maxByOrNull { fetchedAt }`. The snapshot pool is built from the deliberately uncollapsed
`getAllForecastsInRange*` queries, so it spans the whole ~7 mi `LocationMatch` box. On a day the
device travelled, a 14:34 batch fetched at 37.377/-122.075 was the newest complete NWS row and won,
replacing BOTH high and low with that town's 84/57.

Fix: `DailyViewLogic.completeSameSiteReplacement` constrains candidates with
`LocationMatch.sameSite(...)` against the incomplete row's own coordinates.

**Why:** it looks like a stale-data bug but it is coordinate fragmentation — see
[[snapshot_paths_must_select_a_site]] and [[location_box_admits_stale_nearby_site]]. Same class,
new path.

**How to apply:** the tell is path-dependent flapping — `TODAY_BAR_DEBUG` showed `fHigh` alternating
81/84 while `sHigh` stayed 81, because `origin=PROVIDER_ON_UPDATE` (startup coordinator, collapsed
`weatherList`, repair fires) and `origin=WORKER_FETCH` (uncollapsed `weatherList`, repair never
fires) disagree. Any NEW code that filters an uncollapsed snapshot/forecast pool by `source` must
also filter by `sameSite`; `source`-only + `max(fetchedAt)` is the bug signature. Desktop has no
equivalent repair, so it was unaffected.
