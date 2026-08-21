# Open-Meteo model-current provenance correction

## Evidence

1. Open-Meteo's Forecast API documentation says its current conditions are based on 15-minute
   weather-model data. The live response at the configured desktop location also returned the same
   temperature for `current`, the latest `minutely_15` row, and the current hourly row.
2. The Forecast API's `past_days` option returns archived forecast-model output. Open-Meteo's
   separate Historical Weather API is reanalysis, but this application does not call that API.
3. Android currently stores `getCurrent()` samples and elapsed `minutely_15` rows as observations.
   Desktop does the same, and both platforms also promote Forecast API `past_days` daily values into
   `daily_history` actual columns. Those writes create temperature/cloud "actual" curves from model
   output.

## Required design

1. Make Open-Meteo forecast-only in the shared source capability model:
   `historicalDataKind=NONE`, no temperature actuals, no cloud actuals, and no historical-actuals
   backfill. This is the authoritative read/write gate shared by Android and desktop.
2. Do not call Open-Meteo from the current-observation loop and do not let its `current` model field
   update the header. The header may still resolve normally from the ordinary forecast timeline,
   but it receives no observation anchor, correction, or observed timestamp.
3. Stop requesting extra `past_days` solely to mint graph actuals. Preserve ordinary forecast rows
   and accumulated forecast snapshots; those remain useful for showing the model and its revisions.
4. Stop promoting Open-Meteo Forecast API daily `past_days` rows into `daily_history` actual fields.
5. Add an idempotent one-time cleanup on both platforms that deletes legacy Open-Meteo observation
   rows and Open-Meteo daily-history actual rows. Forecast and forecast-history tables are untouched.
6. Keep provider isolation: do not substitute NWS observations or any other API's data when
   Open-Meteo is selected.

## Is a database change required?

No schema or migration is required. Existing capability gates can express the corrected
provenance. Cleanup is data-only and is guarded by a persisted completion marker on each platform.

## Verification

1. Shared tests pin Open-Meteo as forecast-only and prove historical backfill returns no rows.
2. Android tests prove the one-time cleanup removes Open-Meteo observations/daily history and does
   not remove forecast storage.
3. Desktop DAO/service tests prove cleanup is idempotent and observation-only refresh does not call
   Open-Meteo or return a provider-current value.
4. Run shared, desktop, and Android unit suites plus `assembleDebug`.
5. Rebuild/restart desktop with Open-Meteo selected and verify the header resolves only from the
   forecast timeline while temperature/cloud actual curves are absent. Install only on the emulator
   and verify the same widget behavior there; do not install on physical devices.
