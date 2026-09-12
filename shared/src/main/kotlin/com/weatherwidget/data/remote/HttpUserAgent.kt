package com.weatherwidget.data.remote

/**
 * The User-Agent every outbound client identifies itself with.
 *
 * Ktor's default is the literal string `Ktor client`, and some upstreams refuse generic agents
 * outright: ipapi.co answers it with `429 RateLimited` on the very first call of the day (a
 * user-agent blocklist wearing a quota message — the same IP gets 200 with this string a second
 * later, verified 2026-09-12), and Nominatim's usage policy requires an identifying agent. One
 * constant so a client cannot quietly ship without it.
 */
object HttpUserAgent {
    const val VALUE = "WeatherWidget/1.0 (contact@weatherwidget.app)"
}
