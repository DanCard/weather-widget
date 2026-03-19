# NWS API Extreme Temps for Yesterday

## Summary

`api.weather.gov` does not provide a dedicated "yesterday's official high/low" endpoint.

The station observation endpoints:

- `/stations/{stationId}/observations`
- `/stations/{stationId}/observations/latest`

can include `maxTemperatureLast24Hours` and `minTemperatureLast24Hours`, but those are rolling 24-hour values attached to individual observations, not a calendar-day daily summary.

## Important Limitation

The NWS API documentation currently lists a known upstream issue:

- station observation endpoints may return missing (`null`) 24-hour max/min temperatures for stations outside the Central Time Zone

This matches the emulator evidence for `2026-03-17`: the backfill fetched many NWS observation rows, but all of them had `maxTempLast24h=NULL` and `minTempLast24h=NULL`, so no official `daily_extremes` row could be written.

## Implication

If the app wants official daily extremes for yesterday:

- fetching raw observations from `api.weather.gov` is not reliable enough
- the worker can retrieve yesterday's observations, but it cannot rely on them to include official daily extreme values

## Recommended Source

For official past-day extremes, use NWS/NOAA climate data products instead of the `api.weather.gov` observation feed.

Relevant references:

- NWS Web API documentation:
  https://www.weather.gov/documentation/services-web-api
- NWS Climate Services products overview:
  https://www.weather.gov/climateservices/products
- NOWData overview/example page:
  https://www.weather.gov/ohx/nowdata
- NOWData FAQ:
  https://www.weather.gov/climateservices/nowdatafaq

## Practical Conclusion

For yesterday's official high/low:

- `api.weather.gov` observations: useful for spot observations, not reliable for official daily extremes
- NOWData / climate products: the better path for official historical daily max/min temperatures
