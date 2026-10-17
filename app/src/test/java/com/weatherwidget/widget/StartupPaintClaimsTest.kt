package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import com.weatherwidget.widget.StartupPaintClaims.Decision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class StartupPaintClaimsTest {
    @Before
    fun reset() = StartupPaintClaims.clearForTests()

    @After
    fun cleanup() = StartupPaintClaims.clearForTests()

    /** Pixel after an install: PackageReplacedReceiver paints at 2 s, onUpdate arrives at 10 s. */
    @Test
    fun `the second startup trigger inside the window yields to the first`() {
        assertEquals(Decision.Granted, StartupPaintClaims.claim(88, "package_replaced", 2_000))
        assertEquals(Decision.Skip("package_replaced"), StartupPaintClaims.claim(88, "on_update", 10_000))
        // A different widget is a different claim.
        assertEquals(Decision.Granted, StartupPaintClaims.claim(79, "on_update", 10_000))
    }

    /** Samsung: only PackageReplacedReceiver fires; nothing to yield to. */
    @Test
    fun `an unclaimed widget is always granted`() {
        assertEquals(Decision.Granted, StartupPaintClaims.claim(345, "package_replaced", 2_000))
        assertEquals(Decision.Granted, StartupPaintClaims.claim(352, "package_replaced", 2_000))
    }

    /** The periodic host onUpdate, hours later, must paint as it always has. */
    @Test
    fun `a claim expires after the window`() {
        assertEquals(Decision.Granted, StartupPaintClaims.claim(88, "package_replaced", 2_000))
        assertEquals(
            Decision.Granted,
            StartupPaintClaims.claim(88, "on_update", 2_000 + StartupPaintClaims.CLAIM_WINDOW_MS),
        )
        // ...and the fresh claim now holds.
        assertEquals(
            Decision.Skip("on_update"),
            StartupPaintClaims.claim(88, "package_replaced", 3_000 + StartupPaintClaims.CLAIM_WINDOW_MS),
        )
    }

    /**
     * Fold 4, 2026-10-17: onUpdate painted at 06:51:06; the unfold at 06:51:27 re-added the widgets
     * with no views and sent onUpdate again. Refusing it left "Today --°" on the inner screen.
     */
    @Test
    fun `the same trigger again inside the window is a host request and paints`() {
        assertEquals(Decision.Granted, StartupPaintClaims.claim(345, "on_update", 5_000))
        assertEquals(Decision.Repeat(holderAgeMs = 22_000), StartupPaintClaims.claim(345, "on_update", 27_000))
        // The repeat took a fresh claim: its partner arriving now is still the duplicate.
        assertEquals(Decision.Skip("on_update"), StartupPaintClaims.claim(345, "package_replaced", 28_000))
    }

    @Test
    fun `a claim suppresses its partner once, then refuses nothing`() {
        assertEquals(Decision.Granted, StartupPaintClaims.claim(345, "on_update", 1_000))
        assertEquals(Decision.Skip("on_update"), StartupPaintClaims.claim(345, "package_replaced", 4_000))
        // A host rebind after the duplicate was dropped, by either trigger, paints.
        assertEquals(Decision.Granted, StartupPaintClaims.claim(345, "package_replaced", 10_000))
        assertEquals(Decision.Repeat(holderAgeMs = 2_000), StartupPaintClaims.claim(345, "package_replaced", 12_000))
    }
}
