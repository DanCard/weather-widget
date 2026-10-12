# `daily_history` schema

Source: `app/src/main/java/com/weatherwidget/data/local/DailyHistoryEntity.kt` (Room). Desktop has its
own schema (version moves in pairs with Room's); not verified here.

**Key:** `PRIMARY KEY (date, source, locationLat, locationLon)`, plus index on
`(date, locationLat, locationLon)`. One row per source per day per site.

**Retention:** 18 months (`:shared` `RetentionPolicy`).

| Group | Column | Type | Notes |
|---|---|---|---|
| Key | `date` | Long | UTC midnight epoch ms |
| | `source` | String | `WeatherSource.id` |
| | `locationLat`, `locationLon` | Double | Quantized |
| Blended actuals | `computedHighTemp`, `computedLowTemp` | Float? | IDW observation blend, °F; null = forecast-only row |
| | `computedHighAt`, `computedLowAt` | Long? | When the extreme was reached (since v72) |
| Provider actuals | `apiHighTemp`, `apiLowTemp` | Float? | Provider's own product, °F; null when source has no native actuals |
| | `apiStationId`, `apiStationDistanceKm` | String?, Float? | Station behind the api actuals (NWS) |
| | `actualsSource` | String? | Marks a day as resolved, which freezes its blend (since v60) |
| Frozen forecasts | `forecastHighTemp`, `forecastLowTemp` | Float? | Settled overlay values (`ForecastOverlaySettle`) |
| | `priorForecastHighTemp`, `priorForecastLowTemp` | Float? | "Yesterday's forecast", left bar (since v73): low from newest fetch before 06:00 prior day, high before 16:00 |
| | `forecastPrecipAmountMm` | Float? | |
| | `forecastDayPrecipChance`, `forecastNightPrecipChance` | Int? | As displayed |
| Precip actuals | `precipAmountMm`, `precipDayMm`, `precipNightMm` | Float? | Total, 8am-8pm, 8pm-8am |
| Other | `condition` | String | |
| | `noonCloudPercent` | Int? | |
| | `updatedAt` | Long | Epoch ms, used for cleanup |
| | `lastWriter` | String? | Diagnostic only |

## Notes

- `computed*` is null on forecast-only rows by design (`DailyHistoryWriter.FORECAST_ONLY_ROW`);
  accuracy and graph code must treat null as "no data", never zero.
- Not frozen here: `forecasts.hindcastHigh/LowTemp`, the dashed-bar fallback; they stay on the
  `forecasts` row (30 days).
- Source comparison needs no new columns: read two rows for the same date and site. Rows exist only
  for sources that were fetched (`SourceFetchGate`), and a GPS move can split a source's history
  across sites (pair with `LocationMatch`).
- A month / year zoomed-out graph can read `daily_history` alone: actual (`computed*` / `api*`),
  prior-day forecast, settled forecast.
