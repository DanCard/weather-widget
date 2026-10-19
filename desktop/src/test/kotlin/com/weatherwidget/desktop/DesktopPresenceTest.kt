package com.weatherwidget.desktop

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** performance/261019-desktop-away-state-no-fetching-while-nobody-is-there.md */
@Category(ShortDuration::class)
class DesktopPresenceTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val minute = 60_000L
    private var now = LocalDateTime.of(2026, 10, 19, 17, 0).atZone(zone).toInstant().toEpochMilli()
    private var screen: Boolean? = true
    private fun presence() = DesktopPresence(screenState = { screen }, clock = { now }, zone = { zone })

    @Test
    fun `away after an hour of blank screen, back on unblank with what was skipped`() {
        val p = presence()
        assertNull(p.evaluate())
        screen = false
        now += 59 * minute
        assertNull(p.evaluate())
        assertFalse(p.isAway)
        now += minute
        val away = p.evaluate()!!
        assertTrue(away.away)
        assertTrue(away.line, away.line.startsWith("away=true basis=screen"))
        p.countSkippedObservation()
        p.countSkippedForecasts(3)
        now += 14 * 60 * minute
        val back = p.markPresent("unblank")!!
        assertFalse(back.away)
        assertEquals("away=false trigger=unblank awayMin=840 skippedObs=1 skippedForecast=3", back.line)
        assertFalse(p.isAway)
    }

    @Test
    fun `a return seen only by the minute check ends away`() {
        val p = presence()
        screen = false
        now += 2 * 60 * minute
        assertTrue(p.evaluate()!!.away)
        screen = true
        now += minute
        val back = p.evaluate()!!
        assertTrue(back.line, back.line.startsWith("away=false trigger=screen"))
    }

    @Test
    fun `a panel click while present changes nothing`() {
        assertNull(presence().markPresent("panel"))
    }

    @Test
    fun `network restore while away fetches nothing - a resume or a user refresh does`() {
        assertTrue(launchRefreshSkippedWhileAway("network:restored", away = true))
        assertFalse(launchRefreshSkippedWhileAway("network:restored", away = false))
        assertFalse(launchRefreshSkippedWhileAway("resume:logind", away = true))
        assertFalse(launchRefreshSkippedWhileAway(STARTUP_REASON, away = true))
        assertFalse(launchRefreshSkippedWhileAway(SOURCE_CYCLE_REASON, away = true))
    }

    /**
     * A week at a workstation: screen on 08:00–18:00 on weekdays, off otherwise. The observation loop
     * (30 min with the screen off, 10 on) and a return catch-up, driven by the minute check.
     */
    @Test
    fun `a workstation week - nothing fetched between 19 00 and 08 00 or at weekends, fresh within a minute of arrival`() {
        val start = LocalDateTime.of(2026, 10, 19, 0, 0).atZone(zone).toInstant().toEpochMilli()
        now = start
        screen = false
        val p = presence()
        val fetches = mutableListOf<LocalDateTime>()
        var nextObs = now
        val returns = mutableListOf<LocalDateTime>()
        while (now < start + 7 * 24 * 60 * minute) {
            val t = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
            val workday = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY
            screen = workday && t.hour in 8 until 18
            p.evaluate()?.let { if (!it.away) { returns += t; fetches += t } }
            if (now >= nextObs) {
                if (!p.isAway) fetches += t else p.countSkippedObservation()
                nextObs = now + (if (screen == true) 10 else 30) * minute
            }
            now += minute
        }
        val night = fetches.filter { it.hour >= 19 || it.hour < 8 }
        // The first night starts away only an hour after the daemon's start counts as presence.
        assertTrue("night fetches: $night", night.all { it.dayOfMonth == 19 && it.hour == 0 })
        assertTrue(fetches.none { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY })
        assertEquals("one return each weekday morning", 5, returns.size)
        assertTrue(returns.toString(), returns.all { it.hour == 8 && it.minute == 0 })
        println("workstation week: ${fetches.size} observation fetches, returns at $returns")
    }
}
