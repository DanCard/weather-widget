# Make current temperature provider comparisons trustworthy

**Date:** 2026-09-10
**Status:** proposed — runtime evidence complete; implementation not started
**Platforms:** Android (`:app`) and Linux Desktop (`:desktop`), with policy and calculations in
`:shared`

## Outcome

The temperature presented as **current** must have a stable, explainable meaning:

1. Switching the forecast provider must not silently switch between measured station data,
   provider analysis, and forecast-derived data under the same unlabeled number.
2. A delayed provider update must not produce an avoidable catch-up jump when a newer usable value
   is already stored.
3. The UI must identify whether the number is measured, blended, derived, or estimated, and show
   the observation age when that distinction matters.
4. Android and desktop must resolve the same inputs to the same value.
5. Forecast-provider comparisons and historical accuracy must keep their existing source-specific
   actual-series behavior unless explicitly changed; this plan first fixes the current-temperature
   header rather than silently redefining historical scoring.

## Runtime evidence

### Pixel 7 Pro: confirmed Tomorrow.io discontinuity

Physical device identity was verified rather than inferred from its serial:

- Serial: `2A191FDH300PPW`
- Manufacturer/model: Google Pixel 7 Pro
- Android SDK: 37
- Widget: 88
- Location: `37.41682,-122.08903`

Immediately before the 10:30 current-temperature fetch, the Pixel stored both of these Tomorrow.io
products:

| Product | Observation time | Temperature | Fetch time |
|---|---:|---:|---:|
| Realtime | 09:34 | 72.70 F | 09:34:57 |
| Recent history | 10:00 | 75.60 F | 10:20:49 |

`CloudHourBucket` rounds observations to the nearest hour. Therefore 09:34 and 10:00 occupy the
same 10:00 bucket. `TomorrowIoActuals.preferRealtimeWithinHour` currently keeps every realtime row
and discards recent-history rows whenever a bucket contains any realtime row. It consequently kept
the 26-minute-older 72.70 F reading and discarded the newer 75.60 F reading.

The device then recorded this sequence:

| Device time | Event | Observation anchor | Calculated header |
|---|---|---:|---:|
| 10:30:40 | Before current fetch | 72.70 F at 09:34 | 75.78 F |
| 10:30:53 | `OBS_CURRENT_INSERT` | new realtime 77.07 F at 10:30 | not painted yet |
| 10:30:56 | First paint after fetch | 77.07 F at 10:30 | 77.13 F |
| 10:39:40 | Same anchor, forecast advancement | 77.07 F at 10:30 | 77.64 F |

The provider's raw selected anchor jumped **4.37 F**. The header jumped **1.35 F immediately**
because its source-scoped forecast delta was simultaneously recalculated from `-1.64 F` to
`-0.31 F`; it then continued moving along Tomorrow.io's hourly forecast curve.

This was not a location mismatch, failed request, or stale database write:

- `CURR_FETCH_SOURCE_RESULT` reported `source=TOMORROW_IO success=true temp=77.07`.
- The inserted observation timestamp was current (`observedAgeMin=0`).
- The graph and header both resolved the same configured site.
- A screenshot at 10:40 showed `Tmrw`, header `77.6`, actual point `77.1`, and the 10:30 point.

### Emulator: provider switching changes the meaning of current

At approximately 10:30 on `emulator-5554`, the four configured forecast sources resolved these
values at the same Mountain View coordinates:

| Forecast source | Actuals source/kind | Latest selected anchor | Header display |
|---|---|---:|---:|
| Open-Meteo | Open-Meteo derived analysis | 74.40 F at 10:15 | 75.64 F |
| Tomorrow.io | Tomorrow.io derived realtime | 73.89 F at 09:55 | 75.29 F |
| NWS | NWS station blend | 81.24 F at 10:15 | 82.01 F |
| Silurian | borrowed Synoptic station blend | 84.25 F at 10:25 | 85.14 F |

The selected anchors spanned **10.36 F** and the displayed headers spanned **9.85 F**. This is not
rounding noise. The source toggle currently compares unlike products:

