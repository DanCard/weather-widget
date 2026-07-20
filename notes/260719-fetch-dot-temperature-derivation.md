# Fetch-"now" dot: how the temperature is calculated

Worked example: Samsung SM-F936U1 (`RFCT71FR9NT`), 2026-07-19 21:09 local, widgets 345/349,
display source NWS, dot showing **61.6°**.

## TL;DR

The dot is **not any station's reading**. Every raw NWS observation near 20:50 was between 62.6°
and 66.2°, yet the dot read 61.6° — *below all of them*. That is the design working, not a bug:
stale station readings are carried forward along the **forecast's slope**, and on a fast-falling
evening curve the blend legitimately lands under every raw reading.

## Call chain

| Step | Location |
|---|---|
| Widget picks the observation | `WidgetRenderer.kt:186` (`graphStyleObs`) |
| Graph-style resolver | `CurrentTempResolver.resolveGraphStyleCurrentTempFromInputs` |
| Aggregator entry | `ActualsAggregator.resolveCurrentObservation` (`ActualsAggregator.kt:37`) |
| The actual math | `ActualTemperatureSeriesBuilder.blendObservationSeries` (line 211) |
| Rendered as the dot | `WidgetRenderer.kt:257` → `lastObservedTemp` |

`resolveCurrentObservation` blends the window, then takes the **latest blended point at or before
now** whose `condition == "observed"`. At the 21:09 render that was the **20:50** candidate.

## Step 1 — candidate timestamps are event-sampled

Candidate timestamps come from raw observation rows, not a fixed grid
(`candidateTimes = filtered.map { it.timestamp }.distinct().sorted()`). 20:50 exists as a candidate
**only because KSJC reported then**. Every other station is resolved *to* 20:50 by
`resolveStationValueAt` (line 468).

## Step 2 — forecast-carried extrapolation

A station with no reading at the target gets its last reading shifted by the **forecast's** change
over the gap (`extrapolateForward`, line 525):

```
value = lastReading + (forecast(target) − forecast(lastReadingTime))
```

Guard: `MAX_EXTRAPOLATION_GAP_MS` = 3h; beyond that the station drops out entirely.

NWS hourly at the widget site (37.417, −122.089): 19:00 = 72°, 20:00 = 68°, 21:00 = 65°.
Interpolated, **forecast(20:50) = 65.5°**. The curve is falling ~3°/hr, so every stale station is
dragged downward — KPAO's 19:47 reading of 62.6° resolves to **59.2°**.

## Step 3 — IDW blend

`blendCandidateTemperature` (line 440):

```
weight = typeWeight × timeDecay / distanceKm²
timeDecay = 1 − age / BLEND_MAX_AGE_MS      (BLEND_MAX_AGE_MS = 3h, linear to zero)
typeWeight = 1.0 (OFFICIAL) | personalStationWeight (PERSONAL)
blend = Σ(w·T) / Σw
```

Device setting: `personal_station_discount = 95` (`widget_state_prefs.xml:87`)
→ `personalStationWeight = 0.05`.

| station | raw | resolved | age | decay | weight | share |
|---|---|---|---|---|---|---|
| KSJC (15.9 km) | 66.2 | 66.200 *observed, exact hit* | 0 | 1.000 | 0.00394 | 4% |
| AW020 (2.2 km, **PWS**) | 64.0 | 63.754 | 5 m | 0.972 | 0.00984 | 11% |
| **KNUQ (3.8 km)** | 62.6 | **61.600** | 20 m | 0.889 | **0.06100** | **66%** |
| KPAO (6.1 km) | 62.6 | 59.233 | 63 m | 0.650 | 0.01772 | 19% |
| LOAC1 (8.3 km, **PWS**) | 65.0 | 62.994 | 40 m | 0.778 | 0.00056 | 0.6% |

```
Σ(w·T) / Σw = 5.73044 / 0.0930567 = 61.58  →  displayed "61.6"
```

Matches the device's own `TEMP_ACTUALS_DEBUG` line
(`emit t=20:50 blended=... stationCount=5`) and `CURR_STALE_DEBUG temp=61.6 obsAt=20:50`.

## Things that surprised me

- **The 95% PWS discount dominates the result.** AW020 is the *closest* station at 2.2 km — its raw
  1/d² weight is ~50× KSJC's — but the discount cuts it to 11% of the vote.
- **1/d² is brutal.** KSJC at 15.9 km gets 4% of the vote despite being the only *true* observation
  in the set (everything else is extrapolated).
- **Position and value come from different stations.** The dot sits at 20:50 purely because KSJC —
  the farthest station, 4% of the weight — happened to report then. The height is essentially
  KNUQ's extrapolated 61.6°.
- **A blend below every raw reading is expected** during a fast drop. "No station reads 61.6" is
  not evidence of a bug.

## How to re-derive this on any device

```bash
python3 scripts/backup_databases.py
DB=backups/<latest>_<device>/databases/weather_database

# What the dot resolved to, and from when
sqlite3 "$DB" "SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
  FROM app_logs WHERE tag='CURR_STALE_DEBUG' ORDER BY timestamp DESC LIMIT 5;"

# Which stations fed the blend
sqlite3 "$DB" "SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
  FROM app_logs WHERE tag='IDW_BLEND' ORDER BY timestamp DESC LIMIT 5;"

# The raw rows (note: column is locationLat/locationLon, and api='NWS' not source=)
sqlite3 -header -column "$DB" "SELECT stationId,
  datetime(timestamp/1000,'unixepoch','localtime') obs, temperature,
  round(distanceKm,3) km, stationType,
  datetime(fetchedAt/1000,'unixepoch','localtime') fetched
  FROM observations WHERE api='NWS' AND timestamp > <epochMs> ORDER BY timestamp DESC;"

# The forecast that drives extrapolation — pick the row matching the widget's dataLoc
sqlite3 -header -column "$DB" "SELECT datetime(dateTime/1000,'unixepoch','localtime') t,
  temperature, locationLat, locationLon FROM hourly_forecasts
  WHERE source='NWS' AND dateTime BETWEEN <a> AND <b> ORDER BY dateTime;"
```

`TEMP_ACTUALS_DEBUG` `emit` lines are **throttled samples** (`throttleMs=50`, typically 8 of ~978
lines survive per render), so the exact timestamp you want is usually absent — reconstructing the
arithmetic is often faster than waiting for the log line.

## Follow-up: resolved

The dot and the graph run the *same* blender over **different context windows** — the dot uses 12h
back / 3h ahead (`CurrentTemperatureResolver.buildCurrentTempResolutionWindow`), the graph uses
72h / 60h (`WeatherWidgetProvider.HOURLY_LOOKBACK_HOURS`/`_LOOKAHEAD_HOURS`), so the ~0.5° gap
between the logged dot value and the logged `emit` line looked like a window effect.

**It isn't.** Driving the real device rows through both windows gives `61.580162` either way — the
dot's value is identical at every context width from 2h to 72h. The audit did find a genuine
leading-edge artifact (up to 5.5°F in the first hour of a narrowly-*queried* range), but the dot
reads the trailing edge and is unaffected. See
[`260719-blend-window-independence-audit.md`](260719-blend-window-independence-audit.md).
