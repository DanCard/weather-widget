# Location auto-heal: review fixes + split acquisition from following

**Status:** 📋 Planned, not yet implemented · 2026-08-12
**Plan:** [plans/260812-fix-gps-heal-findings-acquisition-vs-following.md](../plans/260812-fix-gps-heal-findings-acquisition-vs-following.md)
**Review:** [plans/260812-code-review-gps-auto-heal.md](../plans/260812-code-review-gps-auto-heal.md)

## Problem

Yesterday's change set (`d79e7f8c`..`e100d3d1`) replaced the Google-HQ default with an explicit
"No location — tap to set" state. The design is sound, but a review of the subsystem found it doesn't
work end to end:

- **It never reaches its target population.** The migration erases the sentinel from prefs, then
  `ActiveLocationResolver.resolve()` re-derives it from cached `forecasts` rows and re-persists it —
  in the first worker run. Upgraders are silently back on Mountain View weather.
- **"No location" can become permanent.** The heal resolves "where we are" through POI inference, so a
  fresh GPS fix can read as "already located" and never becomes a candidate. Meanwhile the tap the
  error message instructs does nothing, because the no-location paint strips every `PendingIntent`.
- **A first location waits out a grace built for a moving car.** Acquisition (nothing → something) and
  following (site A → site B) share one promotion policy but want opposite biases, so a fresh install
  can show the error for hours after its weather is already cached.

## What will change

| Commit | Change |
|---|---|
| 1 | Purge sentinel-keyed `forecasts` rows; gate the cached-weather fallback during the upgrade window |
| 2 | Heal decisions read *stored* coordinates only; drop the POI coordinate fallback |
| 3 | Make the no-location and error states tappable (open `ConfigActivity`) |
| 4 | Split acquisition from following in `evaluateCandidateUsability` |
| 5 | Delete the dead `allWidgetsAtDefault`/`shouldHealTo`; repoint four doc references |
| 6 | Rename heal → follow/acquire — no behaviour change, own commit |

`historical_pois` is **not** cleared: it is the app's label store (`FriendlyLocationName`), and the
problem is that coordinate resolvers read it, not that it exists.

## Verification

19 enumerated automated tests (see plan §4), including the two regressions that matter: a cleared
install with cached HQ weather must resolve to **null**, and a fresh fix `sameSite` with an old POI
must still produce a candidate. Plus a manual on-device upgrade from a restored pre-upgrade DB backup,
checking `LOCATION_MIGRATION rows_purged=N` in `app_logs`.

## Follow-ups

- Delete `LegacyDefaultLocationMigration` once telemetry shows the migration has run (inherited from
  the previous plan's §8; the purge added here is on the same clock).
- Memory entries naming "GPS auto-heal" need updating after commit 6.
