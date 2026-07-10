# Should Robolectric or instrumented tests be written for the site-fragmentation bug class?

**Date:** 2026-07-10
**Context:** The daily cloud-cover flap (plans/260710-daily-cloud-cover-flap-stale-fragment.md,
summaries/260710-daily-cloud-cover-flap-unify-to-nearest-site.md) is the latest recurrence of
the coordinate-fragmentation bug family (hourly quantize+Selector, forecasts Selector,
in-memory pin sameSite, desktop proximity box — and now the refresh render path).

**Short answer:** one Robolectric parity test is worth writing; instrumented tests are not.
But for this *class* of bug, tests are the weaker half of the answer — the stronger half is
making the chokepoint structurally unavoidable.

## Why the existing unit tests aren't enough for recurrence

`GraphDataLoaderUnifyToNearestSiteTest` proves the helper works. But that was never the
failure mode — the unification logic existed and worked in `WidgetRenderer` all along. The
bug was **wiring**: a render path (`refreshDailyView`) simply never called it. A unit test
on the helper cannot detect a path that bypasses the helper. Every recurrence in this
family has the same shape: *a new or overlooked read path skips the site-selection step*.
That is exactly what unit tests are blind to.

## What a Robolectric test buys

A **cross-path parity test**, following the pattern of the existing
`CurrentTempUnificationIntegrationTest` and the reapply-test pattern:

- Seed an in-memory Room DB with the diagnosed scenario: fresh site (noon cloud 65) +
  stale fragment ~0.03° away (noon cloud 25), stale rows sorting first.
- Drive **each DAILY render entry point** — the onUpdate/startup path and
  `refreshDailyView` — against the same DB.
- Assert both produce the same `cloudCoverRatioOverride` (0.65) for the target day.

This tests the invariant that actually broke ("all render paths agree given the same DB"),
not the helper. It fits repo conventions: no mocking framework, real Room/SQLite under
Robolectric, and it asserts *data* (the `DayData` inputs), not rendered pixels — so the
Robolectric no-font-engine limitation doesn't apply. If a third render path is added later
and enumerated in this test, divergence is caught at build time.

**Honest limitation:** it only covers paths it knows about. A brand-new path nobody adds to
the parity test is still uncovered — which is why the test alone doesn't close the
recurrence loop.

## Why NOT instrumented/emulator tests

Nothing about this bug is device-specific. The divergence lives entirely in JVM-land data
selection (DAO query → list → `firstOrNull`), fully reproducible under Robolectric with
real SQLite. Instrumented tests earn their cost when the bug involves things Robolectric
can't see — RemoteViews stickiness, launcher behavior, WorkManager scheduling, real GPS —
none of which apply here. Emulator runs are expensive (WorkManager teardown stall,
widget-removal hazards on physical devices); save that budget for the categories that
need it.

## The stronger fix for "this keeps happening"

Since the recurring failure is *bypassing the chokepoint*, the durable prevention is
structural, not test-based: make the raw proximity-box DAO queries hard to call directly —
e.g., restrict their visibility so render code must go through `unifyToNearestSite` / the
graph loader, or add a lint/Konsist-style check that flags `getHourlyForecasts*` calls
outside `GraphDataLoader`. A test can only guard enumerated paths; a visibility barrier
guards paths that don't exist yet. Prioritize that over broader test coverage.

## Decision summary

| Option | Verdict | Rationale |
|--------|---------|-----------|
| Unit tests on helper | Done | Proves logic; blind to bypassing paths |
| Robolectric cross-path parity test | **Write it** | Tests the invariant that broke; catches enumerated-path divergence |
| Instrumented/emulator test | Skip | Nothing device-specific; high cost, no added signal |
| Chokepoint enforcement (visibility/lint) | **Best long-term** | Guards paths that don't exist yet |

## Implementation (2026-07-10, same day)

- **Parity test:** `DailyCloudCoverSiteParityRoboTest` — drives BOTH real DAILY paths
  (`WidgetIntentRouter.renderAllWidgetsFromCache` and `WeatherWidgetProvider.onUpdate`)
  against the same seeded two-site DB; observes via ShadowLog on DailyViewLogic's permanent
  `resolveNoonCloudCoverRatio` line; asserts both legs resolve exactly 0.65. Proven to fail
  against the reverted bug (`expected 0.65 but was 0.25`). Robolectric gotchas hit:
  elapsedRealtime starts ~0 so onUpdate's startup debounce swallows the first call
  (advance ShadowSystemClock); `@Inject lateinit repository` must be assigned directly.
- **Chokepoint enforcement:** the caller survey found 16 raw `getHourlyForecasts*` sites in
  10 files, several legitimately raw (write-path dedup) — so full visibility restriction was
  deferred (would be a judgment-call refactor). Implemented instead:
  `architecture/HourlyProximityQueryAllowlistTest` — scans `app/src/main` and fails when a
  NON-allowlisted file calls the raw queries, with remediation guidance; also fails on stale
  allowlist entries. Proven to fire (removed an entry → exact offender reported).
