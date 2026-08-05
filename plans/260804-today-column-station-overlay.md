# Today Column Station Overlay

## Gating

Only active when ALL of:
1. Widget is **4+ rows** tall (ample vertical space)
2. Widget is **wide enough** to normally show 10+ columns → reduced to 9 columns
   with a widened today column
3. User setting `detailedHeader` = true
4. View mode = DAILY (graph mode only — no bars in text mode)

Same gates apply on desktop (composables have direct access to `DesktopWidgetDimensions`).

## Today Column Widening

```
Normal:  [TODAY] [ D2 ] [ D3 ] [ D4 ] [ D5 ] [ D6 ] [ D7 ] [ D8 ] [ D9 ] [D10]  ← 10 cols
Wide:    [  TODAY  ] [ D2 ] [ D3 ] [ D4 ] [ D5 ] [ D6 ] [ D7 ] [ D8 ] [ D9 ]      ← 9 cols
```

- Today column width multiplier tuned so it doesn't feel off-balance (~1.3–1.5×)
- Neighbour columns compress proportionally to absorb the difference
- The today panel's frosted-glass bounds widen with the column
- **Today triple bars thinned** from 8dp → 6dp (`TODAY_TRIPLE_BAR_WIDTH_DP_COMPACT = 6f`)
  so the widened column doesn't look bottom-heavy — proportion restored

Implemented via a `todayColumnWidthMultiplier` and `useCompactBars` field on
`DailyGraphLayoutInfo`, computed during `DailyGraphLayoutResolver.resolve()`.

## Empty-Space Finding Algorithm

### Column anatomy (top → bottom)

```
 ┌ panel top ═══════════════════════════════╗
 │                                          ║  frosted-glass panel
 │  (ghost line extension, if present)      ║
 │  "88°"  ← high temp label               ║
 │     │                                     ║
 │  ┌──│──┐  ← snapshot bar (left flank)    ║
 │  │  │  │  ← thermostat bar (center)      ║  bar body
 │  └──│──┘  ← forecast bar (right flank)   ║
 │     ●     ← bulb                          ║
 │    ☀️     ← icon                          ║
 │   "62°"  ← low label                      ║
 │  "Today" ← day label                      ║
 └──────────────────────────────────────────╝
```

### Candidate zones (ranked by preference)

| Zone | Range | Pros | Cons |
|------|-------|------|------|
| **A** — Above bars | panelTop → top of ghost/high-label | Most breathing room, no data obscured | Needs panel extended upward; can collide with high label on hot days (narrow range = label near panel top) |
| **B** — Below bulb, above icon | bulbBottom → iconTop | Good vertical space, clear from bars | Disconnected from "current temp" context visually |
| **C** — Mid-bar overlay | ~30%→70% of bar height | Always available, close to "current temp" meaning | Text on top of a colored line — readability at risk |
| **D** — Between high-label and bar-top | highLabelBottom → barTop (highY) | Tiny but close to context | Usually only a few dp; rarely usable |

### Scoring function

For each zone, compute:

```
score(zone) = availableHeightPx * w_height
            + (distanceToNearestElementPx) * w_margin
            - (isOverlay ? 1.0 : 0.0) * w_overlay_penalty
```

Where `w_height >> w_margin >> w_overlay_penalty`. In practice this means:
1. First attempt Zone A — if ≥ 28dp (two 11sp lines + gap), use it
2. Fall back to Zone B
3. Zone C only as last resort

If no zone has ≥ 20dp (one line only), show nothing.

### Zone sizing

**Zone A** (above bars):
```
zoneTop = panelTop + 4dp padding
zoneBottom = min(
    ghostLineTop,        // ghost line upper extent, or
    highLabelTopY - 4dp  // below the high temp label
)
spaceAvailable = zoneBottom - zoneTop
```

**Zone B** (between bulb and icon):
```
zoneTop = bulbCenterY + bulbRadius + 2dp
zoneBottom = iconTopY - 2dp
spaceAvailable = zoneBottom - zoneTop
```

**Zone C** (mid-bar overlay):
```
zoneTop = highY + (lowY - highY) * 0.15   // 15% down from bar top
zoneBottom = lowY - (lowY - highY) * 0.15  // 85% down, avoid bulb
spaceAvailable = zoneBottom - zoneTop
```

### Text layout within chosen zone

Two lines, centered horizontally in the today column:
- **Line 1**: `"74.1° · 12m"` — station's raw temperature + age in minutes (~11sp)
- **Line 2**: `"vs yest +3.2°"` — day-over-day delta (~9sp)

When only one line fits: show just Line 2 (delta). When nothing fits: omit entirely.

Font color: matching header text color (`0xAAFFFFFF` — translucent white).

Station name is intentionally omitted — it's mostly stagnant and clutters the overlay.

### Day-over-day delta

```
delta = todayCurrentTemp - yesterdayObservedTemp
```

Where `yesterdayObservedTemp` is yesterday's current-temperature observation
at approximately the same hour, from the same display source. Falls back to
yesterday's daily high if no same-hour reading exists.

## Implementation Steps

### 1. Data plumbing — track dominant station during blend

In `ActualTemperatureSeriesBuilder.blendObservationSeries()`:
- Track `topWeightStationRawTemp: Float?` and `topWeightStationAgeMs: Long?` —
  the raw temperature and age of the station with highest individual weight
  at the most recent emitted point
- Return via new fields on `BlendObservationStats`
- Cheap — just a couple comparisons per timestamp, no full breakdown capture

### 2. Bubble through resolution chain

- `ActualsAggregator.resolveCurrentObservation()` → adds dominant station fields to return
- `CurrentTempResolver` (app module) → passes through `ObservationData`
- `DailyViewHandler` → `DailyGraphRenderer` → `DailyForecastGraphRenderer`

### 3. Day-over-day delta

Already available: `dailyActuals[yesterday]` in `DailyViewHandler`. The delta:
```
delta = todayCurrentTemp - yesterdayObservedTemp
```
Uses yesterday's observed temperature at approximately the same hour.

### 4. Settings

No setting — always active when gating conditions met. Removes the preference
toggle complexity and lets the gating (4+ rows, 10+ cols) control visibility.
Simple: wide tall widget gets it, small widgets don't.

### 5. Rendering changes

- `DailyGraphLayoutResolver`: compute `todayColumnWidthMultiplier`, adjust column count
- `TodayColumnHighlight.panelBounds()`: accept wider column geometry
- `DailyBarRenderer`: when compact mode active, use thinner stroke width (6dp vs 8dp) for
  today triple bars via `layout.useCompactBars`
- New `TodayColumnStationOverlay` object: implements the zone-finding algorithm and draws text
- `DailyForecastGraphRenderer.renderGraph()`: after drawing today bars + panel, call overlay
- `DailyGraphRenderResult`: add overlay debug info
- Desktop: same logic in `DailyForecastGraph` composable

### 6. Desktop

- Same gating (4+ rows, 10→9 cols, setting enabled, DAILY mode)
- Same zone-finding algorithm, ported to Compose Canvas drawing
- Same config key in `DesktopConfig`
