# Location auto-heal: review fixes + split acquisition from following

**Status:** ✅ Implemented 2026-08-12 — 6 commits, `6662d026`..`213180e9`. 1924 unit tests pass; app,
androidTest and `:desktop` compile.

> **⚠️ Release 26081201 landed mid-stream.** Another session committed the Play Store release
> (`e5eda810`, Open Beta + Production) between commit 1 and commit 2, so **commit 1 (the sentinel
> purge) shipped to Production; commits 2–6 did not.** Worth confirming the `.aab` was built from a
> tree containing `6662d026` before relying on that. Nothing here is unsafe half-applied — commit 1
> stands alone — but the upgrade fix is live without the escape-hatch fixes that follow it.
>
> **Deviations from the plan:**
> - **The migration key moved to v2** (plan said nothing about this). Installs that already ran v1 —
>   including local debug builds — are sitting on a *resurrected* sentinel with v1 marked done, so the
>   migration has to re-run to catch them. v2 finds it in the active-location prefs a second time and
>   the purge stops it returning.
> - **`historical_pois` is not cleared**, reversing the review's first recommendation. It is the label
>   store `FriendlyLocationName` reads; the fix is in the readers, not the data.
> - **Instrumented tests not yet run** — unit suite and all three source sets compile, but
>   `scripts/emulator-tests.sh` has not been run against these changes.
>
> **Follow-up found while working:** `default_location_format` / `default_location_named_format` in
> `strings.xml` still render "Default Location: 37.42, -122.08" for a POI-derived coordinate in
> Settings. Not touched here — the text exists in 19 locales and changing English alone leaves the
> rest stale.
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
