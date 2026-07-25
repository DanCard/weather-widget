# Play Store Open Beta Release & Production Promotion Summary

**Date:** July 25, 2026  
**Version Code:** `26072501`  
**Version Name:** `1.0.5`  

---

## 1. Overview
Published the latest changes (`versionCode = 26072501`, `versionName = "1.0.5"`) to the Google Play Store using Fastlane (`supply` / `upload_to_play_store` API). The release was uploaded to the **Open Beta** channel (`track: "beta"`), verified via Play Store API, and promoted to the **Production** track (`track: "production"`) with review flags enabled (`changes_not_sent_for_review: false`) so that Google Play will review and push the release to production users.

---

## 2. Summary of Changes

### Versioning ([app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts#L120-L123))
- Bumped `versionCode` from `26072301` to `26072501`.
- Updated `versionName` from `"1.0.4"` to `"1.0.5"`.

### Features & Improvements Included in Version 1.0.5
1. **Cache-Only Unplugged Unlock Refreshes (`6310d280`)**:
   - Screen unlock on battery power now acts as a passive presentation refresh (`EXTRA_UI_ONLY`).
   - Prevents stale-data checks from bypassing 4-hour, 8-hour, and low-battery background fetch tiers.
   - Removed obsolete 30% opportunistic forecast threshold while preserving charging unlocks, manual refresh, periodic scheduling, power-connected updates, and passive location handoffs.

2. **Useful Widget Body During Location Handoffs (`eb0f7a58`)**:
   - Passive device location fixes treat candidate locations gracefully so the last complete active-location body remains visible until candidate data is useful.
   - Promotes cached complete locations immediately and allows forward-only new-location coverage after movement grace.
   - Coalesces candidate refreshes with `ExistingWorkPolicy.KEEP` to prevent worker cancellation.
   - Recovered launcher trees by promoting the first complete-body update after an idle delivery gap to one full rebind.

3. **Narrow View Extrema Labeling (`880ece05`)**:
   - Added actual temperature graph endpoint labels in narrow/1x3 views.

4. **Forecast Gap Diagnostics (`6342821f`)**:
   - Enhanced diagnostics for hourly forecast graph gaps.

### Release Notes ([fastlane/metadata/android/en-US/changelogs/26072501.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072501.txt))
- Created release changelog `26072501.txt`:
  > Open beta release: Added actual temperature graph endpoint labels in narrow views, preserved useful widget body during location handoffs, made unplugged screen unlock refreshes cache-only to save battery, and improved forecast gap diagnostics.

---

## 3. Deployment & Verification

1. **Pre-flight Testing (`./scripts/unit-tests.sh`)**
   - Executed full unit test suite across `:app`, `:shared`, and `:desktop`.
   - **Result:** All 2,327 unit tests passed cleanly in 62 seconds.

2. **Signed AAB Build (`./gradlew bundleRelease`)**
   - Successfully compiled and signed `app/build/outputs/bundle/release/app-release.aab` using `RELEASE_STORE_FILE` keystore credentials (`weatherwidget.jks`).

3. **Open Beta Track Upload (`fastlane beta`)**
   - Authenticated using `fastlane/play-store-api-key.json`.
   - Uploaded signed AAB and changelog `26072501.txt` to Google Play (`track: "beta"`).
   - **Result:** Open Beta upload finished successfully.

4. **Production Track Promotion (`fastlane run upload_to_play_store ...`)**
   - Promoted release `26072501` from `beta` to `production` (`track_promote_to: "production"`) with `changes_not_sent_for_review: false`.
   - **Result:** Play Store API promotion completed successfully with submission to Google Play review queue.

---

## 4. Git Status

Per global rules, no automated git commit was made. The following modified/untracked files are ready for user review:

- `modified:` [app/build.gradle.kts](file:///home/dcar/projects/weather-widget/app/build.gradle.kts)
- `untracked:` [fastlane/metadata/android/en-US/changelogs/26072501.txt](file:///home/dcar/projects/weather-widget/fastlane/metadata/android/en-US/changelogs/26072501.txt)
- `untracked:` [summaries/260725-playstore-open-beta-and-production-release.md](file:///home/dcar/projects/weather-widget/summaries/260725-playstore-open-beta-and-production-release.md)
