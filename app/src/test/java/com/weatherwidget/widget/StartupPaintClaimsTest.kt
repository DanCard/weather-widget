package com.weatherwidget.widget

import com.weatherwidget.test.category.ShortDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(StartupPaintClaims.claim(88, "package_replaced", 2_000))
        assertEquals("package_replaced", StartupPaintClaims.claim(88, "on_update", 10_000))
        // A different widget is a different claim.
        assertNull(StartupPaintClaims.claim(79, "on_update", 10_000))
    }

    /** Samsung: only PackageReplacedReceiver fires; nothing to yield to. */
    @Test
    fun `an unclaimed widget is always granted`() {
        assertNull(StartupPaintClaims.claim(345, "package_replaced", 2_000))
        assertNull(StartupPaintClaims.claim(352, "package_replaced", 2_000))
    }

    /** The periodic host onUpdate, hours later, must paint as it always has. */
    @Test
    fun `a claim expires after the window`() {
        assertNull(StartupPaintClaims.claim(88, "package_replaced", 2_000))
        assertNull(StartupPaintClaims.claim(88, "on_update", 2_000 + StartupPaintClaims.CLAIM_WINDOW_MS))
        // ...and the fresh claim now holds.
        assertEquals("on_update", StartupPaintClaims.claim(88, "package_replaced", 3_000 + StartupPaintClaims.CLAIM_WINDOW_MS))
    }
}
