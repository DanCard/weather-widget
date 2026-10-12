# Forecast Fetch Schedules (Daily & Hourly)

*Documents the dual-platform background fetch cadence for both Daily and Hourly forecasts,
reflecting the separation of `HourlyCadence` from `ForecastCadence`.*

Daily forecasts and hourly forecasts are decoupled and evaluated independently on scheduled ticks across both Android (`:app`) and Linux Desktop (`:desktop`):
- **Daily Forecasts** run on [`ForecastCadence`](shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt) based on charger/battery tier, screen state, and displayed status.
- **Hourly Forecasts** run on [`HourlyCadence`](shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyCadence.kt) based on charger/battery tier, displayed status, and recency of the user's last switch to the source.

---

## 1. Hourly Forecast Fetch Cadence

Governed by [`HourlyCadence`](shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyCadence.kt):

| Source & View Recency | Charging (or Battery ≥ 80%) | Battery > 50% | Battery ≤ 50% |
|---|---|---|---|
| **Displayed Source** | **4 hours** | **8 hours** | **24 hours** |
| **Other: Viewed ≤ 24 h ago** | **4 hours** | **8 hours** | *None* |
| **Other: Viewed ≤ 36 h ago** | **6 hours** | *None* | *None* |
| **Other: Viewed ≤ 48 h ago** | **8 hours** | *None* | *None* |
| **Other: Viewed ≤ 72 h ago** | **12 hours** | *None* | *None* |
| **Other: Viewed > 72 h ago / Never** | *None* | *None* | *None* |

### Supporting Hourly Rules:
- **Tracking Grace Period (`HourlyCadence.inGrace`)**: For the first 3 days after install or source tracking activation, never-viewed sources are treated as viewed 72 hours ago (12-hour cadence on charger).
- **NEAR vs FULL Window (`HourlyWindowPolicy`)**:
  - `NEAR` window (48 hours stored): Default routine hourly fetch horizon.
  - `FULL` window (up to 8 days / 192 hours): Due every 20 hours while charging (or battery ≥ 80%) to update rain extrema and noon cloud coverage.
- **On-Demand Exemption**: Tapping days, panning past cached hours, switching to hourly view, or manual refresh bypasses routine cadence.

---

## 2. Daily Forecast Fetch Cadence

Governed by [`ForecastCadence`](shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt) and [`BatteryTier`](shared/src/main/kotlin/com/weatherwidget/shared/util/BatteryTier.kt):

| Source & Screen State | Charging (or Battery ≥ 80%) | Battery 71%–79% | Battery 51%–70% | Battery ≤ 50% |
|---|---|---|---|---|
| **Displayed Source (Screen On)** | **4 hours** (240 min) | **4 hours** (240 min) | **8 hours** (480 min) | *None* |
| **Displayed Source (Screen Off)** | **6 hours** (360 min) | **4 hours** (240 min) | **8 hours** (480 min) | *None* |
| **Other Sources (Screen On)** | **8 hours** (480 min) | **8 hours** (480 min) | **16 hours** (960 min) | *None* |
| **Other Sources (Screen Off)** | **12 hours** (720 min) | **8 hours** (480 min) | **16 hours** (960 min) | *None* |

### Supporting Daily Rules:
- **Treated as Charging (`BatteryTier.treatAsCharging`)**: Physical charging or battery ≥ 80% applies charger intervals and factors in screen state.
- **Off-Charger Multiplier (`OFF_CHARGER_OTHER_MULTIPLIER = 2`)**: Unplugged background sources wait twice as long as the base battery tier; screen state is ignored off-charger.

---

## 3. Background Evaluation Ticks

The background schedulers (WorkManager on Android via `WidgetWorkScheduler.schedulePeriodicSync`; daemon loop on Desktop) wake up periodically to evaluate if daily or hourly data is due:
- **Charging (or Battery ≥ 80%)**: Every **60 minutes**.
- **Off Charger (Battery > 70%)**: Every **240 minutes** (4 hours).
- **Off Charger (Battery 51%–70%)**: Every **480 minutes** (8 hours).
- **Off Charger (Battery ≤ 50%)**: Suspended / evaluated daily (24 hours).

