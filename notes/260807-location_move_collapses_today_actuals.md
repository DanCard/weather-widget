---
name: location-move-collapses-today-actuals
description: "A location change strands the day's observations at the old site — today's thermostat low regresses to the coldest of the last hour; two write-side defects still unfixed"
metadata: 
  node_type: memory
  type: project
  originSessionId: 4dcf019b-71fa-43a9-b883-cd7637abf691
  modified: 2026-08-07T21:08:34.449Z
---

Samsung 2026-08-07: daily view today-column thermostat low read **69.8°** instead of **60.8°**
(Pixel, which had not moved, was correct). Not a rendering bug — real data corruption, triggered
by the device changing location mid-day.

**Mechanism.** GPS promoted a new site at 13:23:01. At 13:26:57 an opportunistic *current-temp*
fetch ran there and created site `37.402,-122.041` with **5 observation rows** (12:10–13:20).
`ObservationDao.getObservationsInRange` collapses the ±0.1° box to the *nearest* site via
`LocationMatch.selectNearestSite`, so every read instantly switched to that stub and discarded the
455 rows already collected today at sites 3–5 km away. "Today's low" stopped meaning the overnight
minimum and became the coldest of the last hour.

**The breadcrumb trail** (all in `app_logs`, this is the fastest way to re-diagnose):
- `TODAY_BAR_DEBUG` brackets the regression: `obsLow=60.7995` at 13:20:59 → `obsLow=69.8242` at 13:32:11.
- `EXTREMA_WINDOW_DIAG` shows the blend starving: `isolated=[hi=69.80@13:15 lo=69.80@13:15 pts=1]`.
  **`pts=1` or `pts=2` for a whole day means a site collapse, not a blend-math bug.**
- `DAILY_HISTORY_OVERWRITE` names the victim row, `DAILY_HISTORY_STABLE` the last good value.
- Per-site coverage check:
  `SELECT locationLat, locationLon, COUNT(*), MIN(temperature) FROM observations
   WHERE api='NWS' AND timestamp >= <local midnight ms> GROUP BY 1,2;`

**Fixed** (commit `ee0816ad`): the *latency*. The auto-heal only resampled on full syncs, so the
bogus location survived 41 minutes. `ScreenOnReceiver` now resamples on plug-in and unlock — see
[[gps_resample_seam_breadcrumb]].

**NOT fixed — two real defects still live:**

1. **Cross-site write clobber.** `DailyActualsStore.persistExtremes` reads observations site-collapsed
   but reads `existingHistory` from `dailyHistoryDao.getExtremesInRange(...)`, which applies only
   `LocationMatch.ROOM_WHERE` (the coarse ~7 mi box, **no** `selectNearestSite`). It then writes the
   new values onto *every* fragment in the box via `new.copy(locationLat = existing.locationLat, …)`.
   So a recompute anchored at site A overwrites site B's row — here the stub's 69.8 destroyed the old
   site's correct 60.8, meaning the damage outlived the move. Same shape as
   [[today_incomplete_repair_cross_site]]: filter an uncollapsed pool by `source` but not `sameSite`.
   Fix is to constrain `persistExtremes` to `sameSite` fragments.
2. **Handoff readiness is observation-blind.** `LocationHandoffPolicy.evaluateCandidateUsability`
   gates promotion on daily + hourly **forecast** coverage only. A site with zero observations is
   declared `complete_visible_coverage` and promoted — and observations are the entire substance of
   the today-column thermostat. That branch also returns *before* the `MOVING_GRACE_MS` (30 min)
   check, so the guard that exists to stop a drive promoting intermediate sites never engaged.

Minor: the sparse-history self-heal (`OBS_HOURLY_BACKFILL`, `reason=temperature_graph_sparse_history`)
would repopulate a new site, but its cooldown key is `"${displaySource.id}_HOURLY_HISTORY"` per
widget with **no site component**, so a heal at the old site suppresses the new site's for 30 min.

Related: [[shared_location_match_predicate]], [[location_box_admits_stale_nearby_site]],
[[snapshot_paths_must_select_a_site]], [[widget_fetch_location_decoupled]].
