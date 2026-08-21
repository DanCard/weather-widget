# Open-Meteo 15-minute cloud actuals with strict source isolation

Status: approved for implementation, 2026-08-21

## Problem

The Open-Meteo cloud graph can present a stale completed-hour value as current. At 12:21 the solid
curve stopped at 11:00 at 100%, even though the same Open-Meteo response reported 12:15 low cloud at
56%. A separate total-cloud NOW dot was tried and rejected: it mixed total cloud with a graph whose
visible metric is low cloud, and it did not repair the solid curve.

The user requires strict provider isolation. NWS views use only NWS data; Open-Meteo views use only
Open-Meteo data. No actual, fallback, blend, or refresh may borrow another provider's values.

## Evidence

1. Open-Meteo hourly `cloud_cover_low` had 09:00-11:00 at 100%, while its current 12:15 low-cloud
   value was 56%.
2. `HistoricalActualsBackfill` currently calls `CloudActualSettling`, which withholds cloud for the
   in-progress hour even though Open-Meteo's cloud values are timestamped instantaneous samples.
3. Open-Meteo's `minutely_15` payload supplies temperature, weather code, precipitation, total cloud,
   and low cloud together. These can be persisted as complete `ObservationReading` rows; no fake
   temperature and no cloud-only table are needed.
4. Both cloud renderers currently attach actual values only to hourly forecast vertices, so merely
   storing quarter-hour rows would not draw them.
5. Earlier validation found that 15-minute sampling is not intrinsically more accurate than hourly
   interpolation against METAR. This change is therefore about recency and faithful provider detail,
   not a claim that finer model output is ground truth.

## Required design

### Source invariant

- `OPEN_METEO` observations, actual curves, refreshes, and fallbacks contain only Open-Meteo data.
- `NWS` observations, actual curves, refreshes, and fallbacks contain only NWS data.
- Unknown/failed observation-only paths return failure or no data; they never substitute Open-Meteo.

### Fetch and parse

- Add `minutely_15=temperature_2m,weather_code,precipitation,cloud_cover,cloud_cover_low` to the full
  Open-Meteo forecast request.
- Parse timestamps in the response timezone and preserve nulls rather than converting them to zero.
- Keep only values at or before the provider's current timestamp for the history/actual series;
  future minutely values remain forecast data and must not be filed as observations.
- Add `cloud_cover` and `cloud_cover_low` to the current-only request so observation refreshes persist
  the current 15-minute temperature and cloud values together.

### Persistence

- Reuse `observations`, keyed by `(stationId, timestamp)`, under `OPEN_METEO_MAIN` and `api=OPEN_METEO`.
- Full fetches backfill the parsed 15-minute rows. Observation-only fetches upsert the current row.
- Remove the cloud settling gate. Timestamp and source provenance decide eligibility; no hour-end
  delay is applied.
- Continue filtering the Open-Meteo actual read to its synthetic station only, preventing any NWS or
  other-source row from entering the series.

### Rendering

- The forecast/prior-day curve remains hourly because the Previous Runs API is hourly-only.
- The solid Open-Meteo history curve uses every stored timestamp, including quarter hours and the
  latest current timestamp.
- Map actual timestamps independently onto the shared time axis. Do not zip actuals to hourly indices.
- Split the solid curve across missing intervals instead of bridging arbitrary gaps.
- Do not restore a separate NOW cloud dot.

### Platform parity

- Shared parsing, models, persistence mapping, gap segmentation, and source rules are common.
- Desktop and Android both persist the same Open-Meteo rows and render the same timestamped series.
- The Android current-temperature path and desktop observation loop both carry current low cloud.

## Is a database change required?

No. `observations` already has nullable `cloudCover` and `cloudCoverLow`, a non-null temperature,
source identity, location, fetch timestamp, and a timestamp-bearing primary key. Open-Meteo supplies
all required values at 15-minute resolution. No Room or desktop SQLite schema version changes are
needed.

## Tests

1. Open-Meteo parsing: timezone alignment, 15-minute timestamps, low-over-total preservation, nulls,
   current-time cutoff, and current-only cloud parsing.
2. Historical backfill: in-progress quarter-hour cloud is retained immediately; temperature remains
   present; source/station identity stays Open-Meteo.
3. Source isolation: NWS observation refresh failure never falls back to Open-Meteo; Open-Meteo reads
   reject other source/station rows.
4. Series/rendering: quarter-hour actual points survive independently of hourly forecast vertices,
   reach the provider timestamp, and split across gaps.
5. Focused shared, desktop, and Android unit/Robolectric tests, followed by desktop live verification
   and emulator verification when an emulator is available.

## Acceptance criteria

- No cloud percentage dot is drawn on the NOW line.
- In the measured scenario the Meteo solid curve does not stop at 11:00; it reaches the 12:15
  Open-Meteo low-cloud value.
- The current solid endpoint uses `cloud_cover_low`, never total `cloud_cover` when low is present.
- No NWS row or request contributes to a Meteo graph, and no Meteo fallback contributes to an NWS
  graph.
- Android and desktop focused tests pass, and live runtime evidence confirms the changed endpoint.

## Approved live-verification follow-ups

After the first implementation was rebuilt, live verification exposed two independent desktop
boundary bugs. The user approved implementing both on 2026-08-21.

1. The daemon and UI may resolve an omitted `useCelsius` setting under different process locales.
   The daemon inherited `LC_ALL=C.UTF-8` and rendered 64.1°F as 17.8°C in genmon, while its UI
   child discarded locale variables and rendered 64.0°F. Resolve legacy omission once from stable
   desktop locale inputs and always persist the resulting boolean in `config.json`.
2. The desktop hourly setup deliberately includes one pre-roll forecast hour, but the new cloud
   actual curve clipped itself to the unaligned nominal window start. At 12:50 with a -1h offset,
   the nominal start was 9:50 while the plotted domain began at 9:00, dropping valid 9:00–9:45
   Open-Meteo rows. Clip actuals to the plotted point domain, still bounded at NOW.
