# Observed rain: show a trace as "Trace"

User, 2026-10-21: "Desktop: nws api: I see predicted rainfall amount but not actuals." … "Meteo says
there was rain. It did rain." … "Yes implement trace."

## Evidence

Desktop DB, 2026-10-21 (Mountain View): NWS forecast 2.33 mm. The only rain any NWS station
reported was KSJC 12:39–12:50 PDT, `KSJC 211953Z … RMK AO2 RAB39E50 SLP109 P0000` — `P0000` is a
**trace** (rain fell, < 0.01 in). api.weather.gov's JSON for the same report gives
`precipitationLastHour = {value: 0}`, so the JSON cannot tell a trace from dry. Stored as 0.0 mm, it
drew nothing: the past-day labels and the precip graph's "Act" labels show only amounts > 0.
Open-Meteo's analysis had 0.1 mm at 13:00, which drew.

## Fix

1. `:shared` `PrecipTrace`: a reported trace is stored as `MM` = 0.001 mm — positive, so every
   "did it rain?" (> 0) check sees it; far below the smallest gauge reading (0.01 in = 0.254 mm), so
   totals stay honest. An observed total below `MAX_MM` (0.05 mm, 50 trace hours) is a trace.
2. `MetarDecoder`: `P0000` / `60000` / `70000` → `PrecipTrace.MM` (was 0).
3. `NwsObservationMapper`: the remarks trace wins over the JSON's 0 (`precipitationLastHour` is 0
   for both a trace and, on some stations, a dry hour).
4. Display, observed amounts only: `DailyRainLabels.formatObservedPrecipAmount` → "Trace";
   past-day day/night labels, the precip graph's "Act" labels (`RainAmountLabelPlacer` `observed`),
   Android's rain-accuracy list. Forecast amounts are unchanged ("<.001in" for a tiny forecast).

No schema change. Rows already stored as 0.0 heal when the stations are re-fetched (NWS 7-day
pull / desktop observation window), and `daily_history` on its next recompute.

## Out of scope (raised in the same conversation)

- A dry AUTO station's report with no `P` group counting as measured 0.
- `ActualsAggregator.resolveDailyPrecip` sums rain across every station of the source.

## Tests

- `MetarDecoderTest`: `P0000` → trace; `P0002` unchanged.
- `NwsObservationMapper`: JSON 0 + `P0000` → trace; JSON 0, no remark → 0.
- `DailyRainLabels`: past-day trace → "Trace"; forecast tiny amount still "<.001in".
- `RainAmountLabelPlacer`: observed trace period → "Act Trace".

## Done (2026-10-21)

- `:shared` `PrecipTrace`; `MetarDecoder.precipGroupMm`; `NwsObservationMapper` trace override;
  `DailyRainLabels.formatObservedPrecipAmount`; `RainAmountLabelPlacer` `observed` (Android
  `PrecipitationGraphRenderer`, desktop `PrecipitationGraph` pass it for "Act").
- Android `DailyRainAccuracyAdapter`: the actual side uses the observed formatter.
- Tests: `MetarDecoderTest`, `NwsObservationMapperMetarTest`, `DailyRainLabelsTest`,
  `RainAmountLabelPlacerTraceTest` (new).