---

## 4. Execution Combination Matrix (`ForecastParts`)

On each evaluation tick:

| Daily Due | Hourly Due | Action Executed |
|---|---|---|
| **Yes** | **Yes** | Combined fetch requesting both daily and hourly data. |
| **Yes** | **No** | Daily forecast fetch only (omits hourly endpoints/payloads). |
| **No** | **Yes** | Hourly-only forecast fetch (updates hourly series without overwriting daily forecast). |
| **No** | **No** | Skipped for that source. |

---

## 5. Hourly Data Horizon: Fetched vs. Stored

Hourly forecasts distinguish between what is requested/downloaded from the provider and what is actually persisted in the database (`hourly_forecasts`).

### A. Per-Source Horizon Overview

| Weather Source | API Horizon Fetched (Routine NEAR) | API Horizon Fetched (Routine FULL) | API Horizon Fetched (On-Demand) | Persisted to DB (Routine NEAR) | Persisted to DB (Routine FULL) | Persisted to DB (On-Demand) |
|---|---|---|---|---|---|---|
| **Google Weather** | 2 days (48 h / 2 pages) | 8 days (192 h / 8 pages) | Up to 10 days (240 h) | **2 days** (48 h) | **3 days** (72 h) | Up to 10 days (240 h) |
| **Open-Meteo** | ~16 days (~384 h) | ~16 days (~384 h) | ~16 days (~384 h) | **2 days** (48 h) | **3 days** (72 h) | Up to 10 days (240 h) |
| **Silurian** | ~14.5 days (349 h) | ~14.5 days (349 h) | ~14.5 days (349 h) | **2 days** (48 h) | **3 days** (72 h) | Up to 10 days (240 h) |
| **NWS** | ~6.5 days (156 h) | ~6.5 days (156 h) | ~6.5 days (156 h) | **2 days** (48 h) | **3 days** (72 h) | ~6.5 days (156 h) |
| **Tomorrow.io** | 5 days (120 h) | 5 days (120 h) | 5 days (120 h) | **2 days** (48 h) | **3 days** (72 h) | 5 days (120 h) |
| **OpenWeatherMap** | 5 days (120 h, 3-hourly) | 5 days (120 h, 3-hourly) | 5 days (120 h, 3-hourly) | **2 days** (48 h) | **3 days** (72 h) | 5 days (120 h) |
| **WeatherAPI** | 3 days (72 h) | 3 days (72 h) | 3 days (72 h) | **2 days** (48 h) | **3 days** (72 h) | 3 days (72 h) |

### B. Horizon Details & Storage Policies

1. **Routine NEAR Window (`HourlyWindowPolicy.NEAR_HOURS = 48`)**:
   - Persists **48 hours (2 days)** ahead for all providers.
   - Supplies the immediate 2-day hourly curve, rain summaries, and captures previous-day snapshot baselines for history evaluation.
2. **Routine FULL Window (`HourlyHorizons.ROUTINE_HOURS = 72`)**:
   - Persists **72 hours (3 days)** ahead into SQLite/Room.
   - For free providers (Open-Meteo, Silurian, NWS), the full API payload is returned in one request, but hours past 72 h are **discarded** after extracting noon cloud cover and rain extrema onto daily forecast rows (`DailyHourlySummaries`). This saves flash storage and avoids bloated tables.
   - For Google Weather, the FULL routine fetch explicitly requests 8 days (192 h / 8 billed pages) once every 20 hours while charging to refresh daily summaries, but only stores 72 hours of hourly rows.
3. **On-Demand Extension (`HourlyOnDemand.REACH_HOURS = 240`)**:
   - Triggered when the user taps or pans to day 4–10 (beyond 72 hours).
   - Google Weather pages only up to the requested day and stores those hours.
   - Free providers persist their entire payload (capped at 240 hours / 10 days) so all future days stay cached for up to 12 hours (`HourlyOnDemand.MAX_EXTENSION_AGE_MS`).
