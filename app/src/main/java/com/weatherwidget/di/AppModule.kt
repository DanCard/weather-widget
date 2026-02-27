package com.weatherwidget.di

import android.content.Context
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.CurrentTempRepository
import com.weatherwidget.data.repository.ForecastRepository
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
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
    fun provideForecastDao(database: WeatherDatabase): ForecastDao = database.forecastDao()

    @Provides
    @Singleton
    fun provideHourlyForecastDao(database: WeatherDatabase): HourlyForecastDao = database.hourlyForecastDao()

    @Provides
    @Singleton
    fun provideAppLogDao(database: WeatherDatabase): AppLogDao = database.appLogDao()

    @Provides
    @Singleton
    fun provideClimateNormalDao(database: WeatherDatabase): ClimateNormalDao = database.climateNormalDao()

    @Provides
    @Singleton
    fun provideObservationDao(database: WeatherDatabase): ObservationDao = database.observationDao()

    @Provides
    @Singleton
    fun provideCurrentTempDao(database: WeatherDatabase): CurrentTempDao = database.currentTempDao()

    @Provides
    @Singleton
    fun provideForecastRepository(
        @ApplicationContext context: Context,
        forecastDao: ForecastDao,
        hourlyForecastDao: HourlyForecastDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        openMeteoApi: OpenMeteoApi,
        weatherApi: WeatherApi,
        widgetStateManager: WidgetStateManager,
        climateNormalDao: ClimateNormalDao,
    ): ForecastRepository = ForecastRepository(
        context, forecastDao, hourlyForecastDao, appLogDao,
        nwsApi, openMeteoApi, weatherApi, widgetStateManager, climateNormalDao
    )

    @Provides
    @Singleton
    fun provideCurrentTempRepository(
        @ApplicationContext context: Context,
        currentTempDao: CurrentTempDao,
        observationDao: ObservationDao,
        hourlyForecastDao: HourlyForecastDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        openMeteoApi: OpenMeteoApi,
        weatherApi: WeatherApi,
        widgetStateManager: WidgetStateManager,
    ): CurrentTempRepository = CurrentTempRepository(
        context, currentTempDao, observationDao, hourlyForecastDao, appLogDao,
        nwsApi, openMeteoApi, weatherApi, widgetStateManager, com.weatherwidget.util.TemperatureInterpolator()
    )

    @Provides
    @Singleton
    fun provideWidgetStateManager(
        @ApplicationContext context: Context,
        appLogDao: AppLogDao,
    ): WidgetStateManager = WidgetStateManager(context, appLogDao)

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

    @Provides
    @Singleton
    fun provideWeatherApi(
        httpClient: HttpClient,
        json: Json,
    ): WeatherApi = WeatherApi(httpClient, json)
}
