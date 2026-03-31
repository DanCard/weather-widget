# API Key Handling and Proxy Service Notes

## Summary
- A client-side Android app cannot truly keep a bundled API key secret.
- If the app can use a key, a determined user can extract it from the APK, memory, or network traffic.
- For production keys that cost money or create liability, the safest design is to move those keys behind a backend you control.
- For lower-risk development or convenience keys, build-time injection is acceptable, but it should be treated as low-trust rather than secret storage.

## Recommended API Key Policy

### Best practice
1. Do not ship sensitive provider keys in the APK if you can avoid it.
2. Put real secrets on a backend you control.
3. Have the Android app call your backend instead of the weather vendor directly.

### On the backend
- Store keys in environment variables or a secret manager.
- Restrict keys by provider settings if the vendor supports it.
- Rotate keys periodically.
- Monitor usage, rate limits, and abuse.

### If the app must call providers directly
- Treat the key as low-trust, not secret.
- Use separate keys per provider.
- Use tight quotas and billing limits.
- Apply Android app restrictions if the vendor supports package/signing-cert restrictions.
- Keep keys out of git.
- Inject them at build time from `local.properties`, environment variables, or CI secrets.
- Never hardcode production keys in Kotlin source.
- Handle missing, invalid, revoked, and unauthorized keys clearly in the UI and logs.

## What is acceptable for this project today
- The current `BuildConfig` approach using `local.properties` and environment variables is fine for local development.
- It is not a strong production-secret strategy.
- For any provider where misuse could create billing or abuse risk, a small proxy service is the better design.

## Rule of thumb
- If misuse would cost money or create liability, move the key server-side.
- If it is just a low-risk convenience key with strict quotas, client-side injection may be acceptable.

## What a small proxy service looks like

### Architecture
Instead of:

- app -> Visual Crossing / WeatherAPI / OpenWeatherMap directly

Use:

- app -> your proxy
- proxy -> weather provider

The proxy holds the real provider key and forwards only the data the app needs.

### Basic responsibilities
- Accept a request like `GET /forecast?lat=...&lon=...`
- Call the upstream provider with the real secret key
- Normalize or pass through the provider response
- Return only the fields the app needs
- Apply caching, rate limiting, logging, and input validation

### Minimal request flow
```text
Android app
   |
   v
Your proxy service
   |
   v
Weather provider API
```

### Minimal endpoint example
```http
GET /api/weather/visual-crossing?lat=37.42&lon=-122.08
```

### Minimal server example
```js
app.get("/api/weather/visual-crossing", async (req, res) => {
  const { lat, lon } = req.query

  const upstream = await fetch(
    `https://weather.visualcrossing.com/.../${lat},${lon}?key=${process.env.VISUAL_CROSSING_API_KEY}`
  )

  const data = await upstream.json()
  res.json(data)
})
```

## Better proxy shape for this app
- Use one normalized endpoint such as `/api/weather/forecast`
- Accept `lat`, `lon`, and optionally `source`
- Let the backend choose the upstream provider
- Return one stable schema regardless of provider

That gives:
- no provider secrets in the APK
- easier provider swaps
- consistent error handling
- central control over quotas and abuse

## Practical proxy features
- Validate `lat` and `lon`
- Round coordinates for cache keys if precision beyond the widget’s needs is unnecessary
- Cache provider responses for a short time window
- Rate limit by client or IP
- Add upstream timeout and retry rules
- Log provider failures centrally
- Return a stable, app-friendly schema

## Good lightweight hosting options
- Cloudflare Workers
- Vercel serverless functions
- Netlify functions
- FastAPI on a small container
- Node/Express on a small container
- Google Cloud Run

## Lean recommendation for this project
- Use a Cloudflare Worker or very small FastAPI service
- Provide one normalized forecast endpoint
- Provide one normalized current-temperature endpoint
- Store provider keys as environment secrets

## Main tradeoff
- More secure and easier to control
- But now you own backend deployment, uptime, and operational complexity

## Recommended direction
- Keep direct provider calls only for low-risk development or testing.
- If a provider requires billing, subscription access, or creates cost exposure, move it behind a proxy.