4. **Historical Retention (`RetentionPolicy.DEFAULT_DAYS = 30`)**:
   - Once stored, historical forecast rows and snapshots in `hourly_forecasts` and `hourly_forecast_history` are retained for **30 days** before automated cleanup.

---

## 6. How Full Fetch Data Populates the Daily Forecast Table (`forecasts`)

On a **FULL fetch**, all downloaded daily data and the entire downloaded hourly payload across all future days are used to populate the daily forecast table (`forecasts`):

### A. Direct Daily Endpoint Data
All days returned by the provider's daily forecast endpoint (7 days for NWS, 10 days for Google, 16 days for Open-Meteo, etc.) populate the core daily forecast attributes:
- **Temperatures**: Daytime high (`highTemp`) and overnight low (`lowTemp`).
- **Condition & Icons**: Weather description (`condition`) and provider icon codes (`nativeDailyIconToken`).
- **Precipitation**: Provider-reported period rain chances (`precipProbability`, `daytimePrecipProbability`, `nighttimePrecipProbability`) and expected precipitation accumulation (`precipAmountMm`).

### B. In-Memory Hourly Summaries Across the Full Horizon
Before the downloaded hourly series is trimmed to 72 hours for persistence into `hourly_forecasts`, the **entire hourly dataset is processed in memory** via `DailyHourlySummaries.forDate`.

For every future day covered by the hourly download, three summary metrics are calculated and written directly into columns on that day's `forecasts` row:

| Column on `forecasts` | How It Is Derived From Hourly Rows |
|---|---|
| **`noonCloudPercent`** | Cloud cover at 12:00 PM on that date (determines daytime cloud icons and the daily graph bar's sunny/cloudy split). |
| **`hourlyDayPrecipMax`** | Peak hourly rain probability between **8:00 AM and 8:00 PM**. |
| **`hourlyNightPrecipMax`** | Peak hourly rain probability between **8:00 PM and 8:00 AM** (next morning). |

### C. Handling Horizon Discrepancies & Carry-Forward
- **Full Coverage Sources (Open-Meteo, Silurian, NWS)**: The hourly payload covers the full daily forecast range, so all 7 to 16 days receive fresh hourly-derived summaries.
- **Paged / Tiered Sources (Google Weather)**: A FULL fetch requests 8 days (192 h / 8 billed pages), while daily forecasts cover 10 days. Days 1–8 receive fresh hourly summaries. Days 9–10 carry forward their prior values via `DailyHourlySummaries.carryForward` (or use provider daily values) rather than blanking.

### D. Architectural Benefit
Baking noon cloud and day/night rain extrema directly onto the `forecasts` rows allows the daily forecast view to render complete, high-fidelity multi-day graphs without needing to persist hundreds of raw hourly records to disk.

---

## 7. Related Code

- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyCadence.kt` — Core hourly decision rules
- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyWindowPolicy.kt` — NEAR (48 h) vs FULL (20 h cadence) policy
- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyHorizons.kt` — Routine (72 h) vs reach (240 h) horizons per provider
- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyOnDemand.kt` — On-demand hourly fetch depth and cache validation
- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailyHourlySummaries.kt` — Extracts noon cloud and rain maxima before dropping far hours
- `shared/src/main/kotlin/com/weatherwidget/data/local/RetentionPolicy.kt` — Table cleanup cutoffs (30-day retention for hourly)
- `shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt` — Daily forecast background intervals
- `shared/src/main/kotlin/com/weatherwidget/shared/util/BatteryTier.kt` — Battery thresholds and off-charger calculation
- `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastParts.kt` — DAILY, HOURLY, BOTH execution modes
- `app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt` — Daily forecast table definition including summary columns
- `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt` — Android scheduling wrapper
- `app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt` — Android sync coordinator
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopForecastPlanner.kt` — Desktop sync coordinator


