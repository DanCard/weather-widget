# Observed rain: combine stations per hour, don't add them

User, 2026-10-21, after `plans/261021-observed-rain-trace.md`: "tell me more: NWS daily total still
adds rain up across stations instead of combining them." … "yes" (write the plan).

## Problem

Observed rain is a **total over time**. The code adds it over time (hours → day), which is right,
but also over **stations**, which is wrong: three airports that each caught the same 3 mm shower
give a 9 mm day. Temperature never had this, because it goes through the distance-weighted blend.

Four places add across stations, one rule written four times:

| Where | What it adds |
|---|---|
| `:shared` `ActualsAggregator.resolveDailyPrecip` | every row of the source for the day → `daily_history.precipAmountMm / DayMm / NightMm` |
| desktop `PrecipitationGraph.buildRainHours` | every reading in an hour ("the hourly total is their sum") → "Act" labels |
| Android `PrecipViewHandler.buildActualPrecipByHour` | the same, per hour |
| Android `RainAccuracyCalculator.actualPrecipByHour` | the same, per hour → rain accuracy list |

The station filters are duplicated too: Android `ActualPrecipSource.matches`, desktop
`actualPrecipRowsForSource`.

Impact grows with station count: NWS has ~5 stations at Mountain View. A borrower set to METAR or
Synoptic sums that feed's whole list (Synoptic: ~35 k rows in a 132 h window, per a 2026-09-06
code comment). On a wet day the rain accuracy stats then show NWS under-forecasting.

Not visible yet: stored NWS rain days here are small (Sep 3 1.1 mm, Sep 7 1.9 mm, Sep 13 0.51 mm), and
raw observations are kept for 10 days only, so none can be re-checked.

### A dry station must count as 0, or combining biases wet

Combining needs to know which stations were **dry**, not just which were wet. Today an AO2
(automated, rain gauge) station's dry hour has no `P` group and is stored as null — "no data". An
average over "stations that reported a value" is then an average over **wet stations only**.
So this plan includes the second open item: **a routine report from an AO2 station with no `P`
group is a measured 0.**

## Rule (`:shared`, written once: `ObservedPrecip`)

Input: one source's observation rows (already filtered to its actual series), the site, a zone.

1. **Per station, per hour: the station's amount.** Hour = report time truncated to the hour
   (unchanged). From that station's reports in the hour:
   - a `P` group → that amount (trace = `PrecipTrace.MM`);
   - an AO2 routine report with no `P` group and no `PNO` remark → **0** (measured dry);
   - otherwise (manual stations such as KPAO without AO2, `PNO`, rows with no raw report) → unknown.
   - **If several reports in one hour carry amounts, take the routine report's** (see "SPECI" below);
     never add one station's reports together.
   - Rows with a JSON `precipitationLastHour` and no raw report keep today's meaning (value, or
     unknown when null).
   - Single-station synthetic series (`<SOURCE>_MAIN`, e.g. Open-Meteo) are this rule with one
     station: unchanged.
2. **Combine stations per hour → one value for the site:** the **nearest station with a known
   amount**, within 25 km. A real gauge reading, not an invented average. Rain is too local for a
   distance-weighted mean to be more honest than the nearest gauge (open question below).
3. **Hours → totals:** day = sum of the site's hours; 8am–8pm and night split as now
   (`DAY_START_HOUR` / `DAY_END_HOUR`). A total is **null** when no hour in it had a known
   amount; **0** when every known hour was dry.

`ActualsAggregator.resolveDailyPrecip`, desktop `buildRainHours` and both Android per-hour maps
call `ObservedPrecip.byHour(...)` / `.daily(...)`. The station filter moves into `:shared` too
(`ObservedPrecip.rowsFor(source)`, replacing `ActualPrecipSource.matches` /
`actualPrecipRowsForSource`). `CrossPlatformDuplicateNamesTest` guards that both sides keep using
it.

The today-only forecast fallback in `resolveDailyPrecip` is unchanged.

### SPECI: verify before relying on it

A SPECI is an extra mid-hour report issued when the weather changes. **Unverified:** whether an
ASOS SPECI's `P` group is "since the last routine report" (so the next routine report's `P`
*includes* it, and adding both double-counts). Step 0 settles it from real reports:
aviationweather.gov's raw text keeps the `SPECI` prefix (api.weather.gov's `rawMessage` drops it,
so on that feed a SPECI is recognised by its off-schedule minute — the station's routine minute is
the mode of its report minutes, e.g. KSJC :53, KNUQ :55). Rule 1 takes the routine report either
way; the verification decides whether a SPECI-only hour (no routine report yet) may use the
SPECI's amount.

### Cost

Rule 1 decodes `rawMetar` remarks at read time. NWS is ~7.9 k rows per 132 h window, Synoptic ~35 k.
Decode only rows with a raw report, cache by string within one call, and time it on desktop
(`ACTUALS_AGG` already logs its duration). If it shows, store a small `precipKind` at write time
instead (schema bump, both platforms) — not in the first cut.

## Steps

0. Pull a week of raw reports for KSJC / KNUQ / KPAO (and one rainy US airport) from
   aviationweather.gov; record whether a SPECI's `P` amount repeats in the next routine report.
   Write the finding into this plan.
