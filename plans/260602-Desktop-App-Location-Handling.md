# Desktop App — Location Handling Plan

## Context

The Linux desktop weather app (modules `:shared` + `:desktop`, MVP already built and running —
see `session-logs/260602-linux-desktop-app-mvp.md`) currently **hardcodes** the location to Google HQ
in `DesktopWeatherService` (`DEFAULT_LATITUDE/LONGITUDE`). It needs real location handling.

The desktop has no GPS, and **none of Android's location code is reusable**: Android resolves
ZIP→lat/lon via the system `android.location.Geocoder` and GPS via Play Services
`FusedLocationProviderClient` (both Android-only, no HTTP), stores location in per-widget
SharedPreferences, and doesn't reverse-geocode (the name is the algorithmic "Mountain View, CA" /
"lat, lon"). So location must be solved fresh for desktop.

**Decisions (with user):**
- **Resolution order on acquisition: (1) try the connected phone's GPS via ADB; (2) if that fails,
  prompt the user.** A fresh phone fix is used directly (no prompt). Otherwise open the picker.
- **The fallback prompt offers several input options — address, ZIP, lat/lon — with fields
  pre-filled from an IP lookup** (timezone as the offline backstop if IP is unavailable). The
  "use connected phone" action is also available in the picker as a manual retry.
- **Single** saved location (stationary desktop; set-once), stored in an XDG config file. Once saved,
  normal launches reuse it — they do NOT silently re-resolve/override; re-acquisition runs only on
  first run (no config) or via an explicit "Update location" action.
- **"Phone works"** = a `gps`/`fused` last-known fix that exists AND is fresh (fix age < 24h). A
  stale/absent fix falls through to the prompt (a stale fix, if any, is offered there, not auto-used).

**All providers verified, no API keys required** (curl-tested 2026-06-02):
- Offline timezone→coords: `/usr/share/zoneinfo/zone1970.tab` (ISO-6709 coords per zone).
- IP city-level: `https://ipapi.co/json/` (city/region/country + lat/lon).
- Geocoding (address **and** ZIP **and** city in one query): **Nominatim**
  `https://nominatim.openstreetmap.org/search?q=…&format=jsonv2` + `/reverse` for labeling coords.
  Requires an identifying `User-Agent`, ≤1 req/s, geocode **on submit** (no per-keystroke autocomplete).
- Phone GPS: `adb shell dumpsys location` → `last location=Location[gps <lat>,<lon> hAcc=… et=…]`
  (prefer `gps`, then `fused`); gate on `adb` present + a device in `adb devices`; show accuracy + age.

## Components & files

### New HTTP clients in `:shared` (consistent with NwsApi/OpenMeteoApi: `(HttpClient, Json)` ctor)
- `shared/.../data/remote/NominatimApi.kt` — `search(query): List<GeocodeResult>` and
  `reverse(lat, lon): GeocodeResult?`. `GeocodeResult(displayName, lat, lon)`. Sends the required
  User-Agent header (mirror the `USER_AGENT` pattern already in `NwsApi`).
- `shared/.../data/remote/IpGeolocationApi.kt` — `locate(): IpLocation?` → `(lat, lon, city, region,
  country)` from ipapi.co.
  (Both reusable/testable; engine injected per-consumer — desktop passes its CIO `HttpClient`.)

### New desktop-only locators & config in `:desktop`
- `TimezoneLocator.kt` — `ZoneId.systemDefault().id` → look up the row in `/usr/share/zoneinfo/
  zone1970.tab`, parse the ISO-6709 field (handles `±DDMM[SS]` lat / `±DDDMM[SS]` lon, variable
  length) → coarse `(lat, lon, zoneLabel)`. Pure parse of one line → unit-testable.
- `PhoneLocator.kt` — `ProcessBuilder("adb","shell","dumpsys","location")`, parse the
  `Location[<provider> <lat>,<lon> hAcc=<m> et=<age>]` lines (regex), prefer gps→fused; return
  `(lat, lon, accuracyMeters, fixAgeMillis)`. `isAvailable()` = adb on PATH **and** `adb devices`
  lists a `device`. Parsing the dumpsys line is a pure function → unit-testable.
- `DesktopConfig.kt` — `@Serializable data class DesktopConfig(lat, lon, label, source)`; load/save
  JSON at `${XDG_CONFIG_HOME:-$HOME/.config}/weather-widget/config.json`. `null` when absent (→ prompt).

### Orchestration / UI in `:desktop`
- `LocationResolver.kt` — composes the above into the agreed flow. Returns a common
  `ResolvedLocation(lat, lon, label, source)`.
  - `acquire(): ResolvedLocation?` — **(1)** `fromPhone()` if `PhoneLocator.isAvailable()` and the
    fix is fresh (<24h) → return it. **(2)** else `null` (caller opens the picker).
  - `suggestPrefill()` = IP lookup (ipapi.co), falling back to the timezone seed if offline — used to
    pre-fill the picker.
  - `searchText(q)` → NominatimApi (address/ZIP/city); `fromCoordinates(lat,lon)` → optional Nominatim
    reverse for a label; `fromPhone()` → PhoneLocator (also exposed as the picker's manual retry).
- `LocationPicker.kt` — Compose dialog/window with: the suggested default + **Use this**; a search
  field (address/ZIP/city) with a results list (geocode on Enter/button); manual **lat,lon** fields;
  and a **Use connected phone (GPS)** button shown only when `PhoneLocator.isAvailable()` (with
  accuracy + "fix age" shown). Selecting any option writes `DesktopConfig` and closes.
- **`DesktopWeatherService.kt` (edit)** — drop the hardcoded `DEFAULT_*`; read lat/lon from
  `DesktopConfig`. Keep Google HQ only as the absolute last-resort fallback.
- **`Main.kt` (edit)** — on launch: load `DesktopConfig`. If present → show the weather popup. If
  absent → run `LocationResolver.acquire()`: a fresh phone fix is saved & used directly; otherwise
  open `LocationPicker` pre-filled via `suggestPrefill()`. Add a tray item **"Update location…"**
  (and a small affordance in the popup) that re-runs the same flow; on change, re-fetch and redraw.

## Verification
- **Unit tests (plain JUnit, pure functions — matches project's no-mock strategy):**
  zone1970.tab ISO-6709 parsing (incl. `±DDMM` vs `±DDMMSS`); `dumpsys location` line parsing
  (gps-preferred, accuracy/age extraction); Nominatim & ipapi JSON→model mapping.
- **Live run (`./gradlew :desktop:run`, this env has DISPLAY + network + adb):**
  1a. Delete config, phone connected with a fresh fix → first launch **auto-uses the phone GPS** (no
      prompt); config persists.
  1b. Delete config, no phone / stale fix → first launch opens the picker **pre-filled from IP**.
  2. In the picker, search "1600 Amphitheatre Parkway" → resolves to the exact building; pick it →
     popup shows that location's weather. Repeat with a ZIP ("80301") and a city.
  3. Enter manual coords → reverse-geocoded label appears; confirm fetch uses them.
  4. With the phone connected, **Use connected phone (GPS)** fills ~37.4168,-122.0890 (accuracy/age
     shown); confirm config persists and survives restart (no re-prompt).
  5. Screenshot the picker + resulting popup.

## Out of scope (note for later)
- Multiple/switchable saved locations (chose single).
- GeoClue2 D-Bus source (post-MLS it mostly degrades to IP; revisit if a real provider is configured).
- iPhone location (no adb equivalent).
- Live phone-location streaming (we read last-known on demand only).
