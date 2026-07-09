# Settings: "Change app language" row (per-app locale deep link)

**Date:** 2026-07-09
**Follows:** `260709-localization-top20-locales.md` (20-locale string resources)

## Goal

Give users an in-app way to change the app's language. Implemented the cheap option: a
Settings row that deep-links to Android's per-app language screen (`ACTION_APP_LOCALE_SETTINGS`,
API 33+). The full in-app picker (AppCompat `setApplicationLocales`, works pre-13) was
deliberately not built.

## Changes

- **`res/layout/activity_settings.xml`** — new "Language" section between Units and
  Feedback & Bug Reports: bold 16sp header + `bg_surface_card` with a 14sp explainer and a
  21sp `rounded_button_navy` button (navy was the one button color unused on this screen;
  white text on the dark fill). Entire section wrapped in `@+id/language_settings_section`
  with `android:visibility="gone"` — the XML default fails safe: forgetting the runtime
  check hides the section rather than crashing an old device.
- **`SettingsActivity.kt`** — on `Build.VERSION_CODES.TIRAMISU`+ the section is made visible
  and the button fires `Intent(Settings.ACTION_APP_LOCALE_SETTINGS, package URI)`.
  `ActivityNotFoundException` → toast (`app_language_settings_unavailable`) for OEM builds
  that ship API 33 without the per-app locale screen. Below 33 there is no per-app locale
  override at all, so the section stays gone — no fallback exists to offer.
- **Strings** — 4 new resources (`language_title`, `app_language_description`,
  `app_language_button`, `app_language_settings_unavailable`) added to base **and all 19
  locale files** (anchored after `use_celsius_label` in each). Base translatable count:
  250 → 254.

The button carries zero locale logic: Android owns the picker UI, persistence, and activity
recreation. The 20-language list it shows comes from `locales_config.xml`, not from this code.

## Verification (emulator-5554, foldable)

- Parity script: 254/254 keys in all 19 locales, zero format-arg mismatches.
- `:app:compileDebugKotlin` + resource processing pass; `installDebug` on 4 devices.
- Driven end-to-end: section renders between Units and Feedback → tap lands in
  `com.android.settings/.localepicker.AppLocalePickerActivity` for Weather Widget listing all
  20 declared languages → after selecting German the whole Settings screen re-renders
  localized ("Sprache", "App-Sprache ändern…", "Fehlerbericht senden", "API-Schlüssel").
- Reset afterward with `adb shell cmd locale set-app-locales com.weatherwidget --user 0
  --locales ""` (empty = system default; also the fast way to flip languages in future tests
  without driving the picker UI).
- Note: first screenshot attempt used a physical device with its screen off → all-black PNG.
  Use emulators for UI verification.

## Known gap / future

- **Widgets don't re-render on language change**: RemoteViews keep old-language text until the
  next update cycle. Fix would be catching `ACTION_LOCALE_CHANGED` (and app-locale changes)
  → fire `ACTION_REFRESH`. Not yet implemented.
- Test plan for localization (incl. a `LocaleSwitchIntegrationTest` covering exactly this
  flow): `notes/260709-localization-testplan.md`.
