# NWS parallel Aviation Weather freshness

## Evidence and root cause

1. On Samsung at 03:15 PDT, KNUQ displayed `Reported 1:15 AM · Fetched 3:11 AM`.
2. The 03:11 timestamp came from the hourly history backfill, not from a successful current web
   reading. The latest current cycle logged `webNewestMs=null` and chose the lagging NWS API row.
3. Android and desktop both produced the same null web result because the current fetch-both leg is
   implemented with Synoptic and the Synoptic token is intentionally blank.
4. The token-free Aviation Weather feed was healthy at the same time. It supplied KNUQ METARs
   through 02:55 on Android's inspection cycle and through 03:15 on desktop's next cycle, while
   `api.weather.gov` remained at 01:15.
5. The source currently runs station jobs concurrently, but each job awaits NWS before invoking
   Synoptic, so the two transports are not actually parallel within a station.

## Approved implementation

1. Add a detailed-result form of the shared METAR batch fetch so callers retain Success, NoData,
   and Failed instead of collapsing every non-success into an empty list.
2. Start one Aviation Weather METAR batch concurrently with the NWS station jobs.
3. Match the returned METAR rows by station ID and compare their newest valid timestamp with the
   NWS latest row using the shared prefer-newest rule.
4. For the nearest three official NWS stations, file a newer Aviation Weather winner under the NWS
   presentation source with web-origin provenance. Keep stations four and five metrics-only.
5. Preserve the standalone `api=METAR` rows and their existing borrowed-actuals role. The new NWS
   row is a presentation copy of the same physical METAR, matching the former Synoptic fetch-both
   behavior without changing the standalone METAR history.
6. Log transport, outcome, both timestamps, delta, and winner. A disabled or failed secondary
   transport must no longer look like a successful web request that merely returned `null`.
7. Apply the same selection and logging behavior to Android and desktop.

## Boundaries and invariants

1. Do not change NWS historical-only daily-extreme pulls; they must continue using
   `api.weather.gov` exclusively.
2. Do not substitute across unrelated station IDs or locations.
3. Reject QC-failed or invalid/future Aviation Weather rows through the existing METAR parser and
   prefer-newest validation.
4. Do not add a wakeup or an additional cadence. The batch rides the existing NWS observation
   cycle.
5. Do not cancel or replace running WorkManager work.

## Schema and migration impact

None. The existing `api`, `isWebFallback`, `isMetar`, and `rawMetar` fields carry the required
provenance on Android Room and desktop SQLite.

## Verification

1. Shared pure tests for detailed METAR outcomes and newest-per-station selection.
2. Android repository tests proving a newer Aviation Weather row wins and a failure stays
   distinguishable.
3. Desktop tests proving the same merge and metrics-only boundary.
4. Focused module tests, debug APK build, and desktop distributable build.
5. Install on Samsung, trigger a refresh, then verify screenshot, `OBS_WEB_API_DELTA` logs, and KNUQ
   database rows against the live NWS and Aviation Weather endpoints.
