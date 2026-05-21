package com.weatherwidget.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the navigation arrows are vertically centered within the widget.
 */
@RunWith(AndroidJUnit4::class)
class NavArrowCenteringInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun navArrows_areVerticallyCentered() {
        val views = RemoteViews(context.packageName, R.layout.widget_weather)
        val root = FrameLayout(context)
        
        // Use a standard widget size (e.g., 4x2)
        root.layout(0, 0, 800, 400)
        val applied = views.apply(context, root)

        val navLeft = applied.findViewById<ImageButton>(R.id.nav_left)
        val navRight = applied.findViewById<ImageButton>(R.id.nav_right)

        val leftParams = navLeft.layoutParams as FrameLayout.LayoutParams
        val rightParams = navRight.layoutParams as FrameLayout.LayoutParams

        // Check layout_gravity for vertical centering
        assertTrue(
            "nav_left should have center_vertical gravity",
            (leftParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
        )
        assertTrue(
            "nav_right should have center_vertical gravity",
            (rightParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
        )

        // Check that margins are 0 (no offset from center)
        assertEquals("nav_left should have 0 top margin for centering", 0, leftParams.topMargin)
        assertEquals("nav_right should have 0 top margin for centering", 0, rightParams.topMargin)
    }
}
