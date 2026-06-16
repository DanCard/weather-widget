package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.flexbox.FlexboxLayout
import com.weatherwidget.R
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class IconGalleryActivityRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `activity launches and populates icons in flexbox layout`() {
        val intent = Intent(context, IconGalleryActivity::class.java)
        ActivityScenario.launch<IconGalleryActivity>(intent).onActivity { activity ->
            shadowOf(context.mainLooper).idle()

            // Verify title
            val titleText = activity.findViewById<TextView>(R.id.title_text).text.toString()
            assertEquals("Icon gallery", titleText)

            // Verify flexbox container is populated with icons
            val flexbox = activity.findViewById<FlexboxLayout>(R.id.icon_gallery_container)
            assertNotNull("Flexbox layout container should not be null", flexbox)
            assertTrue("Flexbox layout should contain child icon views", flexbox.childCount > 0)
        }
    }
}
