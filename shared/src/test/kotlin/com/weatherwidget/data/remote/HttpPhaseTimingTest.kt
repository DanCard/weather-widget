package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HttpPhaseTimingTest {
    @Test
    fun `formats stalled TTFB with completed DNS, TCP and TLS phases`() {
        val line = HttpPhaseTiming(
            host = "api.open-meteo.com",
            scheme = "https",
            remoteIp = "51.81.167.44",
            protocol = "h2",
            dnsMs = 12,
            connectMs = 45,
            tlsMs = 88,
            reqHeadersMs = 1,
            ttfbMs = -1,
            totalMs = 4012,
            reused = false,
            error = "SocketTimeoutException:timeout",
        ).formatLine()

        assertEquals(
            "host=api.open-meteo.com scheme=https remoteIp=51.81.167.44 protocol=h2 " +
                "dnsMs=12 connectMs=45 tlsMs=88 reqHeadersMs=1 ttfbMs=-1 totalMs=4012 " +
                "reused=false error=SocketTimeoutException:timeout",
            line,
        )
    }

    @Test
    fun `formats missing remoteIp and protocol as none`() {
        val line = HttpPhaseTiming(
            host = "api.open-meteo.com",
            scheme = "https",
            totalMs = 3001,
            error = "UnknownHostException:api.open-meteo.com",
        ).formatLine()

        assertEquals(
            "host=api.open-meteo.com scheme=https remoteIp=none protocol=none " +
                "dnsMs=-1 connectMs=-1 tlsMs=-1 reqHeadersMs=-1 ttfbMs=-1 totalMs=3001 " +
                "reused=false error=UnknownHostException:api.open-meteo.com",
            line,
        )
    }
}
