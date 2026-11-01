# Finished nights show a rain amount, not 100%

Date: 2026-11-01. Status: implemented.

## Correction after step 1

Root cause B below is wrong as written. `actuals_provider_OPEN_WEATHER_MAP = NWS` first appears in the
12:24 backup and `SYNC_START reason=actuals_provider_changed` logged at 12:22:29, two minutes before it.
So OWM using its own rows until then was correct, and the 33 `KEPT_UNCOVERED` lines all predate the
switch.

The actual defect: a provider switch did not reach past days. After the switch today's row rebuilt from
NWS (rain 3.7 to 1.754 at 12:29), but Oct 31 stayed on OWM's rows because `DailyBlendRecomputeRun` skips
a day whose observations are unchanged, and that signature ignored the provider. The fix adds the
resolved providers to the signature, so each day in reach recomputes once after a switch.

## Goal

A finished night with a near-certain chance shows a rain total, on Android and desktop:

1. The measured total, when one above zero exists.
2. Otherwise the predicted total, at a chance of 95% or more, when nothing was measured.
3. Otherwise the chance, as now.

For the Fold's OWM view of Oct 31 this should read `.069in`, the measured total.

User's request: "for 100% rain chance, prefer to change that to actual rainfall total or if that is
not available than predicted rainfall total."

## Evidence

Fold backup `backups/20261101_122433_sm-f936u1_RFCT70G352Y`, night of Oct 31 (8pm to 8am), site
37.424, -122.088.

1. The 12:25 screenshot shows `100%` between Sat and Today with OWM selected. Logcat agrees:
   `date=2026-10-31 text="100%"`.
2. Rows for that night:

| Source | Night rain (mm) | Night chance | High / low | Updated |
|---|---|---|---|---|
| OWM | empty | 100 | 59.23 / 51.64 | 00:41 |
| Tomorrow.io | 1.75 | 97 | 63.95 / 48.06 | 07:44 |
| Silurian, Open-Meteo, Google | 1.75 | 80, 60, 31 | 63.95 / 48.06 | 07:44 |
| NWS | 1.75 | 49 | 63.81 / 48.06 | 04:06 |
| WeatherAPI | 0.0 | 62 | 65.70 / 47.70 | 10-31 23:19 |

3. OWM's predicted night total is 3.52 mm (`.14in`) from the newest run of each hour. All 13 hours are
   present; the night is 13 hours because of the clock change.
4. OWM's own observations that night are 19 rows with no rain amount.
5. `widget_state_prefs.xml` holds `actuals_provider_OPEN_WEATHER_MAP = NWS`, the same as Tomorrow.io and
   Open-Meteo.
6. `DAILY_HISTORY_KEPT_UNCOVERED date=2026-10-31 src=OPEN_WEATHER_MAP` logged 33 times today, with
   `kept=59.23/51.64 partial=59.23/51.48`. `OPEN_WEATHER_MAP_MAIN` that day ran 59.2 to 51.5, so the
   recompute's OWM result came from OWM's own rows, not NWS.
7. Desktop's OWM row for the night has 1.78 mm at 37.417, beside NWS's 1.75, so desktop does have a
   measured value there. Its exact source is not established.

## Root causes

**A. The label (known).** `DailyRainLabels.buildNightRainLabel` never uses a forecast amount once the
night has ended. The rule came from `b159f610`, so a forecast would not read as rain that fell.

**B. OWM's missing measured rain (partly known).** The row is empty because the OWM recompute used OWM's
own rows, despite the NWS preference. That also explains the Sat high and low of 59.2 / 51.6 on screen,
against 63.95 / 48.06 on the sources that use NWS.

What is not known is why. As read, `ActualsAggregator` should drop OWM's own group once its provider
resolves to NWS, and `WeatherSourcePreferences.actualsProvider` and `ActualsProviderResolver.resolve`
look correct for OWM. Step 1 below finds the actual cause before anything in this area changes.

## Changes

### 1. Find why OWM's recompute ignores the preference

