# Findings

- Existing `UIUpdateReceiver` returned early when screen was off and did not call `scheduleNextUpdate()`.
- Existing `UIUpdateReceiverTest` verified enqueue behavior but not scheduling continuity.
- No dedicated unit tests existed for `ObservationResolver` selection logic.
- No JVM test asserted observation-dot draw behavior in `TemperatureGraphRenderer`.
- `UIUpdateScheduler.scheduleNextUpdate()` is `suspend`; receiver path must call it from coroutine/suspend context.
- Robolectric can provide null `goAsync()` pending result in this test setup, so `pendingResult?.finish()` avoids NPE.
