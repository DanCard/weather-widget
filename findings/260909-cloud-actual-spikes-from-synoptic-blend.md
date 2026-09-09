# Findings: cloud actual spikes on the desktop (Silurian + Synoptic actuals)

Date: 2026-09-09
Modules: `:shared` (blend), `:desktop` + `:app` (rendering)
Status: **B (above-ceiling deck persistence) implemented and merged.** A and C were tried and
parked on their own branches.

| Experiment | Branch | Result |
|---|---|---|
| A — median de-spike of the emitted series | `cloud-actual-despike-median` (`4a8de628`) | spikes gone, 44 % plateau remains — rejected |
| C — line = IDW total instead of `max(bands)` | `cloud-actual-idw-total` (`580dd8d6`) | plateau gone, single-point spikes remain — rejected |
| **B — an above-ceiling deck persists across its station's anchor window** | merged to `main` | staircase, no spikes — **chosen** |

## Report

Desktop, `weatherSource = SILURIAN`, `actualsProviders.SILURIAN = SYNOPTIC`, cloud-cover view.
The solid actual line read as a square-wave: flat ~0–2 %, narrow vertical spikes to 19/44/75 %,
plus one wide 44 % block. Expected: a smooth line like the dashed forecast.

## Evidence (all measured, none inferred)

1. **Screenshot** `/tmp/ww-cloud-20260909.png` (window `0x06000007`, 1289×874): actual line flat
   near the axis with 6–7 narrow spikes and one ~50-minute plateau at 44 %.
