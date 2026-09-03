# Session summary — "cloud data missing from NWS 7:00 am" was a discarded GPS-jitter fragment

**Date:** 2026-09-03 · **Plan:** none (investigation from an emailed bug report) ·
**Status:** root cause identified, **not fixed**; instrumentation added and installed

## What was reported

Emailed bug report, sent 2026-09-03 13:21:34 from the app's own report template.
Samsung SM-F936U1 (Fold), Android 16, version 26090201, battery 70% on battery.
Body: *"cloud data missing friend nWS 7:00 a.m."* — i.e. **cloud data missing from NWS, 7:00 a.m.**

Three widgets installed (345 / 349 / 352), all NWS, all at `37.4241, -122.0883`.
No screenshot attached (the template's tip asks for one); the 300 bundled log lines carried the answer.

## What is KNOWN

### The symptom, from the report's own logs

At 13:20:38 — the same second the user toggled widget 345 from Tomorrow.io back to NWS:

```
CLOUD_COVER_GAPS: widget=345 source=NWS missing=7 total=19 ranges=4a–10a
                  reason=- sourceMissingFromLoad=false
CLOUD_COVER_GAPS_REFRESH: widget=345 source=NWS missing=7, requesting immediate API update
```

Window is `ZoomStage.WIDE` (−12/+6) around an aligned centre of 13:00 → **01:00..19:00 inclusive,
19 hour keys**, which matches `total=19`. Seven of those hours (04–10) produced no `CloudHourData`
at all. A missing hour is not a null value — `buildCloudHourDataList` emits nothing, so the curve
simply stops, which on screen is indistinguishable from "NWS never published those hours".

`sourceMissingFromLoad=false` rules out the known worker hourly source-snapshot race
([[worker_hourly_source_snapshot_race]]).

This is the **only** `CLOUD_COVER_GAPS` row in `app_logs` in the preceding two days — a single
one-shot event at the toggle, not a recurring condition.

### The emission gate (read from source, not inferred)

`CloudCoverViewHandler.buildCloudHourDataList` drops an hour at exactly two points:

```kotlin
val entity = entityByTime[point.timeMs] ?: continue   // no row for the display source that hour
val cover  = point.forecastCover ?: continue          // unreachable in practice (see below)
```

`CloudSeriesBuilder.build` already filters `liveHours` on `visibleCloudCover() != null`, and
`forecastCover = frozen ?: live`, so `forecastCover` can never be null for an emitted point.
**Therefore `missing=7` means: no NWS row with a cloud value reached the handler for those hours.**

### The data DID exist (device DB pull, `backups/20260903_140055_sm-f936u1_RFCT71FR9NT`)

Two independent facts combined.

**1. A genuine coverage hole at the configured site.** At `37.424,-122.088` (the quantized
configured location), NWS hourly rows per day:

| date | rows | hours | fetchedAt |
|------|------|-------|-----------|
| 2026-09-02 | 24 | 00–23 | 2026-08-27 12:45:28 |
| **2026-09-03** | **13** | **11–23** | 2026-09-03 13:20:13 |
| 2026-09-04..10 | full | 00–23 | 2026-09-03 13:20:13 |

The 08-27 fetch's ~156-hour NWS horizon ran out at 09-02 23:00; the next fetch **at that site** was
11:59 today. Hours **00:00–10:00 today were never covered there**, in `hourly_forecasts` *or*
`hourly_forecast_history` (confirmed: history at that exact site has rows only for hours 11–19).

**2. GPS jitter filed the missing hours under a different site.** Distinct NWS fetch events:

```
2026-09-03 01:26:58  37.417,-122.089   covers 01:00 →   (the ONLY coverage of 04:00–10:00)
2026-09-03 11:59:16  37.424,-122.088   covers 11:00 →
2026-09-03 13:20:13  37.424,-122.088   covers 13:00 →
2026-09-03 13:29:27  37.416,-122.087   covers 13:00 →   (after the report)
2026-09-03 13:52:56  37.417,-122.089   covers 13:00 →   (after the report)
```

`37.417,-122.089` is 0.00715° from the configured centre `37.42414855957031,-122.08828735351562` —
**outside** `LocationMatch.sameSite` (0.002°) but **inside** the stitcher's nearby fallback (0.01°).

Counted against the real stored coordinates, every hour 01–19 had ~47 candidate rows with a
non-null `cloudCover` within the 0.01° nearby box, **and all of them predate the 13:20:38 render**
(verified with a `fetchedAt <= cutoff` count — the post-report 13:29/13:52 fetches are not what
made them appear).

### The mechanism: two layers disagree about borrowed rows

- `HourlyForecastStitcher.collapse` deliberately lets an hour with **no** same-site row borrow from
  a fragment within `NEARBY_FALLBACK_TOLERANCE_DEG` (0.01°) rather than render blank. This is
  documented and intentional — it is the fix for commit 72e5a033's "blank lines from GPS jitter".
- `WidgetRenderer.kt:332` then calls `GraphDataLoader.unifyToNearestSite`, whose
  `LocationMatch.selectNearestSite` picks the single nearest site and keeps **only** rows
  `sameSite` (0.002°) with it — discarding every borrowed row.

Harmless while the winning site covers those hours itself. **Total data loss when it does not**,
which is exactly today's case.

Both DAOs (`getHourlyForecastsForSources`, `getHistoryInRangeForBucketWindowForSources`) use the
wide `LocationMatch.ROOM_WHERE` (±0.1°) box, so the fragment does reach the stitcher; and
`HourlyForecast.toEntity(lat, lon)` only fills *null* coordinates, it does not re-stamp rows to the
query centre — so `outSites` in `HOURLY_LOAD` is a truthful report of what left the loader.

### The fingerprint, visible without a DB pull

```
HOURLY_LOAD:  caller=full_sync ... outSites=37.42400,-122.08800|37.41700,-122.08900   ← two sites out of the loader
headerState:  ... rowsLoc=37.42400,-122.08800(216)                                    ← one site at the handler
```

`unifyToNearestSite` is the only thing between those two lines.

### Contributing factor

Widget 345 was displaying **Tomorrow.io** all morning. NWS is throttled while not the displayed
source, so the site-`37.424` hole went unnoticed and unrefreshed until the toggle
(`SYNC_START ... reason=toggle_api_stale, lastFullFetch=4842s ago`).

## What is NOT known

1. **The 3-hour discrepancy — the main open question.** On a strict single-site collapse, hours
   **01–10 (ten hours)** should have been lost, since `37.424,-122.088` covers none of them. The log
   says `missing=7`, `ranges=4a–10a` — so hours **01, 02 and 03 rendered** and I never reconciled
   why. Possibilities not eliminated: the cloud window's `alignedCenter` differing from my
   reconstruction; a second load path feeding the handler; or hours 01–03 arriving via a route I did
   not trace. **This means the mechanism above is strongly evidenced but not fully proven** — it
   explains 7 of 10 hours and does not explain why the other 3 survived.
2. **Which loader fed the render.** The render logged `hourlyCount=216`. The nearest `HOURLY_LOAD`
   is `caller=full_sync stitched=227 sources=NWS|Generic`; 227 − 11 Generic = 216 is plausible
   arithmetic but unconfirmed.
3. **Whether the new log actually fires.** Verified installed, compiling, painting, and not
   crashing — but **not yet observed firing**. Since the report the device's GPS moved to
   `37.4168,-122.0890` and the loader now emits a single site
   (`outSites=37.41700,-122.08900`), so there is currently nothing to drop. Silence right now is
   the correct silent case, not evidence the firing case works.
4. **How often this bites.** One `CLOUD_COVER_GAPS` row in two days. Unknown whether the temperature
   and precipitation curves (same stitched list, same unify) lose hours the same way unnoticed.
5. **Why the 08-27 → 11-59 fetch gap at that site was so long.** Throttling of a non-displayed
   source is the likely reason, but the seven-day span was not traced fetch-by-fetch.

## What changed

**`app/src/main/java/com/weatherwidget/widget/WidgetRenderer.kt`** (+49) — a persisted
`HOURLY_UNIFY_DROP` (WARN) log immediately after `unifyToNearestSite`, recording
`in=` / `out=` row counts, `lostHours=`, a `ranges=` description via
`HourLabelFormatter.missingHourRanges`, `center=`, and `droppedSites=`.

Guarded twice — `unifiedHourlyForecasts.size != hourlyForecasts.size`, then a non-empty set of
display-source hours that were present before unify and absent after — so a paint whose nearest site
covers everything logs nothing. Persisted rather than `Log.i` deliberately: this is the third
coordinate-fragmentation bug in this area whose decisive field was logcat-only by the time the
report arrived.

Built and installed on the reporting device (`RFCT71FR9NT`) so the next report carries it.
**Not committed** — the change is in the working tree.

## Why it was not fixed

`unifyToNearestSite` has ~10 callers (daily actuals, observations screen, staleness probe,
`NwsStationActualsStore`, `DailyInteractionRenderer`) and exists for real, measured reasons — it is
what stopped `DailyNoonCloudCover` flapping between a fresh row and a two-day-old one. The correct
fix must let *per-hour* borrowing survive the collapse rather than remove the collapse, and given
open question (1) above, the mechanism should be confirmed by a real `HOURLY_UNIFY_DROP` line before
changing shared selection semantics.

## Next steps

1. Wait for the next report carrying `HOURLY_UNIFY_DROP`; confirm `lostHours`/`ranges` match the
   `CLOUD_COVER_GAPS` ranges. That settles open question (1).
2. Then: teach the unify step to keep borrowed rows for hours the winning site does not cover
   (mirroring `HourlyForecastStitcher.collapse`'s own rule), with a regression test seeded from
   today's measured shape — configured site covering 11:00–23:00 only, a 0.007° fragment covering
   01:00–12:00.
3. Consider whether the same loss is silently affecting the temperature and precipitation curves.

## Memory written

`unify-discards-stitcher-borrowed-fragment` — indexed under *Coordinate fragmentation*.
