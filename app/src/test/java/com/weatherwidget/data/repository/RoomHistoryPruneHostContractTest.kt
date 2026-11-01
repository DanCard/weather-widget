package com.weatherwidget.data.repository

import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.HistoryPruneRun
import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.ObservationEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyActual
import com.weatherwidget.shared.contract.HistoryPruneHostContract
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestDatabase
import org.junit.After
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room half of [HistoryPruneHostContract]; the desktop half is `DesktopHistoryPruneHostContractTest` (`:desktop`). */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class RoomHistoryPruneHostContractTest : HistoryPruneHostContract() {
    private lateinit var db: WeatherDatabase
    private val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("history_prune_contract", android.content.Context.MODE_PRIVATE)

    override fun open(): HistoryPruneRun.Host {
        prefs.edit().clear().commit()
        db = TestDatabase.create()
        val now = System.currentTimeMillis()
        return RoomHistoryPruneHost(db, prefs, touchedSinceFetchedAt = 0L, startedAtMs = now)
    }

    override suspend fun seed(rows: List<Seed>) = rows.chunked(500).forEach { chunk ->
        db.hourlyForecastHistoryDao().insertAll(
            chunk.map {
                HourlyForecastEntity(
                    dateTime = it.hourMs, locationLat = 37.42, locationLon = -122.08, temperature = 60f,
                    condition = "Clear", source = "OPEN_METEO", timestampToGroupPredictions = it.labelMs,
                    fetchedAt = it.fetchedAtMs,
                )
            },
        )
    }

    override suspend fun rowCount(): Int =
        db.hourlyForecastHistoryDao().getAllInDateTimeRange(Long.MIN_VALUE, Long.MAX_VALUE).size

    override suspend fun lastCompleted(): Long = prefs.getLong(HistoryPruneWorker.KEY_LAST_COMPLETED_START_MS, 0L)

    override suspend fun logged(): List<String> =
        db.appLogDao().getLogsByTag(HistoryPruneSchedule.LOG_TAG, 10).map { it.message }

    override suspend fun archived() =
        db.hourlyActualDao().getInRange(0L, Long.MAX_VALUE, 37.42, -122.08, "NWS").map { it.toHourlyActual() }

    override suspend fun seedObservation(timestampMs: Long, fetchedAtMs: Long) = db.observationDao().insertAll(
        listOf(
            ObservationEntity(
                stationId = "KNUQ", stationName = "KNUQ", timestamp = timestampMs, temperature = 60f, condition = "Clear",
                locationLat = 37.42, locationLon = -122.08, fetchedAt = fetchedAtMs, api = "NWS",
            ),
        ),
    )

    @After
    fun closeDatabase() = db.close()
}
