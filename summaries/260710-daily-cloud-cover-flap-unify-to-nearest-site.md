# Daily cloud-cover flap: unify hourly reads to nearest site

**Date:** 2026-07-10
**Plan:** [plans/260710-daily-cloud-cover-flap-stale-fragment.md](../plans/260710-daily-cloud-cover-flap-stale-fragment.md)
**Status:** Implemented & verified on device (Samsung SM-F936U1)

## Problem

The Samsung daily view showed Monday's cloud cover flipping between 65% (matching desktop
and emulator) and a stale 25% every update cycle. Not different weather stations and not a
live fetch — both values came from the phone's own forecast cache. The phone had three
cached copies of the NWS forecast keyed by slightly different coordinates (from device
movement on July 8/9); only the current site kept being refreshed. NWS revised Monday's
forecast upward last night (noon cloud 31% → 65%), so the frozen July-8 copy (25%) suddenly
disagreed visibly. The `onUpdate` render path unified rows to the current site (65%), but
`refresh_action_cache_first` passed raw proximity-box rows where
`DailyNoonCloudCover.firstOrNull()` picked the stale fragment's noon row (25%) — and since
that pass paints last, the widget usually sat in the wrong state.

## What changed

- **`GraphDataLoader.kt`** — new `unifyToNearestSite(rows, lat, lon)` helper, extracted
  from `WidgetRenderer`'s proven inline logic: pick the cached coordinate site nearest the
  widget location, keep only that site's rows (sub-precision GPS-jitter fragments included
  via `LocationMatch.sameSite`), drop frozen sites left by earlier GPS fixes. Falls back to
  the nearest available site when the current one has no rows (cached data with a small
  location offset beats a blank widget).
- **`WidgetIntentRouter.kt`** — applied the helper at all three raw hourly loads:
  - `refreshDailyView` (the load that caused the flap)
  - the today-actuals aggregation path
  - the "has hourly data?" gate (so a stale fragment can't mask genuinely missing data)
- **`WidgetRenderer.kt`** — replaced its inline unification block with the shared helper
  (behavior-neutral; one source of truth).
- **Tests:**
  - `GraphDataLoaderUnifyToNearestSiteTest` (app unit, `@Category(ShortDuration::class)`):
    4 cases using the actual coordinates from the diagnosed device, including the
    stale-fragments-first list ordering that triggered the bug, sub-precision fragment
    retention, nearest-site fallback, and empty input.
  - `DailyNoonCloudCoverTest` — new hazard-documenting case: the shared resolver takes the
    *first* noon row (its model has no coordinates), so callers must unify sites first.

## Verification

- Full `:app:testDebugUnitTest` and `:shared:test` suites green.
- On device after `installDebug`: broadcast `ACTION_REFRESH`; every
  `refresh_action_cache_first` render now logs `ratio=0.65 hourlyRows=226`, identical to
  `onUpdate` (previously `ratio=0.25 hourlyRows=474`). Flap gone; Monday matches desktop
  and emulator.

## Follow-ups / notes

- Rule going forward: any **new raw proximity-box hourly read must go through
  `unifyToNearestSite`** (or the graph path's `sameSite` + stitcher). The DB box query
  intentionally over-fetches; selection is the caller's job.
- The stale fragments themselves are legitimate data (device was physically elsewhere);
  1-month retention ages them out. The bug was selection, not storage.
- Repo gotcha rediscovered: app unit tests **must** carry a `@Category` bucket or
  `validateUnitTestDurations` fails the build.
- Changes are uncommitted (user to decide on commit).
