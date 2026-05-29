# Monetization Strategy — Weather Widget

**Date:** 2026-05-29
**Context:** Solo dev, pre-launch, no users yet, leaning toward a free Play Store release. This note
captures the monetization analysis specific to *this* app (a low-engagement home-screen widget with
paid + free weather APIs and a forecast-accuracy differentiator).

---

## The two constraints that determine everything

1. **A widget is deliberately low-engagement.** Its whole value is that the user *doesn't* open the
   app — they glance at the home screen. Every monetization surface (ads, upsell prompts) lives inside
   Activities users rarely visit. So revenue-per-user is structurally capped, regardless of approach.
   This is not a game/social app where engagement compounds.

2. **COGS scales with users only through the paid APIs.** NWS and Open-Meteo are free (no key, no real
   quota); Tomorrow.io / Visual Crossing / WeatherAPI / OpenWeatherMap (and likely Silurian) bill per
   call. Release default sources are `NWS, OPEN_METEO, SILURIAN` (`WidgetStateManager.kt:76`). If the
   *default* free tier stays on zero-cost APIs, 10,000 free users cost ≈ $0. That single choice is what
   makes "free forever" financially safe and should anchor the whole strategy.

**Reframe:** not "how do I extract revenue from a widget" (low ceiling, bad ad surfaces) but
**"how do I make free cost me nothing, and offer a cheap one-time upgrade to the few who'd pay."**

---

## Recommendation: Free tier (zero-cost APIs) + one-time "Pro" unlock. No subscriptions, no ads.

The differentiator most weather widgets lack is the **multi-source forecast-accuracy tracking**
(`AccuracyCalculator`, yesterday-actual-vs-forecast, bias/score, 30-day stats). That's a "weather nerd"
feature, and weather nerds are the segment that pays. Gate the power-user surface, not the basic glance:

- **Free:** the widget, NWS + Open-Meteo, current temp, basic forecast — what 95% of users want.
- **Pro (one-time, ~$2–4):** full accuracy statistics & history, extra widget themes/sizes, the
  comparison/diff display modes — ideally **zero-marginal-cost** features computed on-device from the
  free APIs.

### Why one-time, and why zero-marginal-cost features specifically
A one-time payment against a feature that costs per-call *forever* (a paid API) eventually loses money
on heavy users. A one-time payment against on-device computation (themes, sizes, analytics from free
APIs) is pure margin. This **decouples revenue from COGS**. If premium APIs are ever exposed, make them
an explicit opt-in the Pro user accepts — never the funded-forever default. (The per-source throttle and
quota work already done helps here.)

---

## Options considered and rejected (for this app)

- **Subscriptions:** over-monetizes a simple widget; invites 1-star reviews. Justified by *ongoing
  server costs*, which this app doesn't have (device talks directly to APIs). The only recurring-value
  angle — "accuracy history accrues over time" — is too thin to defend a sub for a solo dev who'd also
  eat Play's churn/management overhead.
- **Ads:** worst fit. No in-widget surface → ads land in screens nobody opens → near-zero revenue,
  wrecks the "Apple glass" aesthetic, and adds an ad-SDK data-collection disclosure on top of the
  location disclosure. Pure downside.
- **Pay-what-you-want / tip jar:** harmless, honest for a hobby release; revenue is a rounding error but
  near-zero effort. Fine as a complement to free.

---

## The decision hinges on the goal

- **Portfolio / résumé piece →** ship **free, no IAP**. Billing, Pro gating, restore-purchases, and
  support are real work/risk that add nothing to a portfolio story. Keep it clean.
- **Cover API/dev costs →** free + one-time Pro as above; price to offset whatever paid-API usage is
  allowed.
- **Real side income →** a weather widget is a tough vehicle (engagement ceiling). The lever is
  **distribution**, not a better paywall: a free, well-reviewed app that nails the accuracy-tracking
  hook is the best shot at the volume that makes even a small Pro conversion meaningful.

---

## If we proceed with one-time Pro (implementation sketch, not yet built)
- Google Play Billing Library, a single **one-time (non-consumable) product**, with restore-purchases.
- Gate Pro features behind a locally-cached entitlement (no backend needed; Play holds the source of
  truth, cache for offline).
- Pro candidates that are zero-marginal-cost: themes, extra widget sizes, full StatisticsActivity
  detail, the SIDE_BY_SIDE / DIFFERENCE display modes, longer history browsing.
- Keep the free widget fully functional and attractive on its own — the upgrade should feel like "more
  for enthusiasts," never "the free version is crippled."
