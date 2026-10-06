# Error pill: details page, and the crash every pill tap caused

## Evidence (Fold, 2026-10-06)
- Every tap on the failure pill (14:48, 14:49, 14:51–14:52) crashed:
  `IllegalStateException: You need to use a Theme.AppCompat theme` in
  `BackgroundDataResolutionActivity.onPostCreate`. Several quick crashes → "crashed too many times" →
  process blocked as bad, so broadcasts and jobs stopped and the widget sat unpainted (`--°`) until a
  foreground `am start`.
- Cause: an `AppCompatActivity` declared with `@android:style/Theme.Translucent.NoTitleBar`
  (since f9478f08).
- The pill sent every non-auth failure — a 429 included — to Android's background-data screen.

## Change
1. `Theme.WeatherWidget.Translucent` (AppCompat parent, translucent window) for the trampoline.
   Robolectric launch test; it fails with the exact crash under the old theme.
2. The pill opens the new `SourceErrorDetailsActivity` (source id extra) for every failure:
   headline, plain-language summary/explanation, and the provider's details — request, HTTP status,
   provider message, quota name, limit and period, metric, quota window start, reset, failures in a
   row, last attempt — plus the full response (pretty JSON) behind a toggle. API-key Settings is a
   button for 401/403/ACCESS_ERROR; "App data usage" (Android's per-app data usage screen, what the
   pill opened before) is always offered at the bottom (user, 2026-10-06), and Back returns to the page.
3. The last failure's redacted message is stored with its code and time (`source_fail_detail_<id>`,
   ≤ 8000 chars, cleared on success); `ProviderErrorDetails` (`:shared`) parses it.
4. Desktop: the daily-quota banner adds the quota name/limit and request lines.
5. 25 strings × 20 locales (lint fails on missing translations).

## Verified
- Unit/Robolectric: parser (Fold body, plain text, no body), content builder (quota, auth, data
  restricted, none), page render + response toggle, trampoline launch, desktop banner.
- Fold: pill tap opens the page, no crash; rows and response match the stored 429. The stored body
  showed `quota_limit_value: 70` at 14:56 — the raised limit was already used up then.
