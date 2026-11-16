package com.weatherwidget.shared.contract

import com.weatherwidget.shared.observations.RadarNowSample
import com.weatherwidget.shared.observations.RadarNowSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [RadarNowSampler.Store] must do on Android (SharedPreferences) and desktop (a file)
 * (plans/261115-header-rain-now-dots.md): the sampler writes from one place (the worker / the daemon)
 * and the header reads from another (the renderer / the UI process), so a sample written through one
 * instance must come back, exactly, through a fresh instance over the same storage.
 */
abstract class RadarNowStoreContract {
    /** A fresh, empty storage; each call to the returned factory opens a new instance over it. */
    protected abstract fun open(): () -> RadarNowSampler.Store

    @Test
    fun `empty storage reads nothing`() {
        assertNull(open()().read())
    }

    @Test
    fun `a sample written by one instance is read back exactly by another`() {
        val instance = open()
        instance().write(GREEN)
        assertEquals(GREEN, instance().read())
    }

    @Test
    fun `a newer sample replaces the older one`() {
        val instance = open()
        instance().write(BLUE)
        instance().write(GREEN)
        assertEquals(GREEN, instance().read())
    }

    companion object {
        /** Full-precision coordinates: the site check ([RadarNowSample.atSite]) must see what was written. */
        val BLUE = RadarNowSample(37.4166014, -122.0888722, 1_794_800_640_000L, 86, 149, 193, 255)
        val GREEN = RadarNowSample(37.4166014, -122.0888722, 1_794_807_360_685L, 34, 214, 63, 255)
    }
}
