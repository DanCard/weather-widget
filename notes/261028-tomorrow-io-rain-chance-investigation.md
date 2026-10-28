# Tomorrow.io Rain Chance Investigation (Saturday 2026-10-31 Forecast)

**Date:** 2026-10-28  
**Question:** Is Tomorrow.io rain chance working? Every other API says chance of rain Saturday or Saturday night, except Tomorrow.io.  
**Conclusion:** Yes, Tomorrow.io's rain chance is working properly end-to-end. The app accurately ingests and parses `precipitationProbability` from Tomorrow.io. Tomorrow.io's forecast model recognizes the exact same Saturday night weather system (7:00 PM – 9:00 PM PDT), but its localized precipitation cutoff ($\ge 0.01\text{ in}$) places the rain footprint 10–15 miles south/east (in San Jose and Santa Cruz), leaving Mountain View with 0% rain chance under heavy cloud cover.

---

## 1. Live API Probes

Using the active `TOMORROW_IO_API_KEY` to probe `https://api.tomorrow.io/v4/timelines`:

### Mountain View (`37.4166, -122.0888`)
* **Timestep `1d` (Saturday 2026-10-31):**
  * `precipitationProbability: 0`
  * `precipitationAccumulation: 0`
  * `precipitationType: 1` (Rain - dominant form if precipitation occurs)
  * `weatherCode: 1000` (Clear / Mostly Clear aggregate)
* **Timestep `1h` (Saturday evening / night):**
  * Across all 24 hours of Saturday, `precipitationProbability` is explicitly returned as `0`.
  * Weather codes during Saturday evening transition through `1102` (Mostly Cloudy) and `1001` (Cloudy).

### Regional Comparison (Saturday Night Storm System)
A spatial probe around the San Francisco Bay Area during the Saturday night window (`2026-11-01T02:00Z–04:00Z` / **7:00 PM – 9:00 PM PDT**):

* **Mountain View / Sunnyvale / Santa Clara:** `precipitationProbability = 0%`, `precipAccumulation = 0.00 in`
* **San Jose (`37.3382, -121.8863`):** `precipitationProbability = 30%` (7:00 PM – 9:00 PM PDT)
* **Santa Cruz (`36.9741, -122.0308`):** `precipitationProbability = 25%` (7:00 PM – 9:00 PM PDT)
* **San Francisco (`37.7749, -122.4194`):** `precipitationProbability = 0%`
* **Control / Active Rain Area (Seattle, `47.6062, -122.3321`):** `precipitationProbability = 25%`, `precipAccumulation = 0.03 in`, `weatherCode = 4200` (Light Rain).

---

## 2. Local Database & Parsing Verification

1. **Parser Implementation (`TomorrowIoApi.kt`):**
   * Hourly and daily mappings both read `values["precipitationProbability"]?.jsonPrimitive?.floatOrNull` through `percentOrNull(...)`.
   * When non-zero values arrive, they are rounded and stored in `hourly_forecasts.precipProbability` and `forecasts.precipProbability`.
2. **Database Records (`weather.db`):**
   * Historical table `hourly_forecast_history` contains 139 stored records for `TOMORROW_IO` where `precipProbability > 0` (ranging up to 100%), confirming parsing and persistence operate as expected across real-world precipitation events.

---

## 3. Provider Comparison for Saturday Night in Mountain View (`37.417, -122.089`)

Data extracted from `hourly_forecasts` in `weather.db` for Saturday evening (2026-10-31):

| Source | Peak Rain Chance | Peak Timing | Condition / Forecast Detail |
| :--- | :---: | :---: | :--- |
| **Silurian** | **55%** | ~7:00 PM PDT | Rain (`0.06 mm`) |
| **Google Weather** | **30%** | ~7:00 PM PDT | Rain (`0.29 mm`) |
| **NWS** | **15%** | ~11:00 PM PDT | Slight Chance Light Rain |
| **Open-Meteo** | **13%** | ~10:00 PM – 11:00 PM PDT | Overcast |
| **Tomorrow.io** | **0%** (*30% in San Jose*) | ~7:00 PM – 9:00 PM PDT | Mostly Cloudy / Cloudy |

---

## 4. Why Tomorrow.io Shows 0% for Mountain View

Tomorrow.io defines `precipitationProbability` as the probability of liquid precipitation $\ge 0.01\text{ in}$ (0.254 mm) occurring at the exact coordinate. 

While Silurian, Google Weather, and NWS spread a light precipitation probability across the Peninsula, Tomorrow.io's high-resolution model predicts the rain track will stay focused over the Santa Cruz Mountains and South Bay/Central Coast, giving Mountain View overcast skies but keeping expected precipitation below the measurable threshold.
