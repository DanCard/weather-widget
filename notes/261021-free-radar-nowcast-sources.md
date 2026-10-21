# Free sources for radar nowcasting (survey, 2026-10-21)

Context: brainstorm for a 0–2 h "rain starting / stopping" nowcast, with **Ukraine and Europe in
scope** (user). Phase 1 is Open-Meteo only (`plans/261021-open-meteo-15-min-nowcast.md`); this file
keeps the rest for later phases.

Two kinds of "nowcast" — keep them apart in UI claims:
- **Radar extrapolation**: last 30–60 min of radar frames, motion field, advect forward. Best at
  0–60 min, degrades by ~2 h.
- **Rapid-refresh model at 15 min** (HRRR, ICON-D2, AROME): assimilates radar hourly-ish, then is
  a model. Fine for 1–6 h, slower to catch a cell that just formed. Outside its domain a provider
  may interpolate hourly output to 15 min — looks like a nowcast, is not.

Status column: **measured** = probed from this machine on 2026-10-21; **docs** = read in a current
source; **unverified** = from memory or a third-party claim — check before building on it.

## Point APIs (lat/lon → minutes series)

| Source | Kind | Region | Step / reach | Access | Status |
|---|---|---|---|---|---|
| Open-Meteo `minutely_15` | model (HRRR, ICON-D2, AROME France HD, KNMI/DMI Harmonie) | N. America, central/western Europe native; elsewhere interpolated hourly | 15 min, `forecast_minutely_15=N` | free, no key (non-commercial) | **measured** — `models=icon_d2,ncep_hrrr_conus,meteofrance_arome_france_hd,knmi_harmonie_arome_europe,dmi_harmonie_arome_europe`: Kyiv → `"No data is available for this location"`; Berlin, Paris, Rome, SF → series. `precipitation_probability` is also accepted in `minutely_15`. |
| MET Norway `weatherapi/nowcast/2.0` | radar extrapolation | Nordic countries | 5 min, 90 min | free, identifying User-Agent required, CC BY 4.0 | unverified (well known; not probed) |
| Tomorrow.io timelines `timesteps=1m` | proprietary blend (radar + satellite + model) | global | 1 min, 60 min | free plan key (already in the app); ~25/h, 500/day | unverified for 1m on free plan |
| Pirate Weather `minutely` | model (HRRR sub-hourly in US; elsewhere coarser) | global, best in US | 1 min, 60 min | free key, monthly quota | unverified |
| Apple WeatherKit "next hour" | radar nowcast | many countries | 1 min, 60 min | **not free** ($99/yr dev account) | — listed for comparison only |

## Raw radar (build our own extrapolation)

| Source | Region | Product | Access | Status |
|---|---|---|---|---|
| EUMETNET OPERA (Open Radar Data) | pan-European composite | max reflectivity, rain rate, 1 h accumulation; 15 min; DBZH 1 km (since 2024-07), RATE/ACRR 2 km | S3 archive (CloudFerro); RODEO MQTT + EDR API keeps the latest 24 h; EU High-Value Dataset → must be free | **docs**; whether Ukrainian radars feed the composite is **unverified** |
| DWD Open Data | Germany (+ border) | RADOLAN / RV composites (5 min), RADVOR nowcast products | `opendata.dwd.de`, free, no key | unverified |
| KNMI Data Platform | Netherlands | radar composite + radar nowcast | free, anonymous API key | unverified |
| Météo-France public API | France | radar mosaic; AROME-PI (immediate-forecast model) | free registration, key | unverified |
| RainViewer Weather Maps API | global mosaic | past radar frames | free, no key, personal / small-scale use only | **docs**: transitioning through 2025; nowcast tiles listed "discontinuing Jan 1 2026"; third party (LibreWXR) reports free tier since 2026-01-01 = zoom ≤ 7, one colour scheme, no forecast frames |
| NOAA MRMS | US | reflectivity / PrecipRate, 2 min, 1 km, GRIB2 | AWS Open Data, free | unverified |
| Iowa Environmental Mesonet n0q | US | 5-min composite PNG tiles (palette index → dBZ) | free | unverified |
| NOAA nowCOAST WMS | US | radar layers; `GetFeatureInfo` = point query, no imagery download | free | unverified |

## Satellite (fallback where radar is missing — relevant to Ukraine)

| Source | Product | Access | Status |
|---|---|---|---|
| EUMETSAT NWC SAF / H SAF | convective rainfall rate (CRR), precipitation estimates, ~15 min, Europe incl. Ukraine | EUMETSAT Data Store / EUMETCast, free registration | unverified — heavy formats (NetCDF/GRIB), not phone-friendly |

## Ukraine specifically

- Copernicus EFAS noted that after the 2022 invasion data from nearly all Ukrainian stations stopped
  being available, and switched to DWD ICON for Ukraine (page undated — may be out of date).
- No free source found that gives a **radar** nowcast for Kyiv. Open-Meteo has no native 15-min
  model there (measured above). Best candidates: Tomorrow.io `1m` (satellite/model blend), OPERA if
  Ukrainian radars contribute, NWC SAF CRR.
- Consequence for UI: in Ukraine, rain timing stays at **hourly** granularity until one of these
  is verified.

## Extrapolation references (if we build our own)

- pySTEPS (BSD-3): Lucas–Kanade optical flow + semi-Lagrangian advection + STEPS blending.
- rainymotion (MIT): simpler optical-flow nowcasts; good baseline.
- Minimal Kotlin version: phase correlation (FFT) on a ~128×128 tile for one motion vector,
  advect the newest frame, sample the site pixel at +5…+60 min. Blend to model 15-min output over
  45–120 min.

## Sources

- [Open-Meteo: sub-hourly 15-minutely forecasts](https://openmeteo.substack.com/p/sub-hourly-15-minutely-weather-forecasts)
- [EUMETNET openradardata documentation](https://github.com/EUMETNET/openradardata-documentation)
- [RMI Belgium – EUMETNET OPERA](https://radli.meteo.be/about-us/international-and-national-collaboration/eumetnet-opera)
- [Rain Viewer API transition FAQ](https://www.rainviewer.com/api/transition-faq.html)
- [Rain Viewer API examples (nowcast status)](https://www.rainviewer.com/api/examples.html)
- [LibreWXR (third-party report on RainViewer 2026 limits)](https://github.com/JoshuaKimsey/LibreWXR)
- [Copernicus EFAS – Ukraine data note](https://european-flood.emergency.copernicus.eu/en/node/842)
- [RainViewer – Ukraine radar page](https://rainviewer.com/radars/ukraine.html)
