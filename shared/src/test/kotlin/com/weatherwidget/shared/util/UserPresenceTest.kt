package com.weatherwidget.shared.util

import com.weatherwidget.shared.util.UserPresence.Basis
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/** performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md */
@Category(ShortDuration::class)
class UserPresenceTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val minute = 60_000L

    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 19, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `screen off or locked is away only after 60 min`() {
        val left = at(18)
        assertNull(UserPresence.away(false, left, left + 59 * minute, zone))
        assertEquals(Basis.SCREEN, UserPresence.away(false, left, left + 60 * minute, zone))
    }

    @Test
    fun `screen on is present at any hour`() {
        assertNull(UserPresence.away(true, at(1) - 5 * 60 * minute, at(2), zone))
        assertTrue(UserPresence.isPresentNow(true))
        assertFalse(UserPresence.isPresentNow(false))
        assertFalse("unreadable is not presence", UserPresence.isPresentNow(null))
    }

    @Test
    fun `unreadable screen - present by day, away at night once nobody used it for 60 min`() {
        val lastUse = at(22) - 24 * 60 * minute // the evening before
        assertNull("by day: fail-open", UserPresence.away(null, at(8), at(15), zone))
        assertNull("23:30 is not night", UserPresence.away(null, at(22), at(23, 30), zone))
        assertEquals(Basis.NIGHT_FALLBACK, UserPresence.away(null, lastUse, at(2), zone))
        assertNull("05:00 ends the window", UserPresence.away(null, lastUse, at(5), zone))
        // A panel click at 01:30 (lastPresent) keeps it present until 02:30.
        assertNull(UserPresence.away(null, at(1, 30), at(2), zone))
        assertEquals(Basis.NIGHT_FALLBACK, UserPresence.away(null, at(1, 30), at(2, 30), zone))
    }

    @Test
    fun `with the screen readable the hour plays no part`() {
        // A deliberate overnight display: screen on at 02:00 → present.
        assertNull(UserPresence.away(true, at(2), at(2), zone))
        // Screen off at 14:00 for an hour → away by screen, not night.
        assertEquals(Basis.SCREEN, UserPresence.away(false, at(13), at(14), zone))
    }
}
