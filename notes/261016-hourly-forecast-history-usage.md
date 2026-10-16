# Usage and Purpose of Hourly Forecast History

## 1. Overview & Problem Context

In both Android (`:app`) and Linux Desktop (`:desktop`), the primary hourly table (`hourly_forecasts`) stores only the **latest active forecast** for each hour. Its primary key is `(dateTime, source, locationLat, locationLon)` and updates occur via `OnConflictStrategy.REPLACE`.

Because of this design:
- Once an hour passes or a new forecast is fetched, previous predictions are overwritten.
- Past hours quickly age out of the live table or are replaced by updated issuance runs.
- Without historical preservation, the app cannot know what was predicted at an earlier time (e.g. yesterday's forecast for today), making accuracy tracking, historical curve drill-downs, and day-ago comparisons impossible.

To resolve this, the project maintains the `hourly_forecast_history` table (represented by `HourlyForecastHistoryEntity` on Android and managed via `HourlyForecastHistoryDao` / `DesktopWeatherDao`).

---

## 2. Storage & Cadence Model

`HourlyForecastHistoryEntity` mirrors the full payload of `HourlyForecastEntity` (`temperature`, `condition`, `precipProbability`, `cloudCover`, `precipAmountMm`, layer clouds, etc.), but includes `timestampToGroupPredictions` as part of its primary key:

```sql
PRIMARY KEY (dateTime, source, locationLat, locationLon, timestampToGroupPredictions)
```

- **Cadence Capping**: To prevent unbounded growth from frequent background syncs, snapshots are aligned to cadence buckets via `ForecastHistoryPolicy`:
  - **4 hours** for the primary source (e.g., NWS).
  - **8 hours** for non-primary sources.
- Fetches within the same bucket collapse via `REPLACE`, keeping the latest values for that window while creating new history records when crossing bucket boundaries.

---

## 3. Key Use Cases

### 1. Reconstructing Past Hourly Curves & Historical Drill-Down
- **Daily View & Months View Drill-Down**: The app allows navigating backwards across past days (up to 18 months in the Months View). When tapping or zooming into a past day, the live forecast table has no records for that elapsed period.
- **Unified Stitching**: `HourlyForecastStitcher` (called by `HourlyForecastLoader` on Android and `DesktopWeatherDao.getHourlyWithHistory` on Desktop) stitches live rows with `hourly_forecast_history`. 
- **Freshest Forecast Policy**: For past hours, the stitcher selects the most recently issued snapshot (`maxByOrNull { fetchedAt }`) to present the highest-accuracy, final forecast published for that hour rather than an outdated 7-day-out prediction.

### 2. Forecast Accuracy Tracking & Evaluation
- **Lead-Time Accuracy**: Classes like `RainAccuracyCalculator` and `AccuracyCalculator` evaluate how well weather models performed by querying the forecast that was captured during the prior calendar day (the "1-day-ahead" prediction bucket window).
- **Predictions vs. Actuals**: Compares predicted temperatures, precipitation amounts, and day/night rain probabilities against recorded weather station observations (`ObservationEntity`).
- **Evolution Inspection**: Powers the Forecast History activity/viewer to visualize how predictions shifted across model runs leading up to an event.

### 3. Freezing Prior-Day Predictions into Daily History
- **Freezing Daily Records**: When `DailyHistorySnapshotter` finalizes a past day's summary in `daily_history` (which backs historical high/low and condition bars), it requires sub-daily metrics (e.g. day vs. night rain chance, noon cloud cover).
- **Deterministic Historical Metrics**: Because the live hourly table may have already moved forward, the snapshotter queries `hourly_forecast_history` to compute and freeze these metrics.
- **Repair & Backfill**: Handlers such as `FrozenRainChanceRepair` inspect historical hourly snapshots to re-derive frozen rain probabilities if historical rules or station coordinates are refined.

### 4. Day-Ago Comparison Curves on Graphs
- **Visual Accuracy Baselines**: `PriorDayCloudForecast` and `PriorDayBandForecast` extract frozen 24-hour-prior forecast curves from `hourly_forecast_history`.
- **Comparative Visualization**: Renders the previous day's forecast curve alongside observed actuals, giving users an immediate visual indication of forecast performance.

### 5. Gap Filling & API Lookback Resilience
- Providers with strict rolling horizons or quotas (e.g., Google Weather, NWS) only provide data moving forward from the current issuance. Elapsed hours in `hourly_forecast_history` provide local continuity for lookback windows without requiring external historical API queries.

---

## 4. Retention & Lifecycle Tiers

To stay within device storage limits and comply with Android's Google Cloud Backup quota (< 25 MB database footprint), `hourly_forecast_history` is managed via a two-tier retention policy (`RetentionPolicy` / `HistorySnapshotPruner` / `HistoryPruneWorker`):

| Tier | Time Window | Retention Strategy | Purpose |
|---|---|---|---|
| **Tier 1: Active Window** | 0 to 30 days | Retains multi-snapshot buckets per hour across active sites | Live stitcher, near-term hourly graphs, 1-day-ahead accuracy calculations |
| **Tier 2: Settled Archive** | 30 days to 18 months (547 days) | Pruned to **strictly 1 settled row per hour** per location cluster (discarding interim drafts and transient GPS jitter) | Historical drill-down from Months View / Daily View |
| **Tier 3: Purged** | > 18 months | Completely deleted | Disk space bounding |
