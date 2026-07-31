package com.weatherwidget.data.repository

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.test.category.ShortDuration
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NwsObservationBackfillerTest {

    private fun subject(source: NwsObservationSource) = NwsObservationBackfiller(
        observationSource = source,
        observationDao = mockk<ObservationDao>(relaxed = true),
        dailyHistoryDao = mockk<DailyHistoryDao>(relaxed = true),
        appLogDao = mockk<AppLogDao>(relaxed = true),
        dailyActualsStore = mockk<DailyActualsStore>(relaxed = true),
    )

    @Test(expected = CancellationException::class)
    fun `daily backfill station discovery cancellation propagates`() = runTest {
        val source = mockk<NwsObservationSource>()
        coEvery { source.stationsForLocation(any(), any()) } throws CancellationException("stop")

        subject(source).backfillNwsObservationsIfNeeded(37.42, -122.08)
    }

    @Test(expected = CancellationException::class)
    fun `recent backfill station discovery cancellation propagates`() = runTest {
        val source = mockk<NwsObservationSource>()
        coEvery { source.stationsForLocation(any(), any()) } throws CancellationException("stop")

        subject(source).backfillRecentNwsObservations(37.42, -122.08, 24)
    }
}
