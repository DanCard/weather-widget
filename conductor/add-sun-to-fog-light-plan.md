# Plan: Add Sun to Fog Light Icons and Improve Logging

## Objective
1. Identify the fog icons being rendered between 7 AM and 9 AM.
2. Add a visible sun to the `ic_weather_fog_light` icon, as it is used for daytime patchy/light fog.
3. Improve logging in `HourlyBottomZoneHelper` to print human-readable resource names instead of just integer IDs, so future debugging is easier without needing `R.txt` lookup.

## Context & Findings
- By analyzing the emulator logs and `R.txt`, the icons rendered for 7-9 AM (`iconRes=2131165315` or `0x7f070083`) correspond to `ic_weather_fog_light.xml`.
- The icons rendered for 10-11 AM (`iconRes=2131165317` or `0x7f070085`) correspond to `ic_weather_fog_night.xml`. Wait, if 10-11 AM in the widget is displaying `ic_weather_fog_night`, there might be an issue with the day/night boundary calculation or timezone. We should also investigate why a night icon is shown at 10 AM if it's daytime. (But wait, the screenshot showed a bright yellow sun at 10a and 11a! Ah! The sun icon is `ic_weather_clear` or similar. Let's re-verify the log. The log for zone 8, 9, 10 was `2131165317`. If `2131165317` is `ic_weather_clear`? Let's check `R.txt` again!)

Wait, I should check `R.txt` for `2131165317` again.
2131165317 in hex is `0x7f070085`.
Let me re-read the grep output:
`app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt:int drawable ic_weather_fog_light 0x7f070083`
`app/build/intermediates/runtime_symbol_list/debug/processDebugResources/R.txt:int drawable ic_weather_fog_night 0x7f070085`

Let me search for `ic_weather_clear` in R.txt.