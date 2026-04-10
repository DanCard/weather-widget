package com.weatherwidget.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import java.util.concurrent.Executor

/**
 * Creates an in-memory Room database for integration tests.
 * Uses Robolectric's application context — no emulator needed.
 */
object TestDatabase {
    fun create(): WeatherDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(context, WeatherDatabase::class.java)
            .allowMainThreadQueries()
            .setTransactionExecutor(directExecutor)
            .setQueryExecutor(directExecutor)
            .build()
    }
}
