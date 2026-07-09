# Privacy Policy — Weather Widget

_Last updated: July 9, 2026_

Weather Widget ("the app") is a weather forecast widget for Android. This policy describes
what data the app collects, how it is used, and what is shared.

## Data the app collects

### Location
- The app uses your device location (or a location you enter manually) solely to fetch
  weather forecasts and observations for your area.
- Your coordinates are sent to the weather data providers you have enabled in Settings —
  for example the U.S. National Weather Service (weather.gov), Open-Meteo (open-meteo.com),
  and optional providers such as Silurian, Tomorrow.io, WeatherAPI, Visual Crossing, and
  OpenWeatherMap — as part of each forecast request. Each provider's own privacy policy
  governs its handling of those requests.
- Location coordinates and the weather data fetched for them are stored **only on your
  device**, in the app's local database, and are automatically deleted after about 30 days.
- The app never sells your data, never sends it to advertising or analytics networks, and
  has no server of its own — your location is never uploaded anywhere except to the weather
  providers listed above to retrieve forecasts.

### Crash reports
- The app uses Firebase Crashlytics (a Google service) to report crashes so bugs can be
  fixed. Crash reports include device model, OS version, and the app state at the time of
  the crash. They do **not** include your location or any personal identifiers you entered.
- Crashlytics data handling is described in
  [Google's privacy documentation](https://firebase.google.com/support/privacy).

### Nothing else
- No accounts, no ads, no usage analytics, no contact list, no personal information.

## Permissions

- **Location (including background)** — used so home-screen widgets can keep showing
  weather for wherever you are, even while the app is not open. Background reads are
  passive only (the app never actively turns on GPS in the background). You can instead
  pin a fixed location in the app and deny background location entirely.
- **Internet** — used to contact the weather providers.

## Your choices

- Revoke location permission at any time in Android Settings; the app falls back to a
  manually chosen location.
- Disable any weather provider in Settings; disabled providers receive no requests.
- Uninstalling the app deletes all locally stored data.

## Contact

Questions or concerns: open an issue at
<https://github.com/DanCard/weather-widget/issues> or email <cardenas.kin@gmail.com>.
