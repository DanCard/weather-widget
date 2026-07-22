---
name: widget-defaults-mean-unbound-layout
description: "Widget showing \"Today / --° / --°\" = raw widget_weather XML defaults (never-bound layout), NOT stale data"
metadata: 
  node_type: memory
  type: project
  originSessionId: 07adcd22-3e9c-47cb-b292-7b63e287da4e
  modified: 2026-07-22T20:44:59.892Z
---

`Today` / `--°` / `--°` on the widget are the **literal defaults** in
`res/layout/widget_weather.xml` (`day2_label="@string/today"`, `day2_high="--°"`,
`day2_low="--°"`). Seeing them means the launcher inflated the layout and applied **no body
actions** — a never-bound layout, not an old good render gone stale.

Diagnostic value: it splits the failure cleanly. If the header (current temp) is painted but the
body is defaults, the render pipeline and data are fine and the problem is push delivery. Check
`WIDGET_PAINT` (`push=partial|full`) and the new `WIDGET_PUSH` rows (carry `pid=` +
`unbackedPartial=`) rather than the data layer.

**Why:** on 2026-07-14 a Samsung widget showed `92.5°` + `--°`/`--°` for ~35 min. It looked like
missing forecast data; the data was in fact present and correct, and every paint logged
`state=data push=partial`. A full push (`ACTION_REFRESH`) healed it instantly. Root cause of why
those partials didn't land is still OPEN — `WIDGET_PUSH` was added to catch the next occurrence.
See `summaries/260714-widget-partial-push-stale.md`.

**2026-07-22 recurrence — trigger identified: a LOCATION CHANGE, and the body is blanked by a
`caller=TEMPERATURE push=full`, not by partials failing to land.** User played basketball; at
12:15:19 `GPS_RESAMPLE outcome=healed` moved the site from home (37.4168,-122.0890) to Garfield
Park (37.4240,-122.0884). For ~1h configured and data locations disagreed
(`configuredLoc=37.42403 dataLoc=37.41700`). Widget 345 (DAILY, 10 cols) then sat on `--°` for
~30 min while the header stayed live. Partial pushes at 13:40:26 and 13:40:34 did **not** heal it;
the `caller=DAILY push=full` at 13:40:41 did — so "partials are silently dropped" is the wrong
model here.

Mechanism: `TemperatureViewHandler.handle()` builds a *fresh* full `widget_weather` RemoteViews
(TemperatureViewHandler.kt:169) binding only the temperature view, then pushes it
(TemperatureViewHandler.kt:188) **without passing `bodyComplete`**, so it takes
`WidgetPushDispatcher.push`'s `bodyComplete = true` default (WidgetPushDispatcher.kt:112). When
that push is full, `updateAppWidget` replaces the whole tree and every daily-body view the
temperature binder never populated reverts to XML defaults. The dispatcher documents exactly this
hazard at WidgetPushDispatcher.kt:54-56, and the `TEMPERATURE_HEADER` path guards it correctly with
`bodyComplete = false` (TemperatureViewHandler.kt:377) — the main `handle()` push does not.
NOT yet proven: that the specific 13:07:50 `widget=345 caller=TEMPERATURE state=data push=full` was
the blanking event (`shouldPersist` only keeps the first full per widget per process, so repeat
fulls leave no `WIDGET_PUSH` row). Verify before shipping a fix.

**How to apply:** widget body showing `--°` → do NOT start by querying forecasts. Confirm whether
the header is live; if it is, the body is unbound and it's a delivery problem. `ACTION_REFRESH`
(full push) is the workaround:
`adb shell am broadcast -a com.weatherwidget.ACTION_REFRESH -n com.weatherwidget/com.weatherwidget.widget.WeatherWidgetProvider`

Related: [[widget_blank_selfheal_render_ok]], [[widget_worker_partial_push]],
[[onupdate_loading_placeholder_over_good_data]]
