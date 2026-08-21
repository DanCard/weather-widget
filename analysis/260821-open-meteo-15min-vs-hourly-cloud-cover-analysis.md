# Open-Meteo Cloud Cover Resolution Analysis (15-Minute vs. Hourly)

**Date**: 2026-08-21  
**Scope**: Open-Meteo Cloud Cover (`minutely_15` vs. `hourly`) compared against ground-truth METAR ceilometer observations.  
**Dataset Window**: 2026-08-14 to 2026-08-21 (7 days)  
**Location Under Test**: 37.417, -122.089 (SF Bay Area)  

---

## 1. Executive Summary

This study evaluates whether querying Open-Meteo at **15-minute resolution** (`minutely_15=cloud_cover,cloud_cover_low`) provides measurably higher accuracy for actual cloud cover compared to **1-hour resolution** (`hourly=...`) interpolated linearly.

### Key Conclusions:
1. **Negligible Accuracy Gain**: Across 742 paired ground-truth observations, the Mean Absolute Error (MAE) difference between the 15-minute series and the interpolated hourly series is less than **0.1 percentage points** (21.16% vs. 21.24%).
2. **Sub-Hourly Texture is Model Noise**: During fast-changing cloud transitions ($\ge 20\%$ delta/hr), the 15-minute series introduces slight variance rather than improved accuracy (RMSE 39.12% for 15-min vs. 38.91% for hourly).
3. **Upstream Day-Ago Baseline is Hourly Only**: The Open-Meteo Previous Runs API (`previous-runs-api.open-meteo.com`) returns `null` for `minutely_15=cloud_cover_low_previous_day1`. Day-ago baseline forecasts are only available at 1-hour resolution.
4. **Recommendation**: Continue querying Open-Meteo at **1-hour intervals**. This keeps the actual curve aligned with the frozen forecast curve, avoids unnecessary payload bloat, and provides equivalent accuracy.

---

## 2. API Capability & Parameter Support

* **Forecast API (`api.open-meteo.com/v1/forecast`)**:
  * Accepts `minutely_15=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high`.
  * Fully supports `past_days` (up to 60+ days) and `forecast_days`.
  * High-resolution native model runs (e.g. HRRR in CONUS, ICON-D2 in Central Europe) populate native 15-minute steps; for other regions, Open-Meteo interpolates between 1-hour intervals.
* **Previous Runs API (`previous-runs-api.open-meteo.com/v1/forecast`)**:
  * Accepts `minutely_15=cloud_cover_low_previous_day1`, but returns an array of `null` values.
  * Day-ago frozen predictions are only served at hourly resolution (`hourly=cloud_cover_low_previous_day1`).

---

## 3. Empirical Benchmark Methodology

To evaluate accuracy against physical ground truth:
1. **Ground Truth Source**: METAR/SPECI reports fetched from the official NWS API for four regional airport weather stations:
   * **KNUQ** (Moffett Federal Airfield, 3.8 km)
   * **KPAO** (Palo Alto Airport, 6.0 km)
   * **KSJC** (San Jose Mineta International Airport, 15.9 km)
   * **KSQL** (San Carlos Airport, 18.2 km)
2. **Layer Mapping**: METAR sky conditions were mapped using WMO okta midpoints for low cloud ($\le 2,000\text{ m}$ / $6,500\text{ ft}$ ceiling, matching Open-Meteo's `cloud_cover_low` convention):
   * `CLR` / `SKC` / `CAVOK`: $0\%$
   * `FEW`: $19\%$
   * `SCT`: $44\%$
   * `BKN`: $75\%$
   * `OVC` / `VV`: $100\%$
3. **Comparison Alignment**:
   * For each timestamped METAR report, Open-Meteo's hourly curve was linearly interpolated to that exact timestamp.
   * Open-Meteo's 15-minute curve was evaluated at that exact timestamp.

---

## 4. Benchmark Results

### 4.1 Overall Metrics (742 Paired Observations)

| Metric | Hourly Interpolated | 15-Minute Series | Delta |
|:---|:---:|:---:|:---:|
| **Mean Absolute Error (MAE)** | **21.24%** | **21.16%** | -0.08% |
| **Root Mean Square Error (RMSE)** | **38.91%** | **39.12%** | +0.21% |
| **Agreement within 0.5% (Tied)** | 494 samples (66.6%) | 494 samples (66.6%) | — |
| **Direct Head-to-Head Wins** | 102 samples (13.7%) | 146 samples (19.7%) | +6.0% |

### 4.2 Breakdown by Weather Station

| Station | Distance | Samples ($n$) | MAE (Hourly) | MAE (15-Min) | RMSE (Hourly) | RMSE (15-Min) |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **KNUQ** | 3.8 km | 271 | 11.25% | 11.17% | 22.55% | 22.65% |
| **KPAO** | 6.0 km | 51 | 12.52% | 12.45% | 19.30% | 19.67% |
| **KSJC** | 15.9 km | 279 | 32.22% | 32.09% | 51.39% | 51.64% |
| **KSQL** | 18.2 km | 141 | 21.84% | 21.91% | 40.39% | 40.62% |

### 4.3 Rapid Transitional Weather ($\Delta \ge 20\%$ change within 1 hour)

Evaluated across 119 rapid-transition samples:
* **Hourly Interpolated MAE**: `31.74%`
* **15-Minute Series MAE**: `31.66%`
* **Delta**: `-0.08%`

---

## 5. Summary & Recommendation

1. **Accuracy Reality**: Sub-hourly Open-Meteo cloud cover data does not materially improve agreement with real-world ceiling observations over a smoothly interpolated hourly series.
2. **Structural Consistency**: The comparison against day-ago predictions remains fundamentally hourly due to upstream Previous Runs API constraints.
3. **Decision**: Maintain the existing **hourly** resolution in `OpenMeteoApi.kt` (`getForecast` and `getPriorDayCloudForecast`).
