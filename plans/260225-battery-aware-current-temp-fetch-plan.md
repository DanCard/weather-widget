# Battery-Aware Current Temperature Fetch Plan

## Summary
- Keep interpolation as the primary current-temperature display path.
- Add lightweight "current temp only" network refresh.
- On battery, fetch only opportunistically (no forced periodic current-temp fetch).
- While charging, fetch every 10 minutes only when screen is on/unlocked.

## Goals
- Improve current-temp freshness during active use.
- Avoid battery-draining standalone polling while on battery.
- Preserve existing widget responsiveness and source-toggle behavior.

## Final Product Decisions
- Battery mode: opportunistic only, no stale-age forced fallback.
- Charging mode: fixed 10-minute cadence.
- Charging cadence gate: only when screen is on/unlocked.

## Scope
### In scope
- Lightweight current-temp fetch path (separate from full forecast fetch).
- Policy-driven scheduling by battery/charging/screen state.
- Worker, repository, API, and logging integration.
- Unit and integration tests for policy and scheduling behavior.

### Out of scope
- Reworking full forecast cadence logic.
- Major UI redesign.

## API / Interface Changes
1. Add `CurrentTempFetchPolicy` in `widget` package.
   - `shouldFetchNow(isCharging: Boolean, isScreenOn: Boolean, isOpportunisticContext: Boolean, hasNetwork: Boolean): Boolean`
   - `chargingIntervalMinutes(): Long = 10`
2. Add lightweight API methods:
   - `OpenMeteoApi.getCurrent(lat, lon): CurrentReading`
   - `WeatherApi.getCurrent(lat, lon): CurrentReading`
3. Add repository method:
   - `WeatherRepository.refreshCurrentTemperature(lat, lon, locationName, source: WeatherSource?, reason: String): Result<Unit>`
4. Add worker input flag:
   - `WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY` (Boolean)

## Data/Behavior Model
- Display precedence remains unchanged:
  1. Interpolated estimate from hourly data.
  2. Observed/API current temp as calibration/fallback.
- Current-only fetch updates today's `currentTemp` (per source) and `fetchedAt`.
- Full daily/hourly forecast fetch remains separate.

## Implementation Steps
1. **Policy layer**
   - Create `CurrentTempFetchPolicy.kt`.
   - Encode rules:
     - Charging + screen on + network available => allow 10-minute current-only fetch.
     - Charging + screen off => do not run 10-minute current-only loop.
     - On battery => only opportunistic contexts may fetch (never forced cadence).

2. **Remote lightweight current endpoints**
   - In `OpenMeteoApi`, add current-only request/parse (`temperature_2m`, weather code).
   - In `WeatherApi`, add current-only request/parse from `current`.
   - Normalize into `CurrentReading(temp, condition, fetchedAtMs, sourceId)`.

3. **Repository integration**
   - Implement `refreshCurrentTemperature(...)` in `WeatherRepository`.
   - Respect existing visibility and source rules.
   - Reuse rate limiting (10-minute global guard) to avoid bursts.
   - Only patch current-temp fields for today; do not rewrite high/low paths.
   - Add structured app-log events:
     - `CURR_FETCH_START`
     - `CURR_FETCH_SUCCESS`
     - `CURR_FETCH_SKIP`
     - `CURR_FETCH_FAIL`

4. **Worker path**
   - In `WeatherWidgetWorker`, branch by `KEY_CURRENT_TEMP_ONLY`.
   - Current-only path:
     - run lightweight repository refresh,
     - update widgets from DB,
     - reschedule only if charging and screen on.
   - Keep existing full refresh path unchanged.

5. **Scheduling and triggers**
   - Introduce `CurrentTempScheduler` (or equivalent logic in worker/provider).
   - Charging + screen on:
     - enqueue unique one-time current-only work with 10-minute delay.
   - Screen off or unplugged:
     - cancel charging current-only loop.
   - Opportunistic battery triggers:
     - in `OpportunisticUpdateJobService`, enqueue current-only fetch only when policy permits.
   - No new forced wakeup loop for battery mode.

6. **Screen-state integration**
   - Use existing unlock path (`ScreenOnReceiver`) to start/re-arm charging loop.
   - Add screen-off handling (if not already present in manifest/receiver wiring) to stop charging loop when display turns off.

7. **Resolver compatibility**
   - Keep `CurrentTemperatureResolver` precedence and delta logic.
   - Ensure new observed timestamps refresh delta state naturally.

## Edge Cases / Failure Modes
- API failure: keep prior value; rely on interpolation; log failure.
- Missing today row: skip current-only write and defer to next full fetch.
- Source unsupported for current-only endpoint: skip source and log.
- Plug/unplug or screen on/off thrash: unique work names + replace policy prevent loops.
- Rate-limited call at 10 minutes: log skip reason and continue scheduling.

## Tests
1. **Unit: `CurrentTempFetchPolicy`**
   - Charging + screen on => true.
   - Charging + screen off => false.
   - Battery + opportunistic context => true.
   - Battery + non-opportunistic context => false.
   - No forced stale fallback in battery mode.

2. **Unit: repository current-only refresh**
   - Updates today's `currentTemp` only.
   - Does not modify high/low fields.
   - Honors visibility/rate-limit rules.

3. **Unit/worker tests**
   - `KEY_CURRENT_TEMP_ONLY` invokes lightweight path.
   - Charging+screen on re-schedules 10-minute loop.
   - Screen off/unplug cancels loop.

4. **Integration/instrumented checks**
   - Plugged in + unlocked: current temp refreshes about every 10 minutes.
   - Plugged in + screen off: no 10-minute current-only loop.
   - On battery: no forced periodic current-temp fetch.
   - Opportunistic battery fetch can occur when job runs and network is available.

## Rollout
1. Guard with runtime flag (default off initially): `enableCurrentTempNetworkRefresh`.
2. Validate on emulator + device for 24h logs.
3. Enable by default after confirming:
   - no wakeup/work storms,
   - expected battery behavior,
   - improved active-use freshness.

## Acceptance Criteria
- Current temperature refreshes every ~10 minutes only when charging and screen is on.
- No periodic current-temp polling loop runs on battery.
- Existing interpolation-driven UI updates remain functional.
- Log evidence clearly shows fetch reasons and skip reasons.
