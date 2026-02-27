# Findings

- 2026-02-27: Latest emulator test XML reports 2 failures, both in `WeatherObservationsSourceIntegrationTest`.
- 2026-02-27: Failing cases are `activityFiltersObservationsBySource` and `activityStarts_withCorrectSourceFromWidget`.
- 2026-02-27: Failure text indicates the launched activity showed `NWS`-sourced data when the test expected Open-Meteo / `Meteo`.
