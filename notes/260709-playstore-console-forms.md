# Play Console forms — prepared answers (2026-07-09)

Everything below reflects the actual binary as of this date: Crashlytics **without**
firebase-analytics (removed 2026-07-09), location shared only with enabled weather
providers, all data local-only with ~30-day retention.

## 1. Privacy policy URL

```
https://github.com/DanCard/weather-widget/blob/main/PRIVACY_POLICY.md
```
(Repo is public; file added 2026-07-09. Must be pushed to GitHub before submitting the form.)

## 2. Data Safety form

**Does your app collect or share any of the required user data types?** Yes

### Location → Approximate location & Precise location
- Collected: **Yes** · Shared: **Yes** (sent to the weather providers the user enables —
  NWS, Open-Meteo, and optional keyed providers — solely to retrieve forecasts)
- Processed ephemerally: **No** (cached on device ~30 days)
- Required or optional: **Optional** — users can deny location and pin a manual location
- Purposes (collect): **App functionality**
- Purposes (share): **App functionality**

### App info and performance → Crash logs
- Collected: **Yes** (Firebase Crashlytics) · Shared: **No** (Google acts as service provider)
- Processed ephemerally: **No**
- Required or optional: **Required** (automatic)
- Purpose: **App functionality** (stability)

### App info and performance → Diagnostics
- Collected: **Yes** (Crashlytics performance/diagnostic signals accompanying crash reports)
- Same answers as crash logs.

### Device or other IDs
- Collected: **Yes** (Firebase installation ID accompanies crash reports)
- Shared: **No** · Purpose: **App functionality**

### Everything else (personal info, financial, contacts, messages, photos, audio,
browsing, search history, installed apps, calendar, fitness): **Not collected**

**Data security section:**
- Data encrypted in transit: **Yes** (all providers HTTPS)
- Users can request deletion: **No account system; uninstalling deletes all local data** —
  select "No" for the account-deletion requirement (no accounts exist), and note local-only
  storage in the optional description.

## 3. Location permissions declaration (ACCESS_BACKGROUND_LOCATION)

**Feature that uses it:** Home-screen weather widget that follows the device.

**Declaration text (≤ 500 chars, suggested):**
> Weather Widget is a home-screen widget. Its core feature is showing current weather for
> the user's present location without opening any app. Widget refreshes run in the
> background by design (the widget IS the product), so passive last-known-location reads
> require background location. The app never activates GPS in the background; it only
> reads cached fixes. Users may instead pin a fixed location and deny background access —
> the widget then works without it.

**Demo video requirements** (record before submitting; ~30s screen capture, upload to
YouTube unlisted):
1. Fresh install → MainActivity shows the location disclosure card.
2. Tap "Grant permission" → foreground location runtime dialog → allow.
3. The in-app **prominent disclosure dialog** appears (title: background location
   disclosure) → tap Allow → system "Allow all the time" settings screen → set it.
4. Show the widget on the home screen updating with local weather (app closed).

The in-app flow already complies (prominent disclosure dialog before the system request,
in `MainActivity.showBackgroundLocationDisclosureDialog()`); the video just has to show it.

**Fallback if the declaration is rejected:** the app is functional without background
location (fixed/pinned location mode) — worst case, remove the permission from the
manifest for v1.0 and re-add with a stronger declaration later. No code change needed
beyond deleting one `<uses-permission>` line.

## 4. App content — other declarations

- **Ads:** No ads.
- **Target audience:** 18+ / general — NOT designed for children (avoids Families policy).
- **News app:** No.
- **COVID-19 tracing:** No.
- **Data deletion:** no accounts; local-only.
- **Government app:** No.
- **Financial features:** None.
- **Health:** None.
- **Category suggestion:** Weather. **Contact email:** cardenas.kin@gmail.com (required, shown publicly).

## 5. New-personal-account testing gate (if applicable)

Accounts created after Nov 13, 2023 need a **closed test with ≥12 testers opted in for 14
continuous days** before applying for production access. Internal track (current
`fastlane beta` target) has no gate — use it immediately; recruit the 12 testers on a
closed track in parallel.

## 6. Upload runbook

1. Push `PRIVACY_POLICY.md` (+ these prepared changes) to GitHub.
2. `bundle exec fastlane validate` — checks the service-account key + metadata without uploading.
3. `bundle exec fastlane beta` — rebuilds `bundleRelease` (picks up the R8 keep-rule fixes;
   do NOT upload any AAB built before 2026-07-09) and uploads to the internal track.
4. Console: fill Data Safety + declarations from this doc, attach screenshots
   (`fastlane/metadata/android/en-US/images/`), submit for review.
5. Bump `versionCode` before every subsequent upload.
