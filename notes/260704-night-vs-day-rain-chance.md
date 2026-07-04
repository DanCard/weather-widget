# Night vs Day Rain Chance Architecture & Logic

## Overview

In the **Weather Widget** application (both Android widget and Linux Desktop companion), **night versus day rain chance** separates precipitation probability into two distinct 12-hour time windows per calendar day rather than relying solely on a single 24-hour daily maximum.

---

## 1. Time Window Definition

Each 24-hour forecast date is split into two windows:
* **Daytime Window**: 8:00 AM – 8:00 PM (`daytimePrecipProbability`)
* **Nighttime Window**: 8:00 PM – 8:00 AM next day (`nighttimePrecipProbability`)

This separation prevents overnight rain (e.g., 90% chance at 2:00 AM) from giving the misleading impression of a rainy afternoon, or vice versa.

---

## 2. How Data Sources Supply Day/Night Rain Chance

The resolution strategy depends on the weather API source:

1. **National Weather Service (NWS)**:
   * NWS natively provides 12-hour forecast periods with an `isDaytime` boolean flag (e.g., *"Wednesday"* vs. *"Wednesday Night"*).
   * The app reads NWS `isDaytime = true` periods directly into `daytimePrecipProbability` and `isDaytime = false` periods into `nighttimePrecipProbability`.

2. **Hourly API Sources (e.g., Open-Meteo)**:
   * The app evaluates hourly forecast rows for the target date using [DailyRainLabels.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/util/DailyRainLabels.kt#L110-L141):
     * **Daytime Max**: Highest `precipProbability` among hourly entries from 08:00 to 20:00.
     * **Nighttime Max**: Highest `precipProbability` among hourly entries from 20:00 (target date) to 08:00 (next morning).

---

## 3. Database & Model Storage

In the database schema ([ForecastEntity.kt](file:///home/dcar/projects/weather-widget/app/src/main/java/com/weatherwidget/data/local/ForecastEntity.kt#L27-L28)), daily records preserve three distinct fields:
* `daytimePrecipProbability`: Daytime percentage (0–100%).
* `nighttimePrecipProbability`: Nighttime percentage (0–100%).
* `precipProbability`: Overall 24-hour daily peak percentage.

---

## 4. UI Display on the Daily Graph

The daily graph view represents daytime and nighttime rain chances differently:

* **Top Label (Daytime Rain Chance)**:
  * Rendered above the high temperature and condition icon at the top of each day's column.
  * Shows percentages (e.g., `60%`) or formatted rain amounts (e.g., `.15in` or `4mm`) when the daytime probability is very high (≥95% for today, ≥99% for future days).

* **Bottom Tucked Label (Nighttime Rain Chance)**:
  * Rendered in the space below the low temperature row, tucked between day columns.
  * Drawn at **72% scale** of the daytime label size to visually separate overnight rain from main daytime weather.

---

## 5. Distance-Scaled Suppression Thresholds

To prevent far-out low-confidence drizzle from cluttering the graph, the minimum required rain chance increases with distance from today:

$$\text{Threshold} = (4 \times \text{daysFromToday}) + 1\%$$

* **Today (Day 0)**: > 0%
* **Tomorrow (Day 1)**: ≥ 5%
* **Day 3**: ≥ 13%
* **Day 7**: ≥ 29%

If the daytime or nighttime probability is below its respective threshold for that day, the corresponding label is omitted.

---

## 6. Rain Timing Summaries (`RainAnalyzer`)

In text-mode views or summary cards, [RainAnalyzer.kt](file:///home/dcar/projects/weather-widget/shared/src/main/kotlin/com/weatherwidget/shared/util/RainAnalyzer.kt) inspects contiguous hourly windows (≥50% probability) to generate concise start-time indicators (e.g., `💧 4pm` or `💧 11pm`).
