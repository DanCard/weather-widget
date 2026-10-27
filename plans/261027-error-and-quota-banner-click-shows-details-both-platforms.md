# Error / quota banner click shows the detailed error — one shared details builder

User, 2026-10-27: "yes remove dismissal. When clicking on an error or quota banner, detailed error
information should show." (Follows §7 of `261027-android-desktop-duplicate-code-audit-round-three.md`.)

## Done
- Desktop dismissal removed: the details card's × and `dismissedErrorTimestamp` (which hid every
  failure, quota block and partial rate limit older than the dismissal) are gone. A banner now stays
  until the failure or block ends, as on Android. Clicking the card still folds it back to the pill.
  Files: `DesktopUiApplication`, `DesktopWidgetPopup`, `DesktopWindowHosts`.

## State today
| | Android (pill → `SourceErrorDetailsActivity`) | Desktop (pill → `FailureDetailsCard`) |
|---|---|---|
| Opens on click, errors and quota alike | yes | yes |
| Headline / summary / explanation | yes | title + body lines + retry line |
| Error code text | yes | in the body, for some codes |
| Request, HTTP status, provider message | yes | request only (quota/429) |
| Quota name, limit, metric, window start, resets | all | name + limit only |
| Failure count | yes | no |
| Last attempt | yes | yes |
| Last successful update | no | yes |
| Full provider response | "Show response" toggle | no |
| API-key Settings / background-data buttons | yes | no |

The two builders — Android `SourceErrorDetailsContent.build` and desktop `desktopFetchErrorPresentation`
/ `desktopQuotaPresentation` / `desktopPartialRateLimitPresentation` — describe the same failure
differently. A twin under different names.

## Plan
1. **`:shared` `SourceErrorDetails`**: inputs — source name, failure count, last error code, failed-at,
   detail, the product block (if any), the rate limit (if any), last success, now, locale, zone.
   Output — `kind` (QUOTA / RATE_LIMITED / AUTH / DATA_RESTRICTED / FAILING), headline, summary,
   explanation, `rows: List<Row(label: Label, value)>`, `rawResponse`, `offerApiKeySettings`. Moves
   Android's choice logic (product block → rate limit → source failure; code → explanation) and
   parses with the shared `ProviderErrorDetails`. `Label` is an enum; Android maps it to its existing
   string resources, desktop to `Label.english` (the `RainLoopLoadError.Line` pattern). A parity test
   pins each English text to `values/strings.xml`.
2. **Host** for the reads: Android `WidgetStateManager` (as now); desktop `DesktopQuotaState` +
   `getLatestCurrentTempStatus` + `getLastSuccessfulFetch`, plus a failure count (consecutive failed
   status rows for the source).
3. **Android** `SourceErrorDetailsContent.build` becomes a thin call; the page gains a "Last successful
   update" row (desktop already shows it; it tells you how stale the screen is).
4. **Desktop** `FailureDetailsCard` renders the same content: headline, summary, explanation,
   label/value rows (selectable text), a "Show response" toggle for the raw body, and an "API keys…"
   button opening Settings for auth errors. The pill's text keeps coming from `FailureBannerLayout`.
   The three desktop presentation functions are deleted; the daily-view filter that used their
   `quota` / `rateLimit` flags reads `kind` instead.

## Tests (fail on today's code)
- Shared: product block wins over a rate limit; 429 → RATE_LIMITED with the minute limit row;
  Google daily 429 → QUOTA with name/limit/metric/window/resets; 401 → AUTH + settings offer;
  network error → FAILING with no response; raw body carried.
- Desktop compose test: clicking a quota pill shows the window-start and resets rows and the response
  toggle; there is no × (dismissal gone).
- Android: `SourceErrorDetailsActivityRoboTest` unchanged, plus the last-success row.
- Parity: `Label.english` == `values/strings.xml`.

## Verification
`scripts/staggered-tests.sh`; desktop restart; on the Pixel, tap a failure pill (the emulator with
networking off for a FAILING case).

## Progress
- 2026-10-27, on `main`: done as planned.
  - Desktop dismissal removed (× and `dismissedErrorTimestamp`; the write-only
    `currentTempFetchTimestamp` too).
  - `:shared` `SourceErrorDetails` (`build` → typed rows; `English` text). Android
    `SourceErrorDetailsContent` maps it to resources (page tests unchanged).
  - The pill passes its view's product (`SourceErrorDetailsActivity.EXTRA_PRODUCT`), so the page
    names the same block as the pill. It used to take the first block in enum order.
  - New "Last successful update" row on both platforms (Android string in all 20 locales).
  - Desktop card: rows, "Show full response", "API key settings", Close. Facts come from
    `desktopSourceErrorDetails`, with the new `DesktopWeatherDao.getCurrentTempFailureStreak`.
  - The desktop-only wording ("AUTHORIZATION FAILED", "No other weather provider was substituted")
    is gone with `desktopFetchErrorPresentation` / `desktopQuotaPresentation` /
    `desktopPartialRateLimitPresentation`.
  - `FailureBannerLayout.humanReadableErrorCode("NO_COVERAGE")` now matches strings.xml ("Not
    available in this region").
  - Tests: `SourceErrorDetailsTest` (shared), `SourceErrorDetailsEnglishParityTest` (app),
    `DesktopFetchErrorPresentationTest` rewritten over the real DAO, and a `DesktopUiTest` case
    (click pill → rows, response toggle, settings, close; no ×).