2. **The exact series the app reads.** A temporary harness calling
   `DesktopWeatherDao.getCloudActuals(..., "SILURIAN")` against the live
   `~/.local/share/weather-widget/weather.db`:

   ```
   stats: stationsWithLayers=4 stationsSkipped=9 metarPreferred=414 ceilometerBlind=8
          blendWidth=[w1=3 w2=5 w3=130]  points=138
   runs:
     04:00–05:50   0 %
     05:53–06:47   1 %
     06:50–07:40  44 %   <-- 11 points / 50 min
     07:45–07:50   2 %
     07:53        75 %   <-- 1 point
     08:53        75 %   <-- 1 point
     09:53        44 %   <-- 1 point
     10:53        19 %   <-- 1 point
     10:55–11:50   1 %
     11:53,12:53  19 %   <-- 1 point each
     12:55–13:50   1 %
     13:53–13:55  19 %   <-- 2 points (right edge / NOW)
   ```

   The series is 5-minute cadence (KSJC's own reporting rate); 138 points over 11 h.
3. **The stations behind it** (`api='SYNOPTIC'`, same window):
   - `KNUQ` 3.8 km — nearest — `CLR` on every 20-minute report all day (`isMetar=1`).
   - `KPAO` 6.1 km — `SKC` hourly.
   - `KSQL` 18 km — `CLR` ~20-min.
   - `KSJC` 15.9 km — `FEW070 FEW095` / `SCT070 SCT110` every 5 min, and an hourly METAR at
     `:53` that additionally carries `FEW130` / `SCT130` / `BKN130` (13,000 ft ≈ 3,962 m).
4. **Bands at the plateau** (same harness): `06:50–07:40 low=2 mid=44 high=null`. The low band
   is the IDW of every station's low layer (KNUQ 0 dominates ⇒ 2); the mid band is KSJC alone
   (clear stations report no mid layer) ⇒ 44.

## Root cause — two blend mechanisms, not a rendering bug

### 1. The 50-minute 44 % plateau: `pointValues = max(blended bands)`

`MetarCloudBlender.blend` (commit `4bab4389`, 2026-08-27) overwrites the IDW total with the max
of the blended bands:

```kotlin
listOfNotNull(layers.low, layers.mid, layers.high).maxOrNull()?.let { bandMax ->
    pointValues[ts] = bandMax
}
```

At `06:50–07:40` the IDW total of per-station totals is **2 %** (KNUQ CLR 3.8 km holds ~90 % of
the weight) but the blended *mid* band is **44 %** (only KSJC reports mid), so the line is 44.
`4bab4389` deliberately chose this to keep the line consistent with the `m` glyph — and because
the line then equals the band, `suppressMatchingTotal` suppresses the glyph, so the 44 % has no
on-screen explanation. When KSJC's 5-min report drops the 110-hundred-foot layer at 07:45 the
mid blend vanishes and the line snaps back to 2 %: a 42-point step from a layer crossing at a
station 16 km away.

### 2. The hourly single-point spikes: an above-ceiling layer that only the METAR reports

The 13,000 ft deck is invisible to every ASOS ceilometer (hard 12,000 ft ceiling), so it appears
**only** in KSJC's hourly `:53` METAR — the 5-minute rows either side of it report just the
sub-ceiling layers. Under the max-of-bands rule that one candidate's mid band (75/44/19) becomes
the whole line for one point, then disappears again. `blendWidth` `w1=3` are exactly those
points: a single contributing station.

`CeilometerBlindSpot.filterBlindClears` fires at the same candidate (it drops KNUQ's automated
`CLR` because a sibling reports above the ceiling), but that is *not* what makes the line spike:
it only raises the low band at that point, and the line is the max of the bands, so the mid band
was going to win anyway. The per-candidate evaluation is still the reason the exclusion lasts one
point instead of the hour, which is the "known roughness" recorded in
`plans/260827-observation-site-merge-for-actual-series.md:191`:
"the rule toggles … so the curve steps rather than eases … Whether an above-ceiling deck should
persist across buckets — cloud aloft does not vanish between one station's hourly reports — is a
real question and is left open."

### 3. Rendering turns the data steps into square spikes

Both renderers draw the actual as a straight polyline, deliberately unsmoothed
(`CloudActualSeries.segments` → `moveTo`/`lineTo`):
- desktop `CloudCoverGraph.kt`: "Straight, timestamp-positioned segments … No smoothing: it would
  invent values";
- Android `CloudCoverGraphRenderer.kt:459-463`.

The forecast curve, by contrast, goes through `SeriesSmoothing` + `buildCurve` (bezier). So the
forecast looks smooth and the actual looks square.

## Experiments

### A — median de-spike (parked)

New shared `CloudActualDespike`: a candidate that is not the median of itself and its two
neighbours is replaced by the median-valued neighbour, value and bands together; 30-minute
neighbour gap, neighbours must agree within 10 points, endpoints untouched. Removed all 7
single-point spikes on the reported window but left the 44 % plateau, and it rewrites measured
values — **not kept**.

### C — line = IDW total (parked)

Removed the max-of-bands override so the line is the IDW blend of the stations' totals. The 44 %
plateau disappeared (line ≈ 1–2 %), but the single-point :53 spikes remained and band glyphs can
now sit above the line — **not kept**.

### B — above-ceiling deck persistence (chosen)

A report whose sky condition sits **above** the ASOS ceiling now anchors its station's bands for
the whole anchor window, not just its own candidate:

```kotlin
val aboveCeilingCarriersByStation = byStation.mapValues { (_, rows) ->
    rows.filter { row ->
        val base = CeilometerBlindSpot.highestReportedBase(row) ?: return@filter false
        base > CeilometerBlindSpot.ASOS_CEILING_M && (row.visibleCloud() ?: 0) > 0
    }
}
```

and `blendedLayer` takes, per station, the max band value over the anchored reading and any
above-ceiling report within `ANCHOR_TOLERANCE_MS`. `Stats.stickyDeckPoints` surfaces the count as
`stickyDeck=N` in `CLOUD_SERIES`.

Measured on the reported window (`stickyDeck=90`):

```
04:00–05:50  0 %
05:53–06:47  1 %
06:50–07:20  44 %
07:25–09:20  75 %
09:25–10:20  44 %
10:25–14:15  19 %
```

No spikes. The line now reads as a staircase at KSJC's reported sky, which is the deliberate
trade-off: the 16 km station's deck is trusted for its hour because the 3.8 km ceilometer
physically cannot see it. Desktop screenshot: `/tmp/ww-cloud-sticky-deck-20260909.png`.

## Still open (not addressed here)

- **D** — draw the actual with the forecast's curve/smoothing treatment so the remaining steps
  ease rather than snap (display-only).
- The line still shows a 75 % block for two hours while the nearest station reports clear. A
  per-band visibility rule (a clear station votes 0 only for layers below its ceiling) would keep
  the low/mid vote while still letting the above-ceiling deck show; bigger change.
