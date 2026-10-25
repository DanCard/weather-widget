# Privacy Policy — Weather Widget

_Last updated: October 25, 2026_

Weather Widget ("the app") includes an Android widget and a Linux desktop companion. This policy
describes what data they use, where it is sent, and how long it is kept locally.

## Location and network services

The app uses your device location, an approximate IP location, or a location you enter manually to
retrieve weather and label your chosen place. Depending on your settings, location, and the features
you open, it contacts these external services:

- **Forecast and history providers:** the U.S. National Weather Service (weather.gov), Open-Meteo,
  Silurian, Tomorrow.io, WeatherAPI, OpenWeatherMap, and Google Weather. Forecast requests include
  coordinates for the selected location. Only providers you enable or configure for actuals are used.
- **Observation feeds:** NWS/NOAA, Aviation Weather (aviationweather.gov) for airport METAR reports,
  and Synoptic Data/MesoWest (synopticdata.com). Requests use coordinates, a search area, or station
  identifiers to retrieve current and historical observations.
- **Location lookup:** OpenStreetMap Nominatim (nominatim.openstreetmap.org) receives place-search
  text or coordinates for forward/reverse geocoding. ipapi.co receives a network request from your
  device when the app asks for an approximate IP-based location.
- **Rain maps and radar:** on-demand viewers may contact WeatherNext
  (ajoros.github.io/ai-weather-guy), Open-Meteo, NOAA/NWS radar services, or RainViewer
  (rainviewer.com). Radar uses NOAA/NWS inside its coverage and RainViewer elsewhere; requests fetch
  imagery for the viewed area.

Each service's own privacy policy governs its handling of requests. The app has no weather server of
its own, does not sell your data, and does not send it to advertising or analytics networks.

## Local storage and retention

Location coordinates, chosen place names, settings, API keys you enter, and cached weather are stored
locally on your device. Saved configuration is not erased by the weather-data retention schedule; it
remains until you change or remove it, clear the app's data, or uninstall the Android app. On Linux,
remove the desktop configuration and data files yourself if you want them deleted.

Weather retention varies by table:

- Raw station observations are kept for about **10 days**.
- Most forecasts, current conditions, and active forecast snapshots are kept for about **30 days**.
- Daily history and settled hourly forecast history are kept for about **18 months**.

Diagnostic logs generally have shorter retention, and local API/network usage records are kept
separately for the usage screens. Cached radar images are temporary. None of these local retention
periods controls how long an external service retains requests it receives.

## Crash reports

Android builds configured with Firebase use **Firebase Crashlytics** to report crashes and non-fatal
errors. Reports can include device model, OS version, app state, and diagnostic log breadcrumbs.
Crashlytics data handling is described in
[Google's privacy documentation](https://firebase.google.com/support/privacy). Desktop does not use
Crashlytics.

The app has no accounts, ads, usage-analytics service, or contact-list access.

## Android permissions

- **Location (including background)** — lets home-screen widgets keep showing weather for your
  current area while the app is closed. Background reads are passive; the app does not actively turn
  on GPS in the background. You can pin a fixed location and deny background location instead.
- **Internet** — connects to the services listed above.

## Your choices

- Revoke location permission in Android Settings and use a manually chosen location.
- Enable or disable forecast providers and configure observation feeds in Settings.
- On Android, uninstalling deletes the app's local data. On Linux, delete the desktop configuration
  and data files to remove locally saved information.

## Contact

Questions or concerns: open an issue at
<https://github.com/DanCard/weather-widget/issues> or email <daniecarde55@gmail.com>.
