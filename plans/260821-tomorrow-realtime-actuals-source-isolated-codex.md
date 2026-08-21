# Tomorrow.io Recent-History and Realtime Actuals, Source-Isolated

Date: 2026-08-21

## Decision

Persist Tomorrow.io's available six-hour Timeline lookback as actuals for now. Also accumulate the
source-native realtime endpoint. Keep the two products under separate station ids so the Timeline
rows can be compared with forecasts and deleted independently if later evidence shows they are only
forecast history.

The emulator already contains useful comparison evidence from the prior implementation: elapsed
Timeline values saved as `TOMORROW_IO_MAIN` often differ materially from the forecast snapshots
stored before the target hour. For example, the 08:00 forecast was 58.3 F / 100% cloud while the
later Timeline lookback was 61.3 F / 64% cloud. That disproves a simple unchanged forecast replay,
but it does not prove station-observed truth; the product may still be a revised analysis/hindcast.

## Required Invariants

1. Tomorrow.io forecasts and actuals use only Tomorrow.io data. No NWS, Open-Meteo, or other-source
   fallback may be relabelled as Tomorrow.io.
2. Request no more than the six-hour past window documented for the free core temperature and cloud
   fields.
3. Persist elapsed Timeline rows as `TOMORROW_IO_RECENT_HISTORY` observations.
4. Persist `/v4/weather/realtime` samples as `TOMORROW_IO_REALTIME` observations.
5. Realtime wins when both products contribute to the same nearest-hour bucket; otherwise the
   recent-history row fills the six-hour starting window.
6. Reject legacy `TOMORROW_IO_MAIN` rows on every actual-temperature, current-temperature, cloud,
   daily-extrema, watermark, and stations-UI read path.
7. Do not overwrite the archived forecast originally shown for elapsed hours with the provider's
   later revised Timeline value.
8. Keep the two accepted station ids in storage so comparison and targeted deletion remain possible.
9. Android and Linux desktop apply the same rules.

## Is a Database Change Required?

No. The existing `observations` table already stores timestamped temperature and nullable cloud
cover at arbitrary cadence. No dedicated cloud-history table or schema migration is needed.

A one-time cleanup is still required on both platforms:

1. Delete legacy Tomorrow.io observation rows except `TOMORROW_IO_RECENT_HISTORY` and
   `TOMORROW_IO_REALTIME`.
2. Delete legacy Tomorrow.io daily-history rows once, then recompute them from accepted observations.
3. Keep read-side provenance gates authoritative in case cleanup is interrupted or an old database
   is imported.

If Timeline history later proves unsuitable, delete only `TOMORROW_IO_RECENT_HISTORY`, disable its
backfill, and recompute Tomorrow.io daily actuals. Realtime accumulation remains intact.

## Design

### Shared API and provenance

1. Add `TomorrowIoApi.getRealtime(lat, lon)` for provider timestamp, temperature, condition, and
   cloud cover.
2. Change the hourly Timeline start from a 23-hour absolute timestamp to `nowMinus6h`.
3. Stop manufacturing provider-current state from the nearest hourly Timeline interval; current
   state comes from realtime.
4. Mark Tomorrow.io as supporting the bounded recent-history backfill and emit it with the explicit
   `TOMORROW_IO_RECENT_HISTORY` provenance instead of generic `<SOURCE>_MAIN`.
5. Centralize the two accepted ids and the realtime-over-history merge policy.
6. Preserve native timestamps for temperature. For cloud, use the existing nearest-hour bucket and
   prefer realtime samples within the bucket.

### Android

1. Continue routing the complete Timeline response through `HourlyForecastStore`: future hours go
   to forecast tables and elapsed hours become recent-history observations.
2. Fetch and persist `TOMORROW_IO_REALTIME` in the current-temperature path.
3. Apply the accepted-provenance predicate to the resolver, graph, watermark, daily extrema, and
   stations UI.
4. Run the targeted legacy cleanup before Tomorrow.io data is read or written.

### Linux desktop

1. During a full refresh, add both recent-history observations and a best-effort realtime sample.
2. During observations-only refresh, add the realtime sample only.
3. Persist only current/future Timeline rows to live and archived forecast tables, so the later
   lookback cannot rewrite what was predicted before an elapsed hour.
4. Apply the same accepted-provenance predicate, merge policy, and targeted cleanup as Android.

## Tests

1. API request uses `nowMinus6h`; realtime parsing and errors remain covered.
2. Historical backfill emits Tomorrow rows as `TOMORROW_IO_RECENT_HISTORY` and never
   `TOMORROW_IO_MAIN`.
3. Temperature and cloud paths accept both approved ids, reject legacy rows, and prefer realtime in
   overlapping hour buckets.
4. Android resolver/watermark and cleanup tests cover both approved ids.
5. Desktop full refresh stores recent history plus realtime while forecast persistence excludes
   elapsed Timeline hours; observations-only refresh stores realtime.
6. Existing source-isolation tests continue to pass.

## Verification

1. Run focused shared, Android, and desktop tests, then the broader module test lanes and
   `assembleDebug`.
2. Install on `emulator-5554` only after verifying its identity; do not install on either physical
   device.
3. Select Tomorrow.io, refresh, and inspect logcat plus the Room database.
4. Confirm recent rows use `TOMORROW_IO_RECENT_HISTORY` and `TOMORROW_IO_REALTIME`, legacy
   `TOMORROW_IO_MAIN` is absent, the header resolves from realtime, and both temperature/cloud
   actual lines render without cross-provider rows.
5. Take a screenshot of the resulting Tomorrow.io graph.

## Out of Scope

1. No dedicated cloud-history table.
2. No NWS/Open-Meteo fallback while Tomorrow.io is selected.
3. No claim that six-hour Timeline history is station-observed truth.
4. No paid Tomorrow.io historical/archive integration.
5. No Samsung installation without separate user approval.
6. No commit or push without a separate user request.