1. Add a failing `:shared` test: `ActualsAggregator.aggregate` with OWM set to NWS, using OWM_MAIN rows
   with no rain and NWS rows with rain, expects OWM's night to hold the NWS total. Add the same case
   through `DailyBlendRecomputeRun` and `DailyActualsAssembler` if the first test passes.
2. If the tests pass, the cause is outside those paths. Add targeted logging for the provider and row
   count per source in the recompute, and read it from the Fold. Candidates to check: other callers
   that write OWM's row, the observation filter passed into the recompute, and why OWM's row stopped
   updating at 00:41.
3. Fix the cause in `:shared`. If the fix turns out to be significant, or the preference is not meant to
   reach this path, stop and report before changing it.

### 2. Label fallback

In `buildNightRainLabel`, finished nights (`isPastDate`, not `nightInProgress`):

1. Measured above zero: the measured amount, as now.
2. `observedNightPrecipMm == null`, chance at least `NIGHT_AMOUNT_MIN_PERCENT_NEAR` (95), predicted total
   above zero: the predicted amount.
3. Otherwise the chance, as now.

Decisions:

1. **95%, not 100%.** This is the threshold already approved for a night in progress, so a label does
   not flip back to a % at 8am.
2. **A measured 0 keeps the %.** A gauge reported no rain, so a predicted total would contradict it.
   Only an empty value counts as not available.

Android and desktop both get this through `DailyColumnBuilder`. Rewrite the function's KDoc, which still
says a finished night never shows a forecast amount.

### 3. Repairing existing rows

`DAILY_RECOMPUTE_SKIP` shows Oct 31 is treated as settled, and the skip signature may not change after
the fix. Check that the fix actually rewrites OWM's recent rows. Rows past the 10-day observation
retention cannot be rebuilt and keep the label fallback. Do not use `FULL_SYNC` to test this, since it
forces every source and spends API quota.

## Tests

`:shared`, in `DailyRainLabelsTest` unless noted:

1. Replace `finishedNightNeverShowsAForecastAmount`:
   - 100% with nothing measured shows the predicted amount.
   - 95% shows the predicted amount, and 94% shows the chance.
   - Measured above zero beats a predicted amount.
   - A measured 0 keeps the %.
   - A null or zero predicted total keeps the %.
2. A night in progress still prefers the forecast over the rain measured so far.
3. The failing provider test from step 1 passes after the fix, and fails on the old code.
4. A test that a source with no preference, and no rain of its own, keeps an empty row.

Then `./scripts/unit-tests.sh`, checking the summary line and that the long tests ran, followed by
`./scripts/emulator-tests.sh`.

## Verification

1. **Fold, OWM:** the Sat to Today night reads `.069in`. Confirm with a screenshot and the
   `nightRainLabel position` log.
2. **Fold, Sat high and low on OWM:** these may change, since they come from the same recompute. Report
   the before and after.
3. **Tonight and later nights:** labels unchanged.
4. **Desktop:** rebuild and restart with `scripts/buildStart-desktop.sh`, then check the same night on OWM.
5. **Label fallback on a real row:** this needs a finished night with a chance of 95% or more and nothing
   measured. If none exists on a device once OWM is fixed, say the fallback is covered by tests only.

## Docs

1. Update `arch/daily-history-extremes.md` if the fix changes how actuals are provided.
2. Add a recap under `summaries/` when done.

## Risks

1. Fixing step 1 may change OWM's past highs and lows, and accuracy figures that use them, because they
   would come from NWS rather than OWM's own rows. That matches the stored preference, but it is visible.
2. A predicted total looks the same as a measured one on the graph. Nothing marks which it is.
3. The same defect could affect other sources or devices. Step 1 should check the Pixel and desktop.

## Out of scope

1. New styling to separate predicted from measured totals.
2. Changing the 95% and 99% thresholds, or daytime rain labels.
3. The repeated OpenWeatherMap fetches seen on the Samsung that the plan marked not due.
