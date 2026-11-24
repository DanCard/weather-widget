package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.shared.contract.TargetedForecastSyncHostContract
import com.weatherwidget.shared.util.TargetedForecastSyncRun
import com.weatherwidget.test.category.ShortDuration
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.experimental.categories.Category
import java.nio.file.Files
import java.nio.file.Path

/** [DesktopPostSyncTail] half of [TargetedForecastSyncHostContract]; the WorkManager half is `AndroidTargetedSyncHostContractTest` (`:app`). */
@Category(ShortDuration::class)
class DesktopTargetedSyncHostContractTest : TargetedForecastSyncHostContract() {
    private lateinit var path: Path
    private lateinit var scope: CoroutineScope
    private lateinit var tail: DesktopPostSyncTail

    override fun open(): TargetedForecastSyncRun.Host {
        path = Files.createTempFile("weather_targeted_contract", ".db")
        val dao = DesktopWeatherDao(DesktopWeatherDatabase(path).apply { initialize() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tail = DesktopPostSyncTail(scope) { error("the contract never lets a tail come due") }
        val repo = DesktopWeatherRepository(mockk<WeatherApiClient>(relaxed = true), dao, 37.422, -122.084, "NWS")
        return DesktopTargetedSyncHost(repo, dao, tail, visibleSources = { setOf("NWS") }, userSyncActive = { false })
    }

    override fun armedTails(): Int = if (tail.state() == TargetedForecastSyncRun.TailState.NONE) 0 else 1

    override fun close() {
        scope.cancel()
        Files.deleteIfExists(path)
        Files.deleteIfExists(DesktopMetarReconcileBootStore.markerFor(path))
    }
}
