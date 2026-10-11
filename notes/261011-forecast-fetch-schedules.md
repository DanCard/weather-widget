# Forecast Fetch Schedules (Daily & Hourly)

*Date: October 11, 2026. Documents the dual-platform background fetch cadence for both Daily and Hourly forecasts, reflecting the separation of `HourlyCadence` from `ForecastCadence`.*

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

## 5. Related Code

- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyCadence.kt` — Core hourly decision rules
- `shared/src/main/kotlin/com/weatherwidget/data/remote/HourlyWindowPolicy.kt` — NEAR (48 h) vs FULL (20 h cadence) policy
- `shared/src/main/kotlin/com/weatherwidget/shared/util/ForecastCadence.kt` — Daily forecast background intervals
- `shared/src/main/kotlin/com/weatherwidget/shared/util/BatteryTier.kt` — Battery thresholds and off-charger calculation
- `shared/src/main/kotlin/com/weatherwidget/data/model/ForecastParts.kt` — DAILY, HOURLY, BOTH execution modes
- `app/src/main/java/com/weatherwidget/widget/ForecastFetchPolicy.kt` — Android scheduling wrapper
- `app/src/main/java/com/weatherwidget/data/repository/ForecastFetchCoordinator.kt` — Android sync coordinator
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopForecastPlanner.kt` — Desktop sync coordinator
