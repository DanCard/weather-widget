# Refresh freshness and notices: open issues

Status: implemented 2026-11-01 except the release and 4.4 (see Outcome); uncommitted at time of writing. Baseline: commit "Check freshness on tap and announce refreshes".
Current rule: a refresh runs on a tap (Android) or popup open (desktop) when that one source is over 2 h
old at battery ≥ 70% or on a charger. Any refresh a tap starts shows queued and completed messages.

## 1. Ship it

1. Run `./scripts/staggered-tests.sh` (full JVM and emulator gate); the instrumented suite has not run
   on any of today's notice or tap changes.
2. Install on the Pixel and Fold 4, then release. Google Play's 26110102 still fetches on screen-on.

## 2. User-visible fixes

1. **Slow "Tap to refresh" banner.** On the Fold 4 the banner appeared about 6.3 s after the tap, because
   `WidgetRefreshCoordinator` repaints every widget from cache first. Show the notice before
   `repaintFromCache`. Check: tap-to-banner under 1 s on the Fold, from logcat and a screenshot burst.
2. **Is the message right for a shared sync?** A browsing tap's `stale_on_*` refresh is untargeted, but
   its queued message names the tapped source and its completed message goes to every widget. Decide
   whether to target it or word it generally.
3. **App screens.** Opening Forecast History or Observations runs no 2-hour check. Add one, or record
   that only their Refresh buttons fetch. Needs a user decision.
4. **Settings → Refresh data.** It refreshes every source, but "completed" names only the primary one.
   Needs a general sentence, so 19 translations.

## 3. Correctness

1. **Two "last fetched" records.** `ViewingForecastRefresher` reads the LONG marker;
   `SourceStalenessProbe` reads rows and the last success. They can disagree, for example just after a
   location change, costing at most one extra fetch per source per 15 minutes. Make the host read the
   newest of the marker and `getLastForecastSourceSuccessTime`, and extend `ViewingForecastHostContract`.
2. **Close-together queued messages.** The process-wide 3 s gate drops a second queued message even on
   another widget. Tap-only makes this rarer. Make the gate per widget if it is still seen.
3. **`isTesting` leak.** Identify the test that leaves `WeatherDatabase.isTesting` on. Today's setup line
   in `WeatherWidgetProviderEnqueuePolicyTest` hides the leak rather than fixing it.

## 4. Verification gaps

1. **Screen-on regression test.** Add one that a stale displayed source fetches nothing on screen-on.
   Only an emulator run covers this today.
2. **See the wording on screen.** Screenshot the queued and completed banners on the Fold; so far only
   logs and tests prove their text.
3. **Desktop live test.** Age the displayed source past 2 h, then open the popup and cycle the source.
   Expect the messages on the popup, `VIEWING_FORECAST`, and exactly one `REFRESH`.
4. **Glance staleness.** A widget looked at without a tap can be 4 h old, or 8 h after the phone sleeps.
   Review after a few days' use. Android 16 QPR2 `AppWidgetEvent` data could inform this later; it needs
   `compileSdk` 36.1, reports about hourly, and Samsung support is unknown.

## Suggested order

Section 1 gate, 2.1, 3.1, 4.1, then release. Then 2.2 to 2.4, 3.2, 3.3, 4.2 to 4.4.

## Outcome (2026-11-01)

1. **2.1 done.** Fold: 282 ms from request to banner, with the repaint taking 6.55 s. App code always sends
   a widget id; the 6.3 s `targetWidget=all` rows came from adb broadcasts.
2. **2.2 premise wrong.** `stale_on_*` was already targeted (no `target=all` rows on either phone). The
   mismatch was "Tap to refresh": it now forces one source. User's rule: only Settings' Refresh data
   refreshes all sources; everywhere else, a single source.
3. **2.3 user chose check on open**, both platforms. **2.4 done:** "Refresh complete" / "Refresh failed".
4. **3.1 done. 3.2 no change:** the only suppressed rows on the Fold are the removed screen-on batch and
   one rate-limit row from before tap-only. **3.3 done:** seven test classes left `isTesting` on.
5. **4.1 to 4.3 done.** 4.4 needs days of use, so it stays open.
6. **Defects the live tests found, all fixed:**
   - Android: every refresh repaint and resize ran the tap check, so a screen-on paint-debt repaint could fetch.
   - Desktop: a new UI process skipped the popup check, because `showRequestId` is still 0.
   - Desktop: nothing drew popup notices, though they logged `shown`.
   - Desktop: source-switch messages named the previous source (stale closure).
