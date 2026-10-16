# Every quota refusal shows a banner

Status: **approved and implemented** (user, 2026-10-16: "Keep the error text for a minute. Yes a
partial fetch should get the banner.").

## Request

User, 2026-10-16: "When there is a quota error there should be a banner display about the error."
Prompted by a desktop Refresh on Google (14:42) that a per-minute 429 refused and that showed
nothing where the user was looking.

## What happens today

| Refusal | Android widget | Desktop popup |
|---|---|---|
| Daily quota (`quota_unit 1/d/…`) | pill at once on the view of the refused product (`QuotaScope`, `viewWatermark`) | same (`productQuotaBanner`) |
| Per-minute quota, whole fetch fails | `HTTP_429` source failure → pill only after **3 consecutive failures** (`SOURCE_FAILURE_WATERMARK_THRESHOLD`) | hourly views: pill at once; **daily view: nothing** (it shows a source failure only when `.quota`, i.e. daily) |
| Per-minute quota on hour page ≥ 2 (since `plans/261016-google-hour-pages-kept-when-a-later-page-fails.md`) | **nothing** — the fetch succeeded | **nothing** |

## Proposal

A **quota refusal** = any HTTP 429 from a provider (daily or per-minute; Google today, any source
that answers 429). Other failures keep today's rules (Android's 3-strike threshold included).

1. **Shown at once, on every view** (daily and hourly), both platforms — no 3-failure threshold for a
   429.
2. **Wording** (shared `QuotaNoticeText`, Android strings mirrored, 19 locales):
   - daily quota: unchanged — "GOOGLE WEATHER UPDATES PAUSED · Hourly forecast quota used · resets 12 AM";
   - per-minute, whole fetch refused: "GOOGLE WEATHER RATE LIMITED · Too many requests · 2:42 PM";
   - per-minute on a later page: "GOOGLE WEATHER RATE LIMITED · Hourly forecast incomplete · 2:42 PM".
   The limit ("20 per minute") is on the details page / expanded desktop banner, from the 429 body
   (`quota_limit_value`), never hard-coded. Desktop's old "REQUEST LIMIT REACHED" title now matches the pill.
3. **A per-minute refusal's banner lasts one minute** (`RateLimitNotice.SHOW_MS`), whole or partial,
   through the usual stages (full 8 s, tiny to 24 s, faded to 60 s), then comes down: Android
   schedules a repaint at 60 s (`FailureBannerRepaint` `expiresAfterMs`), desktop re-runs its banner
   check (`rateLimitExpiryJob`). A daily-quota pill is unchanged (until its reset / next success).
   *Decided by the user over "until the next success", which could be hours for a 1-minute limit.*
4. **Plumbing** (as built): `:shared` `RateLimitNotice` classifies (429 and not `1/d/`), owns the
   codes `RATE_LIMITED` / `RATE_LIMITED_PARTIAL` and the 60 s rule. `RawFetch.partialRateLimitDetail`
   is set by `GoogleWeatherApi` for a later-page per-minute 429. Android: `WidgetFetchStateStore`
   `recordRateLimit` (from `recordSourceFetchFailure` on `HTTP_429`, and from the coordinator for a
   partial), read by `viewWatermark` after the streak and product-quota checks. Desktop: the
   `CURRENT_TEMP_STATUS` failure row (whole) or a `FETCH_RATE_LIMITED` log row (partial,
   `RateLimitLog`).
5. The error-details screen (Android) / expanded banner (desktop) show the provider's quota name and
   limit line, as they already do for the daily quota.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `QuotaNotice` classifies 429 bodies: `1/d` → daily scope, `1/min` → RATE_LIMIT, no body → RATE_LIMIT | unit (:shared) |
| 2 | `QuotaNoticeText` per-minute and partial wording, limit from the body | unit |
| 3 | `GoogleWeatherApi`: later-page per-minute 429 sets `rateLimited[HOURLY]` with the page count | unit |
| 4 | Android `viewWatermark`: one `HTTP_429` failure shows the pill (no 3-strike), on DAILY and HOURLY | unit (Robolectric) |
| 5 | Android: non-429 failure still needs 3 | unit |
| 6 | Android: partial rate-limit record shows on both views, cleared by the next full success | integration (coordinator + state manager) |
| 7 | Desktop: a 429 source failure shows on the daily view | unit |
| 8 | Desktop: partial rate-limit log row → banner; next success clears it | integration (repository + DAO + banner text) |
| 9 | Locale parity test passes with the new strings | unit |

## Tests as built

`RateLimitNoticeTest` (classification, 60 s window), `FailureBannerLayoutTest` (pill wording),
`GoogleWeatherApiTest` (partial detail reported only for a per-minute 429), Android
`RateLimitBannerRoboTest` (one 429 shows on DAILY and HOURLY; gone at 60 s; non-429 still needs 3;
daily quota not a rate limit; partial pill + details page; localized pill; repaint at 60 s), desktop
`DesktopRateLimitBannerTest` (presentation with limit, daily stays quota, partial text, repository
logs the partial row) and the two updated `DesktopFetchErrorPresentationTest` cases. Locale parity
passes with the 3 new strings in 19 locales.

## Verification

- `:shared:test`, `:app:testDebugUnitTest` green; `:desktop:test` green except the pre-existing,
  time-of-day-dependent `DesktopNwsWebLegTest` (fails identically without these changes).
