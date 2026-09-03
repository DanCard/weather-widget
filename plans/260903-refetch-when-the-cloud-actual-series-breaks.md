# Re-fetch observations when the cloud actual series breaks

**Date:** 2026-09-03
**Status:** done — verified on the Samsung, which repaired its own 11:35–12:15 hole

## The incident

The NWS actual-cloud line on the Samsung broke between **11:35 and 12:15** on 2026-09-03. The user
walked to a basketball court at ~11:59 and the phone sat in a pocket, screen off, until 12:39:

```
11:59:14  GPS_RESAMPLE  outcome=location_moved trigger=screen_on  37.4241,-122.0883  (the court)
11:59:50  OBS_HOURLY_BACKFILL_SKIP  reason=coverage_ok latest_gap_min=12 max_gap_min=15
12:02:01  GPS_RESAMPLE  outcome=same_site trigger=screen_on
          ← 37 minutes, no paint, no sync, no poll
12:39:05  GPS_RESAMPLE  outcome=opportunistic … SYNC_START
```

The routine poll stores each station's *latest* report only — five rows per run — so poll frequency
IS stored density, and a poll that never runs is data lost for good. KNUQ's 11:55 report and KSJC's
11:40–12:20 five-minute samples were never fetched. `CloudActualSeries.segments` then split the line
correctly: 40 minutes exceeds the bridge.

The emulator looked contiguous only because its full station-history sweep happened to run at 13:43,
after the fact; the Samsung's ran at 00:11. **The emulator is not the reference.**

## Why the existing repair did not fire

`HourlyObservationBackfill` already runs on every paint and can request exactly the fetch that would
have healed this (`backfillRecentNwsObservations` pulls `/stations/{id}/observations?start&end` and
REPLACEs). It declined, logging `coverage_ok latest_gap_min=8 max_gap_min=23`. None of its gates can
see a cloud-only hole:

| gate | blindness |
|---|---|
| `maxGapMin > 75` | Measured over **all** observation timestamps. AW020/LOAC1/KSJC push temperature rows every 5–10 min, so the union's worst hole was 23 min while the cloud-carrying subset's was 40. Wrong subset, and the threshold is 2.5× the bridge besides. |
| `latestGapMin > 45` | Trailing edge only — how stale the newest row is. Interior holes are invisible by construction. |
| `metarCloudGapReason` | Cloud-aware but a **ratio over the whole window**; its own doc says it detects "a broken *series*, never a single station that has stopped contributing". One hole never moves it below half. |
| `todayStartUncovered` | About a truncated window start. |

The renderer computed the break and drew it. The backfiller never asked.

## Change

**`:shared` — one owner for the bridge rule.** `CloudActualSeries` gains

```kotlin
data class Coverage(val largestGapMs: Long, val bridgeMs: Long) { val breaks: Boolean }
fun coverage(timesMs: List<Long>): Coverage?   // null below two distinct points
fun maxBridgeMs(sortedTimesMs: List<Long>): Long
```

`segments()` is refactored onto `maxBridgeMs` so the gate and the drawn line can never disagree
about what a break is. The bridge stays `max(2 × median cadence, 30 min)`.

**Android — a fifth gate.** `metarCloudBreakReason` builds the same candidate set the blend uses
(`!qcFailed`, cloud-carrying, distinct timestamps — deliberately NOT `metarCloudGapReason`'s
OFFICIAL-only basis, because the blend does not filter by station type) and fires when
`coverage.breaks`. Ordered after `metarCloudGapReason`, so the broader "series is dead" verdict
still wins when both apply.

The `coverage_ok` line also gains `cloud_gap_min` / `cloud_bridge_min`. The incident was diagnosable
only via a device DB pull precisely because that line reported a healthy temperature figure while
the cloud curve was broken.

Not changed on purpose:
- **No visual bridging.** A 40-minute hole in an instantaneous sky reading is real until data arrives.
- **No fetching while the screen is off.** The hole is a consequence of the battery cadence; the
  repair is cheap because it waits for the next paint. Today's hole would have healed at 12:39.
- **Desktop is not wired.** The rule lives in `:shared` so it can adopt it, but its backfill
  scheduling is Android-side.

## Testing

- `:shared` `CloudActualSeriesTest`: a 40-minute hole breaks at a 10-minute median but NOT at a
  20-minute median (bridge 40); fewer than two points returns null; `segments()` behaviour unchanged.
- Android `HourlyObservationBackfillCloudGapTest`: `metarCloudBreakReason` fires on the Samsung's
  real 2026-09-03 carrier timestamps and stays silent on the emulator's; qc-failed and cloud-less
  rows are excluded from the basis.

## Verification on the device that had the bug

Same widget, same window, before and after installing the change:

```
15:24:09  OBS_HOURLY_BACKFILL_SKIP  reason=coverage_ok latest_gap_min=19 max_gap_min=23
15:27:21  OBS_HOURLY_BACKFILL_REQ   reason=cloud_series_break_min=40 bridge_min=30
15:27:37  OBS_HOURLY_BACKFILL_RUN   lookbackHours=72
15:27:37  OBS_HOURLY_BACKFILL_START start=2026-08-31T22:27Z end=2026-09-03T22:27Z
```

The fetch filled exactly the missing window — KSJC's five-minute ASOS samples at 11:40, 11:45,
11:50, 11:55, 12:00, 12:05, 12:10, none of which existed before:

```
before: 11:25 11:30 11:35            ...            12:15 12:25
after:  11:25 11:30 11:35 11:40 ... 12:10 12:15 12:20 12:25
```

Replaying the segmentation over the repaired rows: `points=161 median=5min bridge=30min
segments=1`, largest gap 5 minutes — one contiguous line, where before it was
`segments=2` split at 11:35→12:15.

Both new Android assertions were shown to fail before passing (forcing the gate's predicate to
`false` reverted all three `assertNotNull` cases).

## A fixture that was describing a broken curve

`HourlyObservationBackfillLocationTest.evenlySpacedFrom` placed its two stations five minutes apart
on each hour, so the merged series stepped 5, 55, 5, 55… — half the steps being the inter-station
offset rather than the reporting cadence. `CloudActualSeries` reads that as 5-minute cadence with
55-minute holes and shatters the line, so the fixture named "evenly spaced" was in fact a broken
curve that the old temperature-only check could not see. Both stations now report on the same marks.
