# Refresh notice open issues: recap

Plan: [plans/261101-refresh-notice-open-issues.md](../plans/261101-refresh-notice-open-issues.md).
User prompts: "implement plans/261101-refresh-notice-open-issues.md"; "Tap to refresh on setting screen
should refresh all sources. Other places, just a single source."; "write above recap to summaries/ dir ,
then commit".

Everything in the plan is implemented except the release and 4.4. The final gate passed (6602 unit tests,
120 emulator tests), and the build is on the Pixel, Fold and emulator, with the desktop app restarted on
it. Only Settings → Refresh data refreshes all sources on both platforms; every other refresh forces one.

## Plan items

| Item | Result |
|---|---|
| 1 Gate and install | Done; release not done, by user choice |
| 2.1 Slow banner | Fixed: on the Fold the banner appeared 282 ms after the request, while the repaint took 6.55 s |
| 2.2 Message for a shared sync | Browsing taps were already single-source; the real mismatch was "Tap to refresh", now targeted |
| 2.3 App screens | Opening Forecast History or Observations runs the 2-hour check on both platforms, by user choice |
| 2.4 Settings → Refresh data | Says "Refresh complete" or "Refresh failed", with 19 translations |
| 3.1 Two "last fetched" records | Uses the newest of the two; the contract test covers it |
| 3.2 Per-widget gate | No change: conditional on being seen, and the Fold's only suppressed rows predate tap-only |
| 3.3 `isTesting` leak | Seven test classes left it on; fixed, and the workaround is removed |
| 4.1 Screen-on test | Added; confirmed it fails if the old fetch comes back |
| 4.2 Wording on screen | Both banners confirmed on the Fold |
| 4.3 Desktop live test | Done on an isolated copy: messages on the popup, exactly one `REFRESH` per refresh |
| 4.4 Glance staleness | Still open; needs a few days of use |

## Bugs the live tests found

All four came from commit `f5e16879`, and all are fixed:

1. **Android, untapped repaints:** every refresh repaint or resize ran the "tap" check, so a screen-on
   repaint could fetch and show a banner, breaking the rule that screen-on never fetches.
2. **Desktop, first popup open:** the 2-hour check was skipped whenever a new UI process opened the popup.
3. **Desktop, invisible notices:** nothing drew messages on the popup, though the logs said `shown`.
4. **Desktop, wrong source name:** source-switch messages named the previous source.

Bug 1 has a regression test, and bug 3 has a check that every notice surface draws a pill. Bugs 2 and 4
were only verified live, because they sit inside Compose app wiring.

## Not checked

1. **Android screen messages:** on Forecast History and Observations the queued and completed messages were
   confirmed from logs, not seen on screen.
2. **Android Settings:** the new Refresh data messages were never run on a device, and nobody has reviewed
   the translations.
3. **Desktop windows:** the pill was seen on the popup, not on History or Observations. The desktop
   Observations open was checked in logs only, and the History open was not run live.
4. **Widget tap:** the 282 ms came from sending the broadcast the tap sends, not from a finger. "Tap to
   refresh" only appears when a widget is in its error state.

## Side effects

1. **Stray command:** `am force-stop` was run on the Fold twice by accident. Android denied it both times.
2. **Real API calls:** testing made a few real fetches, including on the isolated desktop copy with real
   keys (NWS, Open-Meteo, OpenWeatherMap, Silurian). That copy was deleted afterwards.
3. **Desktop restart:** the old desktop window process was killed and the popup reopened on the new build.

## Still to do

1. Play release: Play is still on 26110102, which fetches on screen-on.
2. Item 4.4, after a few days of use.
