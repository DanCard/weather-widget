# Location change: draw the new site's cache under the banner (Android parity with desktop)

## Reports

Two user bug reports, both 2026-09-29, build 26092501, Pixel 7 Pro:

- 17:44 — "when changing location there's a long wait even if the data is recent for the location."
- 17:48 — "change location doesn't look like it worked. still showing data for Warsaw when I asked
  for mountain view."

## Evidence (device `app_logs`, phone time UTC+2)

```
17:27:13  CONFIG Global location set … Mountain View   → full fetch; MV now has a row for today
17:42:15  CONFIG Global location set … Warsaw
17:42:42  CONFIG Global location set … Mountain View
17:42:42  LOCATION_FETCH_PENDING action=banner_sync_start place=Mountain View
17:43:13  NET_FETCH_COMPLETE durationMs=30271 sources=NWS,OPEN_METEO,…   (force=true)
17:43:40  SYNOPTIC_FETCH … hours=24 rows=31555
17:43:42  LOCATION_FETCH_PENDING action=banner_cleared   ← MV first drawn, 60 s after the save
```

For those 60 s the widgets showed **Warsaw's graph** under "Getting weather for Mountain View…",
although Mountain View's forecast was 15 minutes old. The 17:46:35 repeat was the same, plus the
screen went off before the sync ended (`WIDGET_PAINT_SKIP reason=screen_off` 17:47:01). The
paint-debt path then repainted MV at screen-on (17:47:44, `hourlyCount=480` = MV). That part worked
and is out of scope.

(The 17:45:52 Warsaw save that shows up in the middle of this was a setup-screen pick: `CONFIG OPEN`
17:45:45 → `Global location set … Warsaw` 17:45:52. It isn't a stray replay.)

## Root cause

`LocationUpdater.applyActiveLocationToAllWidgets` picks `Feedback.BANNER` whenever something is on
screen, and the banner branch deliberately keeps whatever render is underneath it until the forced
sync ends. `FullSyncPipeline` only logs `banner_sync_start` on that branch. The `hasTodayRow` probe
exists (`WidgetPaintCoordinator.hasTodayForecastRowAt`), but only the interstitial branch uses it.

Desktop already handles this. `DesktopLocationChangeFeedback.decide` returns
`Decision(feedback, adoptCached)`, and `DesktopUiApplication` sets `forecast = cached` under the
banner when the new site has a row for today (`banner_shown under=new_site_cache`). Android has only
the feedback half. The docs on `LocationChangePaintPolicy.feedback` already describe
`hasRenderToKeep` as "the previous site's render, **or the new site's cached rows for today**", so
Android is the side that diverged.

The wait itself is long because the sync is `force=true`, which bypasses per-source freshness. All
six sources are refetched (NWS alone took 30 s) for a site whose rows are minutes old.

## Fix

1. **Move `decide` into `:shared`.** Add `LocationChangePaintPolicy.decide(hasTodayRowAtNewSite,
   hasRenderOnScreen): Decision(feedback, adoptCached)`, taken from
   `DesktopLocationChangeFeedback.decide`. Desktop calls the shared version, and the desktop object
   keeps only its message helpers. There is one rule for both platforms, per the shared-logic
   convention.
2. **Android: adopt the cache at the start of the forced sync.** In `FullSyncPipeline`'s banner
   branch (the forced sync owns the probe and the paint; never do it on a thread launched from the
   activity), when `hasTodayForecastRowAt(new site)` is true:
   - paint every widget from cache for the new site right away (`updateAllWidgets`, data push), with
     the banner still bound, so the user sees Mountain View under "Getting weather for Mountain
     View…" within about 1 s;
   - log `LOCATION_FETCH_PENDING action=banner_shown under=new_site_cache` (same token as desktop).
     When there's no row for today, log `under=previous_site`.
3. **Drop `force` when the cache was adopted.** Run the rest of the sync with the normal
   location-scoped freshness (`ForecastFetchCoordinator.isStale`): a site fetched 15 minutes ago
   costs about nothing and the banner clears in seconds. A site with no row for today keeps
   `force=true`, as now. The Synoptic backoff bypass for a user location change
   (`userLocationChange`) is unaffected.
4. Check that desktop's `repo.refresh(userLocationChange = true)` doesn't force a full refetch in
   the same way. If it does, apply step 3 there too.

## Tests

- `:shared` `LocationChangePaintPolicyTest`: add `decide` cases for a cached new site (BANNER,
  adoptCached), an uncached new site with a render on screen (BANNER, keep previous), and nothing at
  all (INTERSTITIAL). Move the existing desktop `decide` tests there rather than duplicating them.
- Android Robolectric integration test (FullSyncPipeline + WidgetPaintCoordinator + DB). Seed today's
  forecast row at site B, show site A, run the banner-flagged sync for B, then assert:
  - a data paint for B happens before the network fetch;
  - the sync ran unforced;
  - the banner is cleared at the end.
  Also run the same test with no row for today at B and assert the old behaviour: no early paint,
  and `force=true`.
- On device (Pixel 7 Pro): Warsaw → Mountain View → Warsaw via the setup screen, with both sites
  cached. Expect the new site's graph within about 1 s, `under=new_site_cache`, and `banner_cleared`
  within a few seconds rather than 60.

## Out of scope

- The screen-off paint skip during the sync. The paint-debt repaint at screen-on handled it
  correctly.

## Follow-ups after on-device testing (same day)

User reports on the Pixel 7 Pro drove four changes, each verified on the device:

1. **No banner over the new site's own data.** With the cache adopted, "Getting weather for
   Mountain View…" stayed up for 42 s over Mountain View's graph. `LocationChangePaintPolicy.decide`
   now returns `Feedback.NONE` for a cached site; Android clears the banner as part of the adoption,
   desktop never shows one. A failed refresh after adoption paints no error over the cache.
2. **"Cached" = drawable and fresh.** "Use precise device location" (Wola) adopted a 15-day-old
   daily row from Okęcie, 7 km away: no banner, `hourlyRows=0`, 31 s of nothing. Kyiv adopted a
   5-day-old cache. `hasDrawableCache` requires hourly rows for today through the render's own
   loader, fetched within 24 h (desktop's `loadCached` already bounds hourly age at 24 h).
3. **Expedited location-change sync (API 31+).** The forced sync waited 22 s in JobScheduler. Now it
   starts ~0.1 s after Save. The cache decision also runs inside the startup-cooldown deferral.
4. **Repaint, then clear.** Clearing the banner before a 12 s cold-process repaint left the old
   city bannerless. The banner now stays until the new site is drawn.

Open, not addressed here: in a cold process the cache probe + repaint took 5–12 s (Kyiv's hourly
query scans ~26k rows across 13 sites) — a `performance/` item. The post-install `onUpdate` paint
drew widget 88 with `hourlyRows=0` once (22:13:55).

