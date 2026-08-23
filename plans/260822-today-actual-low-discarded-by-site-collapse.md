# Today's actual low is discarded by the site collapse

**Date:** 2026-08-22
**Status:** SUPERSEDED 2026-08-22 by
[260822-today-low-backfill-then-forecast-fallback.md](260822-today-low-backfill-then-forecast-fallback.md)
— Danny chose backfill-first with a forecast-low fallback. Pooling (the candidate below) was NOT
adopted. Retained for the incident evidence and the alternatives analysis, which the decided plan
builds on.

## The observation that started this

Samsung, 2026-08-22 ~19:08, daily view, Tomorrow.io. Today column's thermostat bottomed out at
**66.52°** — the *noon* reading — instead of the day's real low of **57.03°** at 06:47.

The measurement was not missing. It was in `observations` the whole time:

| site | rows | span | min | distance from home |
|---|---|---|---|---|
| `37.417,-122.089` (home) | 40 | 00:00 → 19:26 | **57.03** | — |
| `37.416,-122.087` | 7 | 12:00 → 18:00 | 66.52 | ~0.21 km |
| `37.424,-122.088` | 8 | 12:00 → 19:26 | 66.52 | ~0.82 km |

Two GPS excursions (`GPS_RESAMPLE candidate_detected` at 18:27:08 and 18:40:58) promoted the two
stub sites. `ObservationDao.getObservationsInRange` runs the ±0.1° box and then collapses to the
single **nearest** site via `LocationMatch.selectNearestSite`, so from 18:40 the read saw only the
8 afternoon rows and today's "low" became the earliest of those.

**Nothing was ever recorded under the stub coordinate before 12:00** — no fetch had run there. But
the low for that location was still on disk, filed under home, 800 m away. For NWS it is literally
the same station (KNUQ) under a different device anchor.

Self-repaired 19:26:21 when `trigger=power_connected` resampled back home.

## Why the collapse exists

`LocationMatch.kt:62` documents it: the ±0.1° box spans ~7 miles, so a site visited earlier in the
day can sit inside it, stop being refreshed, and leak stale rows into whatever reads the box. Real
incidents cited there: a stale LOS GATOS station appearing as a 6th entry in a 5-station list; a
2-day-old noon cloud row winning a `firstOrNull` and flapping the daily bar.

The collapse is not wrong. It is **blunt**: it defends against a site ~8 km away going stale by
discarding sites 0.2–0.8 km away that are fresh and describe identical weather.

## Candidate: pool observations across the same locality

Pool fragments near enough to be the same weather instead of selecting exactly one. The existing
radii do not cover this case:

| constant | value | ≈ | purpose |
|---|---|---|---|
| `SAME_SITE_TOLERANCE_DEG` | 0.002 | 200 m | fragment identity — is this GPS jitter? |
| *(missing)* | ? | ~1 km? | same locality — is this the same weather? |
| `TOLERANCE_DEG` | 0.1 | ~8 km | coarse box — "near the user" |

