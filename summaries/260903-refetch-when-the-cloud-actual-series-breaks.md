# Re-fetch observations when the cloud actual series breaks

**Date:** 2026-09-03
**Plan:** [plans/260903-refetch-when-the-cloud-actual-series-breaks.md](../plans/260903-refetch-when-the-cloud-actual-series-breaks.md)

## The incident

The NWS actual-cloud line on the Samsung broke between **11:35 and 12:15**. The user walked to a
basketball court at ~11:59 and the phone sat in a pocket, screen off, until 12:39:

```
11:53:31  GPS_RESAMPLE  outcome=same_site      trigger=screen_on  37.4168,-122.0890  (home)
11:59:14  GPS_RESAMPLE  outcome=location_moved trigger=screen_on  37.4241,-122.0883  (the court)
11:59:50  OBS_HOURLY_BACKFILL_SKIP  reason=coverage_ok latest_gap_min=12 max_gap_min=15
12:02:01  GPS_RESAMPLE  outcome=same_site      trigger=screen_on
          ← 37 minutes: no paint, no sync, no poll
12:39:05  GPS_RESAMPLE  outcome=opportunistic … SYNC_START
```

Follow-device worked exactly as designed — it saw the move and applied it immediately. Then the
screen went off. **The routine observation poll is paint-driven and stores each station's *latest*
report only** (five rows per run), so poll frequency IS stored density and a poll that never runs is
data lost for good. KNUQ's 11:55 report and KSJC's 11:40–12:20 five-minute ASOS samples were never
fetched, and `CloudActualSeries.segments` split the line correctly over the 40-minute hole.

The court fragment (37.424, ~780 m away) is *not* the cause: `ObservationSiteMerge`'s 0.01°
tolerance merges it, and rows from all three device-site fragments were present in the union.

**The emulator is not the reference.** It looked contiguous only because its full station-history
sweep happened to run at 13:43, after the fact; the Samsung's ran at 00:11.

## Why the existing repair did not fire

`HourlyObservationBackfill` already runs on every paint and can request exactly the fetch that heals
this — `backfillRecentNwsObservations` pulls `/stations/{id}/observations?start&end` and REPLACEs.
It declined, logging `coverage_ok latest_gap_min=8 max_gap_min=23`. None of its four gates could see
a cloud-only hole:

| gate | blindness |
|---|---|
| `maxGapMin > 75` | Measured over **all** observation timestamps. AW020/LOAC1/KSJC push temperature rows every 5–10 min, so the union's worst hole was 23 min while the cloud-carrying subset's was 40. Wrong subset, and the threshold is 2.5× the bridge besides. |
| `latestGapMin > 45` | Trailing edge only — how stale the newest row is. Interior holes invisible by construction. |
| `metarCloudGapReason` | Cloud-aware but a **ratio over the whole window**; its own doc says it detects "a broken *series*, never a single station that has stopped contributing". One hole never moves it below half. |
| `todayStartUncovered` | About a truncated window start. |

The renderer computed the break and drew it. The backfiller never asked.

## Change

**`:shared` — one owner for the bridge rule.** `CloudActualSeries` gains `maxBridgeMs(times)` and
`coverage(times) → Coverage(largestGapMs, bridgeMs, breaks)`. `segments()` is refactored onto
`maxBridgeMs`, so "the line broke" and "we should re-fetch" cannot drift apart. The rule is
unchanged: `max(2 × median cadence, 30 min)`.

Duplicates are collapsed before measuring cadence — several stations report on one timestamp, and
the blend emits one candidate point per DISTINCT time. Counting those zero-length steps would drag
the median to zero, floor the bridge at 30 minutes for every series, and make a genuinely sparse
hourly location look permanently broken.

**Android — a fifth gate.** `metarCloudBreakReason` builds the blend's own candidate set
(`!qcFailed`, cloud-carrying, distinct timestamps — deliberately NOT `metarCloudGapReason`'s
OFFICIAL-only basis, because the blend filters no station types) and fires when `coverage.breaks`.
Ordered after `metarCloudGapReason`, so the broader "series is dead" verdict still wins when both
apply. The `coverage_ok` line also gained `cloud_gap_min` / `cloud_bridge_min`: this incident was
diagnosable only via a device DB pull precisely because that line reported a healthy temperature
figure while the cloud curve was split.

Deliberately not changed:

- **No visual bridging.** A 40-minute hole in an instantaneous sky reading is real until data arrives.
- **No fetching while the screen is off.** The hole is a consequence of the battery cadence; the
  repair is cheap because it waits for the next paint. Today's hole would have healed at 12:39.
- **Desktop not wired.** The rule lives in `:shared` so it can adopt it; its backfill scheduling is
  Android-side.

## Verification on the device that had the bug

Same widget, same window, before and after install:

```
15:24:09  OBS_HOURLY_BACKFILL_SKIP  reason=coverage_ok latest_gap_min=19 max_gap_min=23
15:27:21  OBS_HOURLY_BACKFILL_REQ   reason=cloud_series_break_min=40 bridge_min=30
15:27:37  OBS_HOURLY_BACKFILL_RUN   lookbackHours=72
15:27:37  OBS_HOURLY_BACKFILL_START start=2026-08-31T22:27Z end=2026-09-03T22:27Z
```

The fetch filled exactly the missing window — KSJC's five-minute samples at 11:40 … 12:10, none of
which existed before:

```
before: 11:25 11:30 11:35            …            12:15 12:25
after:  11:25 11:30 11:35 11:40 … 12:10 12:15 12:20 12:25
```

Replaying the segmentation over the repaired rows: `points=161 median=5min bridge=30min
segments=1`, largest gap 5 minutes — one contiguous line, where an hour earlier it was `segments=2`
split at 11:35→12:15.

Tests: 6 new in `CloudActualSeriesTest`, 6 new in `HourlyObservationBackfillCloudGapTest`. All three
new `assertNotNull` cases were shown to fail first (forcing the gate's predicate to `false`). Full
`:app`, `:shared` and `:desktop` unit suites green.

## A fixture that was describing a broken curve

`HourlyObservationBackfillLocationTest.evenlySpacedFrom` placed its two stations five minutes apart
on each hour, so the merged series stepped 5, 55, 5, 55… — half the steps being the inter-station
offset rather than the reporting cadence. `CloudActualSeries` reads that as 5-minute cadence with
55-minute holes and shatters the drawn line, so a fixture named "evenly spaced" was in fact
describing a broken curve that the old temperature-only check could not see. Both stations now
report on the same marks.

That also names the shape most likely to over-fire in the wild: a location with exactly two official
stations, both hourly, offset by a few minutes. Its curve genuinely *is* shattered on screen, so
re-fetching is defensible, and the existing 30-minute per-source-and-site cooldown bounds it. If it
should never fire there, the fix belongs in the cadence rule itself — measure per-station spacing
rather than the merged series — which would change rendering too. Left alone.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/graph/CloudActualSeries.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/HourlyObservationBackfill.kt`
- `shared/src/test/kotlin/com/weatherwidget/shared/graph/CloudActualSeriesTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/HourlyObservationBackfillCloudGapTest.kt`
- `app/src/test/java/com/weatherwidget/widget/handlers/HourlyObservationBackfillLocationTest.kt`