- NWS and Synoptic resolve spatial blends of station observations.
- Open-Meteo supplies a gridded analysis/backfill product.
- Tomorrow.io supplies realtime/recent-analysis products without a physical station identity.
- Silurian has no temperature-observation endpoint and borrows a configured actuals provider.

`CurrentTemperatureResolver` then advances each selected anchor with the selected forecast source's
own curve:

```text
display(now) = forecast(now) + observation - forecast(observation time)
```

That is useful for a smooth estimate, but it guarantees that changing forecast sources can change
the number labeled current even if every source is configured to share one observation feed.

### Personal-station population can overcome a per-station discount

The active personal-station discount was 95%, making each personal station's type multiplier 0.05.
The multiplier is applied separately before IDW normalization. A provider returning many nearby
personal stations can therefore give them substantial combined weight.

At 10:30:

- KNUQ reported 80.6 F in both NWS and Synoptic.
- KNUQ held 65.9% of the NWS blend, whose result was 81.24 F.
- KNUQ held only 47.7% of the Synoptic blend, whose result was 84.25 F.
- The Synoptic pool included multiple nearby personal stations reporting 84-88 F plus farther
  official stations.

The arithmetic is behaving as written, but the population sensitivity is not an intuitive trust
property for a setting described as a 95% discount.

### False stale state after an unchanged successful fetch

NWS and Silurian live hourly rows retained an old `fetchedAt` and were marked stale after successful
10:27 fetches. Their forecast-history rows proved the 10:27 responses contained the same values.

`HourlyForecastStore.hasMeaningfulHourlyChange` deliberately excludes `fetchedAt`; unchanged
weather fields are not written back to `hourly_forecasts`. The current resolver interprets the old
row timestamp as an old fetch. This did not cause the observed provider spread, but it creates a
false freshness warning and weakens diagnostics.

## Root causes

1. **Tomorrow.io product precedence is type-first rather than freshness-first.** A realtime sample
   suppresses a newer recent-history sample in the same nearest-hour bucket.
2. **Current-temperature provenance follows the forecast source.** The same UI position alternates
   among measured, blended, and derived products.
3. **Forecast extrapolation also follows the forecast source.** Even a shared observation anchor
   produces different current estimates after a source toggle.
4. **Personal-station discount is per row/station, not a cap on the personal class.** Provider pool
   size affects the aggregate influence of personal stations.
5. **Live-row `fetchedAt` means last value change, not last successful fetch.** The stale detector
   treats it as the latter.

## Scope and sequencing

The work is split so the confirmed Tomorrow.io defect can be fixed independently of the larger
product-policy change. After approval, proceed through all phases without additional check-ins
unless evidence forces a change to this plan.

## Phase 1: make Tomorrow.io merging freshness-aware

### Shared policy

Update `shared/.../actuals/TomorrowIoActuals.kt` without changing persisted provenance:

1. Continue grouping with `CloudHourBucket.startMsOf`; do not alter the shared nearest-hour rule.
2. Preserve every realtime sample when realtime is the freshest product in the bucket.
3. When a recent-history row is strictly newer than the newest realtime sample in its bucket,
   retain that history row as the bucket's newest point instead of suppressing it.
4. Retain realtime precedence when timestamps are equal.
5. Normalize accepted rows to the one in-memory `Tmrw` station exactly as today, so the temperature
   blender cannot treat the two products as two physical stations.
6. Keep cloud-product selection unchanged unless a focused cloud test proves it shares the same
   current-temperature defect; cloud semantics are outside this temperature fix by default.

For the Pixel fixture, the accepted logical sequence must contain 09:34 = 72.70 F and
10:00 = 75.60 F before the next realtime response. After 10:30 realtime arrives, it becomes the
latest point at 77.07 F. The avoidable 72.70 -> 77.07 catch-up is replaced by
72.70 -> 75.60 -> 77.07.

### Tests

Extend `shared/src/test/.../actuals/TomorrowIoActualsTest.kt` with Short tests for:

1. Pixel regression: 09:34 realtime and 10:00 recent history round into the same bucket; the newer
   history row remains available.
