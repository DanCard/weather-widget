# Hourly temperature label colours: one rule in `:shared`

## Problem

User, 2026-10-07: desktop draws forecast temperature labels white; Android colours them.

| Label | Android (`TemperatureGraphAnnotationRenderer`) | Desktop (`TemperatureGraph`) |
|---|---|---|
| Forecast | hour's weather colour (`WeatherColors.forecastColor`) | white, by role |
| Actual | observed rose | observed rose (`COLOR_ACTUAL`) |
| START/END | as above | 60 % white |
| Leader | label colour @ alpha 80 | white @ 35 % |

Desktop's hourly `HourData` (`HourDataAssembler`) carries no condition flags; it derives them per
hour from the icon + sun position for the dashed forecast segments only.

## Change

1. `:shared` `TemperatureLabelColors`: `labelArgb(isFuture, condition)` — forecast → its hour's
   weather colour (the same colour as the dashed segment), actual → `WeatherColors.OBSERVED`;
   `leaderArgb` = label colour @ alpha 80; `LEADER_STROKE_DP`.
2. Android renderer calls it (paints keep size/shadow only).
3. Desktop: one `conditionFlagsOf(hour, lat, lon)` used by both the curve segments and the labels
   (label → nearest forecast hour); the role-based `when` is gone.
4. `TemperatureLabelColorsTest` pins the rule, including label == segment colour.
