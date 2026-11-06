# NWS forecasts are whole °F, even when served in °C

Date: 2026-11-06

## Question

NWS seems to deliver temperatures in Celsius, yet forecasts show only whole Fahrenheit numbers.
Why are there no fractional forecast temps?

## Finding

The NWS forecast is whole °F on every endpoint. Forecasters edit the NDFD grid in integer °F. The
API serves the raw grid in SI units, so its Celsius values are exact conversions of those integers,
not extra precision.

Live responses for grid MTR/94,104 on 2026-11-06:

| Endpoint | Unit on the wire | Sample values | Parsed in |
|---|---|---|---|
| `/forecast/hourly` | integer °F (`"temperatureUnit":"F"`) | 59, 62, 65, 67 | `NwsApi.kt:419` |
| `/forecast` (day/night periods) | integer °F | 69, 50, 70, 51 | `NwsApi.kt:369` |
| `/gridpoints/{id}/{x},{y}` (raw grid) | `wmoUnit:degC`, fractional | 10.5555…, 11.6666…, 20.5555… | `NwsApi.kt:492`, `NwsApi.kt:592` |

The grid's Celsius values convert back to exact integers:

1. 10.5555…°C × 1.8 + 32 = 51.0°F
2. 20.5555…°C → 69.0°F (matches the `/forecast` "Today" high)
3. 10.0°C → 50.0°F (matches the "Tonight" low)

None of the samples converted to a value with a fractional part. Asking for `?units=si` on
`/forecast/hourly` returns rounded whole °C (15, 17, 18), which is less precise than the °F
values.

## Conversion precision

The grid parsers compute `raw * 1.8f + 32f` in `Float`. Running the samples through 32-bit float
math gives exact integers (51.0, not 50.99998). This matters because stored temps keep full
precision, and History of Forecasts and the accuracy views never round. A float error would show
up there as stray tenths.

## Notes

1. NWS "API actual" equals its forecast high (`apiHighTemp == forecastHighTemp`) because both come
   from the same integer-°F grid.
2. Fractional NWS temperatures come only from observations. METAR T-group remarks give 0.1°C
   readings (`NwsMetarTextApi.kt:76`), and `NwsObservationMapper` converts them to fractional °F.
   A decimal NWS high means an actual measurement was used, not the forecast.
3. NWS sometimes puts a -100°F missing-data value in the grid, which arrives as −73.333°C.
   `NwsTemperaturePlausibility` rejects it.

## Conclusion

No change needed.
