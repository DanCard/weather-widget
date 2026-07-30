# Prevent hourly history repair requests from being discarded

## Reported behavior

On `emulator-5556`, widget 2's NWS hourly temperature graph showed a nearly
horizontal historical line from about 7 PM through 7 AM followed by a large
drop. The detailed history returned at about 7:09 AM.

## Runtime evidence

1. The affected widget was still configured for `TEMPERATURE`, `NWS`, offset
   `0`, and `WIDE` zoom. A 7:19 AM screenshot showed the recovered graph.
2. At 6:36 AM, the renderer diagnosed `max_gap_min=563` and requested an
   observation backfill for widgets 2 and 7.
3. The unique observation-backfill lane was already occupied by work that
   started at 8:33 PM the prior evening. That old request did not finish until
   6:36 AM and its fetch window ended the prior evening, so it could not fill
   the new overnight gap.
4. Both new 6:36 AM requests used `ExistingWorkPolicy.KEEP`. WorkManager
   therefore discarded them while the old request was active, but the handler
   still recorded its 30-minute per-widget cooldown.
5. From 6:37 through 7:08, graph diagnostics remained sparse:
   `max_gap_min=563`, 25 points in the visible prior-evening segment, and
   9–15 points in the current-morning segment.
6. After the cooldown expired, a new request ran from 7:08:49 through 7:08:59.
   At 7:09, coverage changed to `latest_gap_min=9 max_gap_min=15`, with 67
   prior-evening and 93 current-morning points. The restored graph timing
   matches the report.
7. Startup fast-path renders also intentionally contain only 24 forecast
   points and no observation query, but each observed startup render was
   followed by a full observation-backed render. That transient behavior did
   not explain the roughly 30-minute stale-history interval.

## Root cause

Observation history repair is required follow-up work, but its unique
WorkManager lane used the redundant-work policy `KEEP`. A newer request can
cover a later time window than an already-running request, so the requests are
not interchangeable. Dropping the newer request leaves the graph stale until
the separate cooldown permits another attempt.

## Implementation

1. Move observation-backfill request construction and collision policy into
   `WidgetWorkScheduler`, which already owns widget WorkManager names,
   requests, and collision policies.
2. Enqueue observation repair with `APPEND_OR_REPLACE`. This preserves a newer
   required repair behind active work without cancelling a running
   `WeatherWidgetWorker`.
3. Keep the existing short randomized delay and network constraint.
4. Extend the existing sparse `OBS_HOURLY_BACKFILL_REQ` database event with
   the selected collision policy and request ID. Existing `REQ`, `RUN`,
   `START`, station, `RESULT`, coverage, and render diagnostics already made
   this incident diagnosable; no new per-render persistent logging is needed.

## Regression coverage

1. Add an enqueue-policy test that verifies observation repair uses the
   dedicated unique lane, `APPEND_OR_REPLACE`, and the expected worker input.
2. Add a WorkManager collision test that leaves one observation repair
   enqueued, schedules a newer repair, and verifies both request IDs remain in
   the unique chain. This reproduces the production failure that `KEEP` would
   discard.
3. Run the focused policy/collision tests and the hourly observation-backfill
   unit tests.
4. Run the app short and long duration test lanes, plus Kotlin compilation.

## Runtime verification

Install the debug build on `emulator-5556`, preserve widget 2's source, view,
offset, and zoom, and confirm that the recovered graph and coverage diagnostics
remain healthy. Keep the two-request collision assertion in the deterministic
Robolectric WorkManager test rather than inserting test repair work into the
emulator's live queue.

## Verification results

1. The focused backfill decision, enqueue-policy, and WorkManager collision
   tests passed.
2. `:app:testByDurationDebugUnitTest` passed all short, medium, long, and
   localization lanes.
3. `:app:assembleDebug` passed.
4. The exact debug APK was installed on `emulator-5556`. Widget 2 retained
   `TEMPERATURE`, `NWS`, offset `0`, and `WIDE` zoom.
5. The settled widget showed detailed overnight history. Persistent diagnostics
   reported healthy coverage (`latest_gap_min=6 max_gap_min=15`), 67 points for
   the visible prior-evening segment, and 98 points for the current-morning
   segment.
