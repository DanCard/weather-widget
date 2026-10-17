# History Refresh reports a refused forecast part — both platforms, shared rule

- **Trigger:** Fold 4, 21:02. A Google History Refresh toasted "Refreshed Google". `forecast/hours` got a 429:
  `ForecastHoursQueriesPerDay` was exhausted at 17:24 PT (150/day; two emulators spent ~58). The
  user raised it to 200.
- **Cause:** "ok" meant only that the source answered; a fetch with one product refused still counts as ok.
- **Change:**
  - `:shared` `RefreshOutcome` (Failed / Refreshed / Refused(scope, until) / HourlyIncomplete):
    the rule, `logDetail`, and the English `text`.
  - Android `ForecastHistoryActivity`: reads `WidgetStateManager` product quotas and partial rate
    limits; localized text from existing strings; `LENGTH_LONG`.
  - Desktop: `historyRefreshOutcome` reads `PRODUCT_QUOTA` / `FETCH_RATE_LIMITED` rows. Both
    History refresh paths show it on the History pill; `RefreshNoticeState.showOutcome` replaces
    the "queued" pill inside the 4 s window.
  - Both platforms log `HISTORY_REFRESH_DONE … refused=…`.
- **Verification:** 5,467 unit tests; a parity test checks Android English == shared text; installed on
  the Fold; desktop restarted. Plans: `plans/261016-history-refresh-reports-refused-forecast-part.md`,
  `plans/261016-history-refresh-outcome-shared-desktop-parity.md`.
