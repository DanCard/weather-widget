package com.weatherwidget.di

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.widget.WidgetStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient =
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }

    @Provides
    @Singleton
    fun provideWeatherDatabase(
        @ApplicationContext context: Context,
    ): WeatherDatabase = WeatherDatabase.getDatabase(context)

    @Provides
    @Singleton
    fun provideWeatherDao(database: WeatherDatabase): WeatherDao = database.weatherDao()

    @Provides
    @Singleton
    fun provideForecastSnapshotDao(database: WeatherDatabase): ForecastSnapshotDao = database.forecastSnapshotDao()

    @Provides
    @Singleton
    fun provideHourlyForecastDao(database: WeatherDatabase): HourlyForecastDao = database.hourlyForecastDao()

    @Provides
    @Singleton
    fun provideAppLogDao(database: WeatherDatabase): AppLogDao = database.appLogDao()

    @Provides
    @Singleton
    fun provideWidgetStateManager(
        @ApplicationContext context: Context,
    ): WidgetStateManager = WidgetStateManager(context)

    @Provides
    @Singleton
    fun provideNwsApi(
        httpClient: HttpClient,
        json: Json,
    ): NwsApi = NwsApi(httpClient, json)

    @Provides
    @Singleton
    fun provideOpenMeteoApi(
        httpClient: HttpClient,
        json: Json,
    ): OpenMeteoApi = OpenMeteoApi(httpClient, json)
}
