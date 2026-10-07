# One shared rule for Google quota messages on Android and desktop

## Problem

The two platforms show a Google quota refusal through separate code that has drifted apart. Both read
the same facts from `:shared`: `GoogleQuota`, `SourceQuotaBlocks`, `ProviderErrorDetails` and
`RawFetch.quotaRefused`.

| | Android | Desktop |
|---|---|---|
| Classification | Turned into an error code at fetch time (`QUOTA_DAILY`, `QUOTA_HOURLY_FORECAST`, `QUOTA_DAILY_FORECAST`) | Worked out again at render time by regex over `className` + detail (`desktopFetchErrorPresentation`) |
| Which view shows which quota | `WidgetStateManager.viewWatermark(source, product)`: a source-wide failure wins, else that view's product block | Only the hourly view shows a product block (`getLatestProductQuota(src, HOURLY)`). **A daily-forecast quota block never appears on desktop.** |
| Headline | `GOOGLE WEATHER UPDATES PAUSED` | `GOOGLE WEATHER DAILY QUOTA USED` / `… HOURLY FORECAST QUOTA USED` |
| Summary line | `Hourly forecast quota used · resets 12 AM` | `HTTP 429 — resets at 12 AM` among 4–6 body lines |
| Reset time (source-wide) | `nextResetMs(failureTimeMs)` | `nextResetMs(now)`. **Wrong after midnight PT:** a failure from yesterday shows *tomorrow's* reset. |
| Reset time (product) | `nextResetMs(sinceMs)` | the stored `untilMs` |
| Time format | `h a`, else `h:mm a` (`formatResetTime`) | always `h a` |
| Quota name / limit lines | Details screen only | In the banner |
| Text | `strings.xml`, 19 languages | hard-coded English |

## Proposal: `:shared` `QuotaNotice`

The shared code makes the decision and returns structured data, not finished text. Android keeps its
localized strings; desktop gets English text from the same model.

```kotlin
// shared/.../data/remote/QuotaNotice.kt
enum class QuotaScope { SOURCE, HOURLY_FORECAST, DAILY_FORECAST }   // ↔ the three GoogleQuota codes

data class QuotaNotice(
    val scope: QuotaScope,
    val resetAtMs: Long,              // one rule: product block's untilMs, else nextResetMs(failureTime)
    val details: ProviderErrorDetails?,  // quota name, limit, period, metric, request
) {
    companion object {
        /** The ONE "what does this view show" rule: source-wide quota failure wins, else the view's product block. */
        fun forView(
            view: ForecastProduct,
            sourceErrorCode: String?, sourceFailureMs: Long?, sourceDetail: String?,
            productBlock: (ForecastProduct) -> Pair<Long /*untilMs*/, String /*detail*/>?,
        ): QuotaNotice?
        fun scopeOf(errorCode: String?): QuotaScope?
    }
}

/** English copy (desktop, and Android's non-localized fallbacks/tests). */
object QuotaNoticeText {
    fun headline(sourceName: String) = "${sourceName.uppercase()} UPDATES PAUSED"
    fun summary(n: QuotaNotice, resetTime: String)   // "Hourly forecast quota used · resets 12 AM"
    fun short(n: QuotaNotice)                        // "Hourly forecast quota used"
    fun explanation(n: QuotaNotice, sourceName, resetTime)
    fun detailRows(n: QuotaNotice): List<Pair<String,String>>  // Quota / Limit (per day) / Request
    fun formatResetTime(epochMs, locale, zone)       // moved from GraphFailureWatermarkRenderer
}
```

### Android
- `viewWatermark`, `GraphFailureWatermarkRenderer` and `SourceErrorDetailsContent` call
  `QuotaNotice.forView` / `scopeOf` instead of comparing `QUOTA_CODES` each on their own.
  `defaultQuotaText` and `humanReadableErrorCode`'s quota cases delegate to `QuotaNoticeText`.
- The `strings.xml` resources stay, keyed by `QuotaScope`. The text you see does not change.

### Desktop
- `desktopFetchErrorPresentation`'s 429 branch and `desktopHourlyQuotaPresentation` are replaced by
  one `desktopQuotaPresentation(notice)` built from `QuotaNoticeText`:
  title = `GOOGLE WEATHER UPDATES PAUSED`, then the same summary line as the widget, the explanation,
  and the quota/limit/request rows. Same banner and same 8 s / 24 s shrink as now.
- `DesktopUiApplication.updateStatus` passes the **current view's** product (`config.viewMode`):
  daily → `DAILY`, hourly/precip/cloud → `HOURLY`. This fixes the missing daily-forecast banner.
- The source-wide reset time comes from the failure time, which fixes the after-midnight bug.
- Plain HTTP 429 / 401 / 403 / network failures stay in `desktopFetchErrorPresentation`. That copy
  is not part of this change.

## Tests (automated)
- `:shared` `QuotaNoticeTest`:
  - `forView` precedence (source-wide beats product block; hourly view ignores a DAILY block; no
    block → null)
  - the reset rule, including a failure from before midnight PT read after midnight
  - `scopeOf` for every code
  - `formatResetTime` `h a` vs `h:mm a`
  - summary/short/detail rows from a real stored 429 body (fixture already used by
    `ProviderErrorDetailsTest`)
- Android: the existing `GraphFailureWatermarkRenderer` / `SourceErrorDetailsContent` tests must
  pass unchanged (the text you see does not change).
- Desktop: `DesktopFetchErrorPresentationTest` updated for the new title. New cases: a
  daily-forecast block on the daily view shows; on the hourly view it does not. The reset time is
  taken from the failure time.
- Builds: `:shared:test`, `:app:testDebugUnitTest`, `:desktop:test`, then restart desktop and look
  at the popup with an injected `ProductQuotaLog` row.

## Open choice
The desktop banner would take Android's wording ("UPDATES PAUSED" + one summary line), with the
quota/limit lines kept underneath because the desktop has room. The alternative is to move Android
toward desktop's more detailed headline. The plan assumes the first; it changes no Android text.

## Outcome (implemented 2026-10-07; wording: Android's, user approved)
- `:shared` `QuotaNotice.kt`: `QuotaScope`, `QuotaNotice` (`forSourceFailure` by code or by status,
  `forProductBlock`, `productOf(ViewMode)`), `QuotaNoticeText` (English copy + `formatResetTime`).
- Android watermark and error-details screen classify and time the reset through it; localized
  strings are now picked by `QuotaScope`. No Android text changed.
- Desktop: `desktopQuotaPresentation(notice)` replaces the old quota branch and
  `desktopHourlyQuotaPresentation`. `desktopFetchErrorPresentation` now takes `failureMs` (the
  reset follows the failure). The popup banner became `FetchFailureBanner` and is also drawn on the
  daily view, which shows quota notices only (source-wide, or the daily-forecast block).
- Found along the way: product blocks store the bare 429 JSON, which `ProviderErrorDetails.parse`
  did not recognize (it wants `… Detail: <body>`). So the quota name and limit were missing from
  both platforms for product blocks. The parser now accepts a bare JSON body.