A ~1 km pooling radius would union all three fragments above and still exclude the different-town
case the collapse was written to stop (the doc's example is 0.075° ≈ 8 km). Today's low becomes
`min(57.03, 66.52, 66.52) = 57.03`, with no refetch and no network.

**The radius is unjustified so far.** ~1 km was picked to sit between this incident's 0.82 km and
the doc's 8 km — two data points. Before adopting it, derive it from what `observations` actually
shows about this device's GPS spread over the retention window, e.g.:

```sql
SELECT locationLat, locationLon, COUNT(*), date(timestamp/1000,'unixepoch','localtime') d
FROM observations WHERE api='NWS' GROUP BY 1,2,4 ORDER BY d DESC;
```

### Known wrinkle if pooling is adopted

Observation rows carry `distanceKm` computed relative to the device location they were filed under,
and the IDW blend weights on it (memory `idw_weight_window_dependent_distance`). Pooling across
anchors means those weights must be recomputed against the *current* location, not taken at face
value. Daily min/max are unaffected — a 06:47 reading of 57.03 is 57.03 whatever anchor filed it —
but the blended series is not.

## Alternatives not yet ruled out

1. **Backfill a newly promoted site.** On promotion, pull today's history for the new coordinate so
   the nearest-site read is correct by construction. Keeps the single-site invariant, and is the
   ONLY option that helps a genuine move to a new city (pooling has nothing to offer there).
   Costs an API call, has a latency window where the widget is confidently wrong, and fails offline.

   **Investigated 2026-08-22 — two independent blockers, both must be fixed for this to work:**

   a. **The trigger asks the wrong question.** The self-heal ran during the excursion and declined:
      `18:59:12 OBS_HOURLY_BACKFILL_SKIP source=NWS reason=coverage_ok latest_gap_min=19
      max_gap_min=10`. It measures **gaps between consecutive observations**, not **whether the day
      is covered from local midnight**. The stub's 8 rows (12:00→19:26) were evenly spaced, so a
      window starting at noon scores as perfectly healthy. The heal can never fire for a truncated
      *start*. (This is a different defect from the cooldown-key one below, and it fires first.)

   b. **The Tomorrow.io fetch window is 6 hours, not 23.**
      `shared/src/main/kotlin/com/weatherwidget/data/remote/TomorrowIoApi.kt:43` sends
      `startTime=nowMinus6h`, commented "core temperature/cloud fields are available six hours into
      the past on the free plan." Verified against the stored rows — every
      `TOMORROW_IO_RECENT_HISTORY` row lands ~6–7 h after its own timestamp (00:00 fetched 06:44;
      11:00 fetched 17:00; 12:00 fetched 18:00). **Home's full-day coverage is an artifact of ~12
      fetches accumulating since midnight, not something one fetch can reproduce.** A fresh site
      fetched at 18:41 reaches back only to ~12:00 → min 66.52, i.e. retrieval as implemented
      returns exactly the wrong number this bug is about.

   **Blocking open question:** can the window widen? Memory `tomorrow_io_24h_history_limit` says the
   plan permits `startTime` up to 23 h back (`minusHours(23)`, and `providesHistoricalActuals=true`
   was set on that basis); the code comment says core fields are only available 6 h back on the free
   plan. These may both be true (policy limit vs field-availability limit). **Settle with a live
   probe before designing around either.** If 23 h is reachable for temperature, one widened fetch
   covers today's overnight low and this option becomes viable. If 6 h is the real ceiling,
   retrieval can never recover today's low for Tomorrow.io.

   **NWS is the favourable case:** `NwsObservationBackfiller` already takes `lookbackHours`
   (`app/src/main/java/com/weatherwidget/data/repository/NwsObservationBackfiller.kt:173`) and pulls
   `/stations/{id}/observations`, which serves the whole day from the same station (KNUQ) whatever
   coordinate you ask from. For NWS only the trigger (a) is broken.

   Also still live: the sparse-history self-heal's cooldown key
   (`"${displaySource.id}_HOURLY_HISTORY"`) has **no site component**, so a heal at the old site
   suppresses the new site's for 30 min (memory `location_move_collapses_today_actuals`).
2. **Fix promotion instead (defect #2).** `LocationHandoffPolicy.evaluateCandidateUsability` gates
   on forecast coverage only, so an observation-less site is declared `complete_visible_coverage`
   and promoted — and that branch returns *before* the `MOVING_GRACE_MS` check. Stops the GPS case
   at the source; does nothing for a genuine move, fresh install, or newly-enabled source.
3. **Widen `SAME_SITE_TOLERANCE_DEG`.** Simplest edit, but that constant means "is this GPS jitter
   of one point" and is load-bearing elsewhere (`ROOM_SAME_SITE_WHERE` guards row *deletion*).
   Overloading it to also mean "same weather" is how it ends up wrong for both.
4. **Do not collapse for daily extrema specifically.** Narrower than general pooling — use the full
   box for min/max only, where a stale distant row can only widen the range, and keep nearest-site
   for everything else. Needs a check on whether a stale row can actually corrupt an extreme.
5. **Forecast-low fallback when coverage is truncated.** *Considered and rejected for this
   incident* — it would show a forecast when a real measurement exists 800 m away. May still have
   standalone merit for the genuine-no-data case (fly to a new city at noon), but it is not the fix
   for what was observed here.

## Open questions

- How far apart before two fixes are genuinely different weather? Is one radius right everywhere,
  or does it depend on terrain (a coastal/inland boundary can shift 10 °F in 2 km)?
- Does pooling belong at the DAO read, or only in the daily-extrema path?
- Do the per-source point queries (Tomorrow.io files a point query per coordinate) behave
  differently under pooling than station-based sources (NWS files real station rows)?
- Should the today column distinguish "low measured here" from "low measured 800 m away"? Or is
  that a distinction without a difference?

## Prerequisite regardless of which option wins

Defect #1, the cross-site write clobber — see
[260822-fix-cross-site-actuals-clobber.md](260822-fix-cross-site-actuals-clobber.md). A recompute
anchored at a stub currently writes the stub's truncated low onto the home row
(`DAILY_HISTORY_OVERWRITE … at=37.41682… low=57.03->66.52` at 18:41:58), corrupting the very data
any read-side fix would rely on.

## Related

- Memory: `location_move_collapses_today_actuals` (this incident appended)
- Memory: `shared_location_match_predicate`, `snapshot_paths_must_select_a_site`,
  `location_box_admits_stale_nearby_site`, `idw_weight_window_dependent_distance`
- `shared/src/main/kotlin/com/weatherwidget/data/local/LocationMatch.kt:62` — `selectNearestSite`
  rationale
- `app/src/main/java/com/weatherwidget/data/repository/DailyActualsStore.kt:88` —
  `getDailyActualsWithLiveToday`, where today's pool is read
