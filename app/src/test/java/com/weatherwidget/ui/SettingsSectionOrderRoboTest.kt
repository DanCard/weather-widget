package com.weatherwidget.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.shared.settings.Platform
import com.weatherwidget.shared.settings.SettingsSection
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android Settings screen renders exactly the shared catalogue's sections, in its order, under
 * its English titles. Section title views carry `android:tag="settings_section"` in
 * `activity_settings.xml`; this walks them top-to-bottom. The desktop's
 * `SettingsWindowSectionsTest` asserts the same against [SettingsSection.forPlatform] for its side,
 * so the two screens can only drift by editing the enum — which is the decision point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@Category(LongDuration::class)
class SettingsSectionOrderRoboTest {

    @Test
    fun `section headers match the shared catalogue in order`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val headers = mutableListOf<String>()
            collectSectionHeaders(root, headers)
            assertEquals(
                SettingsSection.forPlatform(Platform.ANDROID).map { it.title },
                headers,
            )
        }
    }

    /** Depth-first in child order, which is document order for the layout's single column. */
    private fun collectSectionHeaders(view: View, out: MutableList<String>) {
        if (view is TextView && view.tag == "settings_section") out += view.text.toString()
        if (view is ViewGroup) for (i in 0 until view.childCount) collectSectionHeaders(view.getChildAt(i), out)
    }
}