2. Newer realtime retains all realtime samples and suppresses an older overlapping history row.
3. Equal timestamps prefer realtime deterministically.
4. A bucket with only recent history remains unchanged.
5. Input order does not change output.
6. `forTemperatureSeries` still exposes one normalized logical station.

Add an integration-level shared regression through `ActualTemperatureSeriesBuilder` or
`ActualsAggregator` proving that the selected current anchor is 75.60 F before the 10:30 realtime
row and 77.07 F afterward. Assert values and timestamps, not merely row counts.

## Phase 2: give the current header one canonical provenance

### Recommended product decision

Add an app-wide **Current temperature source** preference, separate from per-forecast-source
actuals preferences. Default it to the existing keyless measured provider (`METAR`). Permit the
same candidates already classified by `ActualsProviderResolver`, grouped as measured and derived.

The header uses this canonical source in every daily/hourly/precipitation/cloud view and does not
change when the forecast source button is pressed. The forecast graph, source-specific actual line,
and historical accuracy continue to use their existing per-source actuals provider; changing those
would be a separate scoring-policy decision.

### Shared ownership

Add the provider selection/fallback policy in `:shared` (for example,
`CurrentConditionsProviderResolver`) and reuse `ActualsProviderResolver` candidate/tier
classification. The shared policy must return both:

- the chosen provider id; and
- provenance kind: `MEASURED`, `DERIVED`, or `FORECAST_ESTIMATE`.

Fallback order:

1. Fresh canonical measured/derived observation.
2. Stale canonical observation, returned with age/stale state rather than silently transformed.
3. Only when no observation exists, the selected forecast's estimate, explicitly marked estimated.

Do not silently fall back from a missing measured source to an unlabeled derived source.

### Current-value calculation

For the trust-first default, display the latest canonical blended observation while it is fresh.
Do not advance it using whichever forecast source happens to be visible. This intentionally trades
a stepwise station update for a stable and auditable current value.

If smoothing remains desired, implement it later as one canonical, source-independent policy (for
example, short transition smoothing between successive canonical observations). Do not reuse the
visible provider's forecast slope, because that recreates the original source-toggle variation.

### Android adapter/UI

Likely touch points:

- `WidgetStateManager` / preference owner: persist canonical provider id.
- `WidgetRenderer` and handler input construction: resolve the canonical current once and pass the
  result to every view.
- `WeatherObservationsActivity` or Settings UI: add the provider picker using Android resources.
- `activity_settings.xml` if placed in Settings; mirror the ordering on desktop.
- Header state/renderers: show concise provenance and age for derived, estimated, or stale values.

Do not duplicate provider ranking, freshness, or fallback calculations in Android code.

### Desktop adapter/UI

Likely touch points:

- `DesktopConfig`: persist the canonical provider id and provide migration/default behavior.
- `DesktopWeatherRepository` / daemon load: request and resolve the canonical observation feed.
- `SettingsWindow`: mirror the Android setting and ordering.
- Desktop header: render the same shared provenance/age state.

Do not change desktop-only behavior independently; the same stored inputs must yield the same
shared resolution result.

### Fetch routing

Both platforms must fetch the canonical current provider even when no visible forecast source uses
it as its per-source actuals provider. Preserve the existing safe WorkManager policies: no
`ExistingWorkPolicy.REPLACE` for running immediate widget work.

## Phase 3: make station trust independent of provider pool size

Extract a shared weighting policy that distinguishes official and personal candidate classes.
Recommended semantics:

1. Compute distance/time weights within each class.
2. Apply the configured personal-station preference to the personal class as a whole, rather than
   multiplying each station and allowing station count to restore its influence.
3. At 100% discount, personal stations contribute zero whenever official data exists.
4. When no official station is usable, allow an explicit personal-only fallback unless the user
   selected strict official-only behavior; label that fallback.
5. Preserve near-zero-distance handling and synthetic-backfill deprioritization.

Before changing production arithmetic, add the exact NWS/Synoptic Mountain View fixture and prove:

- duplicating equivalent personal stations does not increase the personal class's total influence;
- adding one bad personal station cannot move the result beyond the configured class allowance;
- official-only, personal-only, near-zero, stale, and synthetic-only cases remain defined; and
- Android and desktop aggregate through the same shared function.

