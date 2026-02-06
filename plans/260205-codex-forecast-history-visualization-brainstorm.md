# Forecast History Visualization Brainstorm (2026-02-06)

Good direction. I’d implement this as two graph modes in `ForecastHistoryActivity`:

1. `Forecast Evolution (single target day)`
- X-axis: `forecast issued time` (e.g., 7d → 1d before target day).
- Y-axis: temperature.
- Lines:
  - Forecasted high (or low) at each issue time.
  - Actual high/low as a flat reference line.
- Visual cue: shaded error band between forecast and actual.
- Why it works: user sees convergence (or drift) as the day approaches.

2. `Fixed Lead Accuracy (e.g., 24h ahead)`
- X-axis: target day (last 30 days).
- Y-axis: signed error (`forecast - actual`) or absolute error.
- Bars centered at 0:
  - Above 0 = over-forecast.
  - Below 0 = under-forecast.
- Overlay 7-day rolling MAE line.
- Why it works: makes day-to-day 24h skill obvious, with trend.

I’d also add a third optional view if you want fast insight:

3. `Lead-Time Skill Curve`
- X-axis: lead time bucket (`12h`, `24h`, `48h`, `72h`, etc.).
- Y-axis: MAE.
- One line per source (`NWS`, `Open-Meteo`).
- Why it works: instantly shows “which API is better at what horizon.”

Design details to make it feel good:
- Toggle chips: `Evolution | 24h Accuracy | Skill Curve`.
- Separate `High` and `Low` tabs (avoid cluttered dual-axis).
- Show median + p90 error summary above graph.
- Keep colors semantic: actual=`neutral`, over-forecast=`warm`, under-forecast=`cool`.
- Tap point shows: issue time, predicted, actual, error.

If you want, I can sketch exact chart specs (data queries + rendering rules) for each mode using your current entities (`ForecastSnapshotEntity`, `WeatherEntity`).