1. `:shared` `ObservedPrecip` (rows → per-station-hour → site-hour → totals) + unit tests.
2. `MetarDecoder` exposes `precipSensorOff` (`PNO`); `ObservedPrecip` reads AO2 / `PNO` /
   routine-vs-SPECI from the decoded remarks.
3. Switch the four callers; delete the per-platform summing and station filters.
4. Recompute: `daily_history` rows inside observation retention (10 days) heal on their next
   recompute; older rows keep their stored totals (no raw data left to redo them).
5. Logs: `OBSERVED_PRECIP source=… date=… stations=KNUQ:0,KPAO:?,KSJC:0.001 picked=KNUQ
   total=… day=… night=…` (VERBOSE per hour; INFO once per day per source on recompute).

## Tests

`:shared` (`ObservedPrecipTest`), each failing on the summing code:
- Three stations each `P0012` in the same hour → **3.05 mm**, not 9.14.
- Nearest station dry (AO2, no `P`), a farther one wet → the hour is **0** (nearest known wins).
- Nearest station manual (no AO2, no `P`) → skipped as unknown; the next station decides.
- Station with `PNO` → unknown.
- All stations unknown for a day → total **null**; all known and dry → **0**.
- Routine + SPECI in one hour at one station → the routine amount, not the sum.
- Trace at the nearest station → the hour is a trace (`PrecipTrace.isTrace`).
- `<SOURCE>_MAIN` single-station series → identical to today's totals.

Integration (2+ classes): `ActualsAggregator` over a fixture of KNUQ/KPAO/KSJC rows for one wet
day → `daily_history` total/day/night from the combined hours.

Platforms: desktop `buildRainHours` and Android `buildActualPrecipByHour` /
`RainAccuracyCalculator.actualPrecipByHour` each fed the three-station fixture → the same per-hour
map as `ObservedPrecip.byHour`.

## Open question

**Nearest gauge or distance-weighted mean per hour?** Proposed: nearest station with a known amount
(a real measurement; showers are local, as on 2026-10-21 when only KSJC, 16 km away, caught one). A
distance-weighted mean would match the temperature blend but invents values no gauge measured,
e.g. 1 mm from "3 mm at 16 km, 0 at 4 km".

## Step 0 finding (2026-10-21)

**SPECI amounts are running totals and are repeated.** aviationweather.gov, KMIA, 2026-10-20:

```
SPECI KMIA 200845Z … RMK AO2 RAB08 P0002 …
METAR KMIA 200853Z … RMK AO2 RAB08 SLP154 P0002 60002 …   ← same 0.02 in, not 0.04
SPECI KMIA 190029Z … RAB21 P0000 …   then   METAR KMIA 190053Z … RAB21E50 P0001 …
```

Same set: an AO2 routine report with no `P` group in a quiet hour (`200753Z … RMK AO2 SLP153`), and an
AO2 SPECI without `P` before rain began (`200539Z`) — both dry so far. So a reporting period takes
the **largest** amount (all known amounts in a period are running totals), never the sum, and an AO2
report with no `P` group is dry whether routine or SPECI. Of 12 airports checked over 96 h only KMIA
had SPECIs with `P` groups; KSJC had one routine `P` (the 2026-10-21 trace).

## Done (2026-10-21)

- `:shared` `ObservedPrecip` (`rowsFor`, `byHour`, `daily`): per station, report rows take the
  period's largest amount (period = the routine report that closes it, routine minute = the
  station's most common minute, ≥ 3 raw reports); AO2 without `P`/`PNO` → 0; interval series
  (`<SOURCE>_MAIN`, Tomorrow.io five-minute) add within the hour; per hour the nearest station
  with a known amount ≤ 25 km.
- `MetarDecoder.MetarRemarks.precipSensorOff` (`PNO`).
- `ActualsAggregator.resolveDailyPrecip` takes the ±context window and calls `ObservedPrecip.daily`;
  its summing helpers are gone.
- Desktop `buildRainHours` → `ObservedPrecip.byHour`; `actualPrecipRowsForSource` → `rowsFor`.
- Android `PrecipViewHandler.buildActualPrecipByHour`, `RainAccuracyCalculator.actualPrecipByHour`
  → shared; `ActualPrecipSource` deleted. Parity fix on the way: Android's filter looked for
  `TOMORROW_IO_MAIN`, a retired id, so Android drew no Tomorrow.io actual rain; desktop did.
- Logging: `OBSERVED_PRECIP` per day at VERBOSE only (it runs on every recompute, for every source;
  INFO would flood `app_logs`).
- Cost, measured (3 runs, JVM): 6 days of `daily()` over a 132 h window — NWS-sized 7.9 k rows
  12–14 ms warm (53 ms cold), Synoptic-sized 35 k rows 16–22 ms. No `precipKind` column needed.
- Tests: `ObservedPrecipTest` (11), `ActualsAggregatorObservedRainTest` (integration; the old
  aggregator files 12.19 mm for the 4.06 mm day), `DesktopRainHourStationsTest`, and
  `RainAccuracyCalculatorTest` / `PrecipViewHandlerTest` / `DesktopRainHourNowGateTest` updated
  where they asserted cross-station or within-station summing of station rows.
