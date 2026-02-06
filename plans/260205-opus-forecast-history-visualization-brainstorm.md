# Forecast History Visualization - Brainstorm

## Current State

The current `ForecastHistoryActivity` shows two separate line graphs (high & low temps) plotting how a forecast for a single target date evolved over time. Issues:
- Two separate graphs split the story
- Bezier curves between sparse points can be misleading
- Hard to quickly grasp "was the forecast good or bad?"
- No way to see accuracy patterns across multiple days

## Available Data

Per target date, we have:
- **Forecast snapshots**: multiple `(forecastDate, highTemp, lowTemp, source)` entries captured at different lead times (e.g., 7 days out, 3 days out, 1 day out)
- **Actual observed**: high/low from NWS observations
- **Two sources**: NWS and Open-Meteo forecasts stored independently
- **Hourly data**: `HourlyForecastEntity` exists but only stores current/recent forecasts, not historical snapshots of hourly curves

---

## Visualization Ideas

### 1. Convergence Funnel (Improved Evolution)

**Concept**: Single combined graph showing how both high and low forecasts converge toward actual values as the target date approaches.

```
Temp
 75 |          ---- NWS High ----___
 72 |    ============================== Actual High (orange band)
 70 |              --- Meteo High --/
    |
 55 |         --- NWS Low ----___
 52 |    ============================== Actual Low (orange band)
 50 |            --- Meteo Low ---/
    |______________________________
     7d    5d    3d    1d    0d
         Days Before Target
```

**Pros**: Shows the "funnel" of convergence naturally; combines high/low into one view; actual values as bold horizontal bands give instant read on accuracy.

**Cons**: Can get busy with 4 lines + 2 bands. Could mitigate by letting user toggle high/low.

---

### 2. Error Ribbon Chart

**Concept**: Instead of plotting absolute temperatures, plot the *error* (forecast - actual) over time. Zero line = perfect forecast.

```
Error
 +5 |   *
 +3 |      *  NWS High
  0 |=========*=======*====  perfect
 -2 |              *
 -4 |                     *  NWS Low
    |______________________________
     7d    5d    3d    1d    0d
```

**Pros**: Immediately shows bias direction (consistently over/under-predicting); zero line is intuitive; error magnitude is the main story.

**Cons**: Loses the actual temperature context (user might want to know "what was the temp?", not just the error).

---

### 3. Accuracy Across Days (User's Idea #2)

**Concept**: Pick a fixed lead time (e.g., 24 hours ahead). Show a multi-day chart where each day is a column, with forecast vs actual comparison.

```
Temp
 80 |  F A   F A   F A   F A   F A
 75 |  | |   |*|   | |   | |   |*|
 70 |  |*|   | |   |*|   |*|   | |
 65 |  | |   | |   | |   | |   | |
    |______________________________
     Mon   Tue   Wed   Thu   Fri
```
Where F = forecast bar, A = actual bar, side by side.

**Pros**: Answers "how accurate is the 24h forecast in general?"; patterns emerge (e.g., consistently off on weekends, or during weather changes); user can switch between 24h/48h/72h lead times.

**Cons**: Needs enough days of data to be meaningful; separate view from single-day detail.

**Variant - Paired dot plot**: For each day, draw a dot for forecast and a dot for actual, connected by a vertical line. Line length = error. Very clean.

```
Temp
 78 |          A
 76 |     A    |         A
 74 |  F--+    F    F    |    A
 72 |  |            |    F    |
 70 |  A            A         F
    |______________________________
     Mon   Tue   Wed   Thu   Fri
```

---

### 4. Forecast vs Actual Scatter Plot

**Concept**: Each dot is one day. X-axis = forecast temp, Y-axis = actual temp. Perfect forecasts fall on the diagonal. Separate series for NWS/Open-Meteo.

```
Actual
  80 |          /
  75 |       * / *
  70 |     *  /  *
  65 |   *   / *
  60 |      /
     |________________
      60  65  70  75  80
           Forecast
```

**Pros**: Classic statistical visualization; instantly shows bias (dots above line = forecasts too low, below = too high); can include both highs and lows as different marker shapes.

