# Battery-gated opportunistic current-temperature fetch

## Goal

Reduce the battery-mode current-temperature polling cost while retaining the existing charging and
manual-refresh behavior:

1. Schedule the opportunistic job every 45 minutes instead of every 30 minutes.
2. Do not keep the periodic opportunistic job scheduled at 65% battery or below, whether plugged
   in or unplugged.
3. Recheck the battery gate when the job starts so a persisted job cannot fetch after the battery
   has fallen to 65% or below.
4. While unplugged, target only the configured primary source. While charging, retain the current
   all-visible-source behavior.

“Above 65%” is an exclusive boundary: 66% is allowed and 65% is blocked.

## Evidence

- Connected-device `app_logs` showed roughly 45–47 opportunistic job callbacks per full day at the
  current 30-minute period.
- The Samsung recorded real unplugged, screen-off network fetches from this path.
- `OpportunisticUpdateJobService` currently has no battery-level gate and enqueues a source-agnostic
  current-temperature worker, which makes `CurrentTempRepository` target all visible sources.
- The worker already accepts `KEY_TARGET_SOURCE`, so primary-only behavior can reuse the existing
  targeted-fetch path without changing repository semantics.

## Implementation

1. Put the 65% threshold, 45-minute interval, schedule decision, and battery-mode target-source
   decision in `CurrentTempFetchPolicy`.
2. Extend `CurrentTempUpdateScheduler.enqueueImmediateUpdate` with an optional target source and
   write it to the worker input data.
3. In `OpportunisticUpdateJobService`:
   - read current charging state and battery percentage before scheduling;
   - cancel instead of scheduling at 65% or below;
   - recheck the same gate in `onStartJob`;
   - pass the primary source ID only while unplugged;
   - use the 45-minute periodic interval.
4. Re-evaluate scheduling on power connect/disconnect so the persisted job always reflects the
   current battery gate; the separate charging loop remains responsible for low-battery charging.
5. Pass battery percentage into the worker policy as a defense-in-depth check for already-enqueued
   opportunistic work.

## Verification

1. Pure policy tests:
   - 66% unplugged schedules/fetches;
   - 65% and below do not;
   - charging does not bypass the opportunistic job's battery cutoff;
   - unplugged targeting selects only primary;
   - charging targeting leaves the source unrestricted;
   - interval is 45 minutes.
2. Scheduler test confirms the optional target source reaches `KEY_TARGET_SOURCE`.
3. Focused unit/Robolectric tests for the policy, scheduler, service, and power receiver.
4. Compile the app unit-test sources and run `git diff --check`.
5. If focused JVM verification passes, install on an emulator and verify the registered JobScheduler
   interval and input-source breadcrumbs before declaring runtime completion.

## Verification results

1. Focused policy, scheduler, JobService, and power-receiver tests passed.
2. `:app:assembleDebug` passed and `git diff --check` reported no errors.
3. On an API 36 emulator, 66% unplugged registered job 1002 with a 45-minute periodic interval.
4. Forcing that job at 66% logged `charging=false battery=66%` and enqueued the current-temperature
   worker with `target=NWS`, the emulator's configured primary source.
5. Changing the unplugged battery to exactly 65% before forcing the persisted job caused
   `OpportunisticUpdateJobService` to cancel job 1002 without enqueuing a current-temperature fetch.
6. The emulator's simulated battery state was reset after verification; the emulator was left
   running.
7. At 20% while charging, the emulator had no opportunistic job 1002, confirming that charging does
   not bypass the strict threshold. The separate charging refresh loop was left unchanged.
