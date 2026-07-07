package com.weatherwidget.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class LocationModeTest : RobolectricTest() {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `absent key defaults to follow_device`() {
        assertEquals(LocationMode.FOLLOW_DEVICE, LocationMode.get(context))
    }

    @Test
    fun `set and get round-trip`() {
        LocationMode.set(context, LocationMode.FIXED)
        assertEquals(LocationMode.FIXED, LocationMode.get(context))

        LocationMode.set(context, LocationMode.FOLLOW_DEVICE)
        assertEquals(LocationMode.FOLLOW_DEVICE, LocationMode.get(context))
    }
}
