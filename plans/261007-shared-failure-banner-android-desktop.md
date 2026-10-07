# One failure banner for Android and desktop (`:shared`)

## Problem

User, 2026-10-07: centring the Android quota pill left desktop's banner at the top — "I'm tired of
every day pointing out discrepancies between desktop and android." The banner existed twice:

| Rule | Android | Desktop |
|---|---|---|
| Position | `GraphFailureWatermarkRenderer.calculateLayout` | `Alignment.TopCenter` in `FetchFailureBanner` |
| Size / padding / fonts / colours | renderer constants | separate dp/sp/Color literals |
| Wording | "<SOURCE> UPDATES PAUSED" + "<which> quota used · resets <t>" | title + body + last update + retry lines |
| Error code | `extractErrorCode` ×2 (`ForecastFetchCoordinator`, `CurrentTempRepository`; only one knew `NO_COVERAGE`) | none — status code / class name matched inside the presentation |
| Stage timing | `FailureBannerStage` (shared) | `FailureBannerStage` (shared) |

## Decision (user chose A)

Desktop shows the **same two-line pill** as the widget; clicking it opens the detailed card (the
widget's pill opens `SourceErrorDetailsActivity`). Content, look and placement come from one rule.

## Changes

1. `:shared` `FetchErrorCode`: `of(Throwable, isBackgroundDataRestricted)` — the one exception → code
   mapping; `fromLogged(className, detail, failureMs)` for desktop's persisted status rows. Both
   Android extractors delegate (the current-temp path gains `NO_COVERAGE`).
2. `:shared` `FailureBannerLayout`: wording, width fitting, sizes, vertical centring, stage, alpha,
   colours (ARGB) — moved from the Android renderer, `GraphRect` for `RectF`. The Android renderer
   keeps only Paint measurement, Canvas drawing and the localized strings.
3. Desktop `FetchFailureBanner`: computes the shared layout (density × uiScale, Compose text
   measurer) and draws it on a `Canvas`; click → the existing detailed card (with its dismiss ×).
   Warm-up ("waiting for network") has no Android counterpart and keeps its blue card, centred.
4. Tests: shared contract tests for the layout (centred, wording per code, tiny stage, fitting) and
   for `FetchErrorCode` (exception path and logged path agree).
