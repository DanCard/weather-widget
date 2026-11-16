package com.weatherwidget.desktop

import com.weatherwidget.shared.contract.RadarNowStoreContract
import com.weatherwidget.shared.observations.RadarNowSampler
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category
import java.io.File
import java.nio.file.Files

/** Desktop half of [RadarNowStoreContract] (the daemon's file, read by the UI process); the Android half is `AndroidRadarNowStoreContractTest`. */
@Category(ShortDuration::class)
class DesktopRadarNowStoreContractTest : RadarNowStoreContract() {
    override fun open(): () -> RadarNowSampler.Store {
        val file = File(Files.createTempDirectory("radar_now_contract").toFile(), "radar-now.txt")
        return { FileRadarNowStore(file) }
    }
}
