# Today column's "yesterday's forecast" bar differs between desktop and emulator (Open-Meteo)

2026-10-02, 01:30. Report: on Open-Meteo, the today column's left (snapshot) bar on desktop doesn't
match the emulator.

## Verdict

Not a code divergence. The two devices have different Open-Meteo fetch histories, so the shared rule
picks a different stored forecast on each. This is the case the 2026-09-25 decision covers: keep
showing the older forecast, drawn dashed when it is over 48h old.

## The rule

Shared `DailySnapshotSelector.selectPriorDaySnapshot`: the newest stored forecast for today that
was fetched more than 24h ago (fallback: the earliest one). At 01:30 the cutoff is **10-01 01:30**.
`DailySnapshotSelector.isStale` dashes it when the last confirmation is over 48h old (Android judges
from `batchFetchedAt`, desktop from `fetchedAt`, since desktop writes a row per fetch).

## What each device picked

| | Forecast | Fetched | Age | Bar |
|---|---|---|---|---|
| Emulator | **87° / 66°** | 09-29 11:57 | 61.5h | dashed (over 48h) |
| Desktop | **86° / 66°** | 09-30 03:41 | 45.8h | solid |

The emulator fetched no Open-Meteo data between 09-29 11:57 and 10-01 15:57 (the same two-day gap
as its NWS history). Desktop fetched on 09-30, so it has a newer forecast that is still over 24h
old. Each device shows the most recent "yesterday's forecast" it actually has.

## How it settles

- **Desktop, from 01:58:** its 10-01 01:58 forecast (77° / 55°) crosses 24h and becomes the bar.
- **Emulator, from 15:57:** its 10-01 15:57 forecast (79° / 57°) crosses 24h; the bar turns solid.
- The two match once both fetch Open-Meteo regularly. Between 01:58 and 15:57 they still differ,
  because desktop has fetches the emulator lacks.

## How to diagnose this again

Query both DBs for today's rows of the source and compare fetch times against `now - 24h`:

```sql
SELECT highTemp, lowTemp,
       datetime(fetchedAt/1000,'unixepoch','localtime') fetched,
       datetime(batchFetchedAt/1000,'unixepoch','localtime') batch
FROM forecasts
WHERE source='OPEN_METEO'
  AND targetDate = CAST(strftime('%s','2026-10-02') AS INTEGER)*1000  -- UTC midnight, no 'localtime'
  AND abs(locationLat-37.417) < 0.05
ORDER BY fetchedAt;
```

Same rule, different input: when a shared selector gives different results on two devices, compare
the inputs (fetch times) before reading rendering code.