This phase affects graph actuals and daily extrema as well as current temperature because they share
`ActualTemperatureSeriesBuilder`; run the full cross-platform suite and compare historical outputs
before accepting it.

## Phase 4: separate value freshness from value change

Change live hourly persistence so `fetchedAt` records the most recent successful fetch even when all
weather fields are unchanged, without creating false forecast-history changes.

1. Keep meaningful-change grouping for `hourly_forecast_history` intact.
2. Update or touch matching `hourly_forecasts` rows after every successful source fetch.
3. Implement equivalent behavior in Android Room and desktop JDBC storage.
4. Ensure a failed fetch never advances freshness.
5. Add sparse diagnostics for source, site, row count, and old/new freshness; do not add per-row DB
   logs.

Tests must distinguish:

- unchanged successful fetch -> live freshness advances, history does not gain a false change;
- changed successful fetch -> values and freshness advance, history records the proper snapshot;
- failed fetch -> neither advances; and
- site/source isolation -> only rows belonging to the completed fetch are touched.

## Verification gates

### Focused iteration

1. Run the new `TomorrowIoActualsTest` and current-anchor integration tests in `:shared`.
2. Run focused shared current resolver, actual-series, provider resolver, and station weighting
   tests.
3. Run focused Android repository/persistence and Robolectric widget-header tests.
4. Run focused desktop config/repository/header tests.
5. Ensure every new test class has exactly one measured duration category.

### Full automated gate

Run:

```bash
./scripts/staggered-tests.sh --install
```

This must pass all JVM buckets across `:shared`, `:app`, and `:desktop`, followed by the emulator
suite, before device claims are made.

### Pixel 7 Pro runtime proof

On explicit serial `2A191FDH300PPW`:

1. Verify identity again with `getprop`.
2. Capture pre-fetch database rows, `CURR_FETCH_SOURCE_RESULT`, `OBS_CURRENT_INSERT`,
   `CURR_TEMP_RESULT`, header-state logs, and a screenshot.
3. Reproduce a delayed Tomorrow.io realtime sample while newer recent history exists.
4. Confirm the current anchor advances to the newer history row before realtime catches up.
5. Confirm the next realtime row causes only the genuine provider difference, not the additional
   stale-row catch-up.
6. Cycle NWS, Open-Meteo, Silurian, and Tomorrow.io and confirm the canonical header remains stable
   while forecast curves/source indicators change.
7. Confirm provenance and age match the database timestamp.

### Emulator and desktop parity

1. Repeat source cycling on `emulator-5554` and save a screenshot plus narrowed resolver logs.
2. Build/restart desktop, select the same location and canonical provider, and compare its resolved
   value/timestamp with Android.
3. Verify that a provider lacking observations is labeled estimated/fallback rather than measured.
4. Verify that changing personal-station settings produces the same shared result on both
   platforms.

## Acceptance criteria

1. The Pixel regression selects 75.60 F at 10:00 instead of retaining 72.70 F from 09:34 while
   waiting for the 10:30 realtime response.
2. A newer realtime point replaces the history point naturally; equal-time conflicts prefer
   realtime.
3. Changing the forecast source does not change the current header's provider, anchor, or value.
4. The header exposes derived/estimated/stale provenance instead of presenting all values as
   equivalent observations.
5. Personal-station population cannot overcome the configured class-level influence.
6. Successful unchanged fetches do not produce false stale status.
7. Android and desktop pass focused and full tests and agree at runtime for identical inputs.
8. Pixel and emulator screenshots plus database/log evidence are retained in the completion
   summary.

## Non-goals

1. Do not force forecast providers to agree or alter their forecast curves.
2. Do not declare one provider ground truth from this incident; no thermometer at the exact user
   location was available.
3. Do not redefine historical forecast-accuracy scoring or provider-specific graph actuals in this
   change.
4. Do not clamp genuine temperature changes merely to make the UI look smoother.
5. Do not change cloud, precipitation, or daily-high/low provenance except where the shared station
   weighting phase necessarily affects the already-shared actual-temperature blend.
6. Do not commit or push until explicitly requested.
