package com.weatherwidget.desktop

import com.weatherwidget.shared.rainmap.RainLoopSpeed
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

@Category(ShortDuration::class)
class RainMapSpeedConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = DesktopConfigStore(tmp.root.toPath().resolve("config.json"), missingUnitDefault = { false })

    @Test
    fun `rain map speed defaults to the shared default and survives a save and load`() {
        val base = DesktopConfig(lat = 37.42, lon = -122.09, label = "Mountain View")
        assertEquals(RainLoopSpeed.DEFAULT_SECONDS, base.rainMapSecondsPerHour)

        store().save(base.copy(rainMapSecondsPerHour = 0.3f))
        assertEquals(0.3f, store().load()!!.rainMapSecondsPerHour)
    }

    @Test
    fun `a config written before the field existed opens at the default`() {
        val file = tmp.root.toPath().resolve("config.json").toFile()
        file.writeText("""{"lat":37.42,"lon":-122.09,"label":"Mountain View","settings":{"useCelsius":false}}""")
        assertEquals(RainLoopSpeed.DEFAULT_SECONDS, store().load()!!.rainMapSecondsPerHour)
    }
}
