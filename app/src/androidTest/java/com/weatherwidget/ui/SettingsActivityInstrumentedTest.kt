package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.R
import com.weatherwidget.util.DeviceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun emulatorShowsLocationSettings() {
        // This test ensures the location settings block is shown on emulators, 
        // as emulators do not have a standard "real" GPS provider in the way
        // that physical devices do, and we require manual coordinate entry to test location logic.
        org.junit.Assume.assumeTrue("Test must be run on an emulator", DeviceUtils.isEmulator())

        val intent = Intent(context, SettingsActivity::class.java)
        val scenario = ActivityScenario.launch<SettingsActivity>(intent)

        scenario.onActivity { activity ->
            val locationSection = activity.findViewById<View>(R.id.location_settings_section)
            assertEquals(
                "Location settings section should be visible on emulator", 
                View.VISIBLE, 
                locationSection.visibility
            )
        }
    }
}
