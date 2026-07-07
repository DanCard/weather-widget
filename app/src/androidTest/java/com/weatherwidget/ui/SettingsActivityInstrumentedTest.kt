package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun locationSettingsSectionIsVisibleOnAllDevices() {
        // The section hosts the "Set Location…" entry to the shared setup screen; since location
        // pinning (LocationMode.FIXED) made manual choices safe on GPS devices, it must be
        // visible everywhere — emulators and physical phones alike.
        val intent = Intent(context, SettingsActivity::class.java)
        val scenario = ActivityScenario.launch<SettingsActivity>(intent)

        scenario.onActivity { activity ->
            val locationSection = activity.findViewById<View>(R.id.location_settings_section)
            assertEquals(
                "Location settings section should be visible",
                View.VISIBLE,
                locationSection.visibility
            )
        }
    }
}