**Cons**: Requires many data points to be useful; may feel "academic" for a weather widget; loses temporal ordering.

---

### 5. Daily Forecast "Report Card"

**Concept**: For a single day, show a clean summary card instead of a graph. Visual indicators rather than line plots.

```
 ┌─────────────────────────────────┐
 │  Wednesday, Feb 5               │
 │                                 │
 │  HIGH  Actual: 72°              │
 │        NWS:    74° (+2)   [██]  │  <- colored bar showing error
 │        Meteo:  70° (-2)   [██]  │
 │                                 │
 │  LOW   Actual: 55°              │
 │        NWS:    58° (+3)  [███]  │
 │        Meteo:  54° (-1)    [█]  │
 │                                 │
 │  Score: ★★★★☆  (NWS: 3.8/5)    │
 │                ★★★★★ (Meteo: 4.5/5) │
 │                                 │
 │  ▼ Evolution detail             │  <- expandable
 └─────────────────────────────────┘
```

**Pros**: Instantly readable; works even with sparse data; focuses on what matters (was it accurate?); expandable for detail.

**Cons**: Not as visual/fun as a graph; limited to one day at a time.

---

### 6. "Weather Tape" - Horizontal Timeline

**Concept**: Multi-day horizontal scrollable strip. Each day is a mini-card showing forecast vs actual with a color-coded background (green = accurate, yellow = close, red = off).

```
 ◄ [Mon ✓] [Tue ✓] [Wed ~] [Thu ✗] [Fri ✓] [Sat ✓] ►
     68/55    72/58    70/52    65/48    74/60    76/62
    +1/+0    +0/-1    +3/+2   -5/+1    +1/+0    +0/-1
```

**Pros**: Scannable at a glance; color coding gives instant feel; tapping a day opens detail; great for the "across many days" view.

**Cons**: Limited space per day; needs careful design to not feel cramped.

---

### 7. Dual-Area Overlay (if hourly snapshots existed)

**Concept**: If we stored historical hourly forecast curves, overlay the forecasted 24h temperature curve against the actual observed curve. The shaded area between = error.

```
Temp
 75 |      /---\
 72 |    /  ////\---\     Shaded area = error
 70 |  /-- ////      \--
 68 | /   //            \
    |______________________________
     6am  9am  12pm  3pm  6pm  9pm
```

**Pros**: Most intuitive and beautiful; shows exactly when the forecast was right/wrong during the day; immediately engaging.

**Cons**: Would require storing hourly forecast snapshots historically (schema change + more storage). Currently only high/low snapshots are retained.

**Worth considering**: Start storing hourly snapshots going forward for this feature. Storage cost is modest (~24 rows per source per forecast day).

---

## Recommended Approach

A **layered strategy** combining complementary views:

### Primary View: "Report Card" + Convergence (for single day)
When user taps a day's history:
1. **Top**: Clean report card showing forecast vs actual with error bars and score
2. **Bottom**: Convergence chart showing how forecast evolved (improved version of current graph, combining high+low into one chart)

### Secondary View: "Accuracy Over Time" (for multi-day trends)
Accessible from a tab or swipe:
1. **Paired dot plot** showing forecast vs actual across 7-30 days
2. Toggle for lead time (24h / 48h / 72h)
3. Summary stats (avg error, bias direction, % within 3 degrees)

### Future Enhancement: Hourly Overlay
Start storing hourly forecast snapshots now. Once a week+ of data accumulates, add the dual-area overlay as a premium detail view.

---

## Implementation Priority

| Priority | Visualization | Effort | Data Ready? |
|----------|--------------|--------|-------------|
| 1 | Report Card (single day) | Low | Yes |
| 2 | Convergence Funnel (single day) | Medium | Yes |
| 3 | Accuracy Over Time dot plot (multi-day) | Medium | Yes |
| 4 | Error Ribbon (single day) | Low | Yes |
| 5 | Weather Tape (multi-day summary) | Medium | Yes |
| 6 | Hourly Overlay | High | No (schema change) |
| 7 | Scatter Plot | Low | Yes |
