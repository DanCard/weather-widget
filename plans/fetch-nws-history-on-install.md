# Objective
Fetch NWS historical observations for the previous day when a new user installs the app so they can see history immediately without waiting for daily actuals to accumulate.

# Key Files & Context
- `app/src/main/java/com/weatherwidget/data/repository/CurrentTempRepository.kt`: Handles current NWS observation fetching and inserting into `ObservationDao`. We will add the logic here to backfill 48 hours of NWS history.
- `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`: Exposes repository methods to the worker. We will add a delegator for the backfill function.
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`: The main background worker that syncs data. We will invoke the backfill logic here before calculating daily actuals so that the UI can render history on the first sync.

# Implementation Steps

1. **Add Backfill Logic in `CurrentTempRepository`**:
   - Create a new suspend function `backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double)`.
   - Check if there are any recent observations in `ObservationDao` (e.g., `observationDao.getObservationsInRange` for the past 48 hours).
   - If empty, fetch the closest NWS stations via `getSortedObservationStations`.
   - Iterate over the top 3 stations and attempt to fetch history using `nwsApi.getObservations(stationId, startTimeStr, endTimeStr)` with `startTimeStr` being 48 hours ago and `endTimeStr` being now.
   - For the first station that returns data, map the results to `ObservationEntity` objects and `insertAll` into `observationDao`, then `break`.

2. **Expose in `WeatherRepository`**:
   - Add `suspend fun backfillNwsObservationsIfNeeded(latitude: Double, longitude: Double)` which calls the underlying function in `CurrentTempRepository`.

3. **Trigger Backfill in `WeatherWidgetWorker`**:
   - Inside `doWork()`, right before calling `fetchDailyActuals(...)`, call `weatherRepository.backfillNwsObservationsIfNeeded(location.first, location.second)`.
   - Ensure it's called so that the freshly populated history is immediately picked up by `fetchDailyActuals` and pushed to the widget UI in `updateAllWidgets()`.

# Verification & Testing
- Wipe app data to simulate a new install.
- Launch the widget.
- Monitor logcat for `backfillNwsObservationsIfNeeded` and ensure that `nwsApi.getObservations` is called with a 48-hour window.
- Verify that the Daily View on the widget immediately shows a historical bar for "Yesterday".
- Verify that subsequent refreshes do not trigger the backfill again (because the database is no longer empty for the recent period).