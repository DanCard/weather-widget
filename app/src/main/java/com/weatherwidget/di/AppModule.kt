package com.weatherwidget.di

import android.content.Context
import com.weatherwidget.data.local.ApiUsageDao
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.DailyExtremeDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.HourlyForecastHistoryDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.repository.CurrentTempRepository
import com.weatherwidget.data.repository.ForecastRepository
import com.weatherwidget.data.repository.NwsForecastMapper
import com.weatherwidget.data.repository.ObservationRepository
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.OpenWeatherMapApi
import com.weatherwidget.data.remote.VisualCrossingApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.data.remote.SilurianApi
import com.weatherwidget.data.remote.TomorrowIoApi
import com.weatherwidget.data.remote.NominatimApi
import com.weatherwidget.data.remote.IpGeolocationApi
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.shared.util.TemperatureInterpolator
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.CurrentTemperatureResolver
import com.weatherwidget.widget.WidgetConstants
import com.weatherwidget.widget.WidgetStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.weatherwidget.network.FallbackDns
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Singleton
import dagger.hilt.EntryPoint
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryEntryPoint {
    fun forecastRepository(): ForecastRepository
    fun currentTempRepository(): CurrentTempRepository
    fun observationRepository(): ObservationRepository
}

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
    fun provideHttpClient(
        json: Json,
        apiUsageDao: ApiUsageDao,
        @ApplicationContext context: Context,
    ): HttpClient {
        val fallbackDns = FallbackDns(
            context.getSharedPreferences(FallbackDns.PREFS_NAME, Context.MODE_PRIVATE)
        )
        return HttpClient(OkHttp) {
            engine {
                config {
                    dns(fallbackDns)
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            install(HttpRequestRetry) {
                maxRetries = 2
                retryOnExceptionIf { _, cause ->
                    cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
                    cause is io.ktor.client.network.sockets.SocketTimeoutException ||
                    cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
                    cause is java.io.IOException
                }
                exponentialDelay(base = 2.0, maxDelayMs = 2_000)
            }
        }.apply {
            plugin(HttpSend).intercept { request ->
                val host = request.url.host
                val source = when {
                    host.contains("silurian.ai") -> "SILURIAN"
                    host.contains("tomorrow.io") -> "TOMORROW_IO"
                    host.contains("weather.gov") -> "NWS"
                    host.contains("visualcrossing.com") -> "VISUAL_CROSSING"
                    host.contains("open-meteo.com") -> "OPEN_METEO"
                    host.contains("openweathermap.org") -> "OPEN_WEATHER_MAP"
                    host.contains("weatherapi.com") -> "WEATHER_API"
                    else -> "UNKNOWN"
                }
                if (source != "UNKNOWN") {
                    val date = LocalDate.now().toEpochDay() * WidgetConstants.MS_IN_A_DAY
                    apiUsageDao.logCall(date, source)
                }
                execute(request)
            }
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
    fun provideHourlyForecastHistoryDao(database: WeatherDatabase): HourlyForecastHistoryDao = database.hourlyForecastHistoryDao()

    @Provides
    @Singleton
    fun provideAppLogDao(database: WeatherDatabase): AppLogDao =
        database.appLogDao().also { dao ->
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            CurrentTemperatureResolver.dbLogger = { tag, message, level ->
                // Persistence boundary: VERBOSE = high-frequency render/poll trace, ephemeral sinks
                // (logcat) only — never persisted, so the queryable DB log stays sparse. DEBUG+ persist.
                if (level != "VERBOSE") {
                    scope.launch {
                        dao.log(tag, message, level)
                    }
                }
            }
        }

    @Provides
    @Singleton
    fun provideClimateNormalDao(database: WeatherDatabase): ClimateNormalDao = database.climateNormalDao()

    @Provides
    @Singleton
    fun provideObservationDao(database: WeatherDatabase): ObservationDao = database.observationDao()

    @Provides
    @Singleton
    fun provideApiUsageDao(database: WeatherDatabase): ApiUsageDao = database.apiUsageDao()

    @Provides
    @Singleton
    fun provideDailyExtremeDao(database: WeatherDatabase): DailyExtremeDao = database.dailyExtremeDao()

    @Provides
    @Singleton
    fun provideObservationRepository(
        @ApplicationContext context: Context,
        observationDao: ObservationDao,
        dailyExtremeDao: DailyExtremeDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        hourlyForecastDao: HourlyForecastDao,
    ): ObservationRepository = ObservationRepository(
        context, observationDao, dailyExtremeDao, appLogDao, nwsApi, hourlyForecastDao
    )

    @Provides
    @Singleton
    fun provideForecastRepository(
        @ApplicationContext context: Context,
        forecastDao: ForecastDao,
        hourlyForecastDao: HourlyForecastDao,
        hourlyForecastHistoryDao: HourlyForecastHistoryDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        openMeteoApi: OpenMeteoApi,
        visualCrossingApi: VisualCrossingApi,
        weatherApi: WeatherApi,
        silurianApi: SilurianApi,
        widgetStateManager: WidgetStateManager,
        climateNormalDao: ClimateNormalDao,
        observationDao: ObservationDao,
        dailyExtremeDao: DailyExtremeDao,
        observationRepository: ObservationRepository,
        tomorrowIoApi: TomorrowIoApi,
        openWeatherMapApi: OpenWeatherMapApi,
        nwsForecastMapper: NwsForecastMapper,
    ): ForecastRepository = ForecastRepository(
        context, forecastDao, hourlyForecastDao, hourlyForecastHistoryDao, appLogDao,
        nwsApi, openMeteoApi, visualCrossingApi, weatherApi, silurianApi, widgetStateManager, climateNormalDao, observationDao, dailyExtremeDao, observationRepository, tomorrowIoApi, openWeatherMapApi, nwsForecastMapper
    )

    @Provides
    @Singleton
    fun provideCurrentTempRepository(
        @ApplicationContext context: Context,
        observationDao: ObservationDao,
        hourlyForecastDao: HourlyForecastDao,
        appLogDao: AppLogDao,
        nwsApi: NwsApi,
        openMeteoApi: OpenMeteoApi,
        visualCrossingApi: VisualCrossingApi,
        weatherApi: WeatherApi,
        silurianApi: SilurianApi,
        widgetStateManager: WidgetStateManager,
        dailyExtremeDao: DailyExtremeDao,
        observationRepository: ObservationRepository,
        tomorrowIoApi: TomorrowIoApi,
        openWeatherMapApi: OpenWeatherMapApi,
    ): CurrentTempRepository = CurrentTempRepository(
        context, observationDao, hourlyForecastDao, appLogDao,
        nwsApi, openMeteoApi, visualCrossingApi, weatherApi, silurianApi, widgetStateManager, dailyExtremeDao, observationRepository, tomorrowIoApi, openWeatherMapApi
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
    fun provideVisualCrossingApi(
        httpClient: HttpClient,
        json: Json,
        widgetStateManager: WidgetStateManager,
    ): VisualCrossingApi = VisualCrossingApi(httpClient, json) { 
        widgetStateManager.getApiKey(WeatherSource.VISUAL_CROSSING) ?: com.weatherwidget.BuildConfig.VISUAL_CROSSING_API_KEY 
    }

    @Provides
    @Singleton
    fun provideOpenWeatherMapApi(
        httpClient: HttpClient,
        json: Json,
        widgetStateManager: WidgetStateManager,
    ): OpenWeatherMapApi = OpenWeatherMapApi(httpClient, json) { 
        widgetStateManager.getApiKey(WeatherSource.OPEN_WEATHER_MAP) ?: com.weatherwidget.BuildConfig.OPEN_WEATHER_MAP_API_KEY 
    }

    @Provides
    @Singleton
    fun provideWeatherApi(
        httpClient: HttpClient,
        json: Json,
        widgetStateManager: WidgetStateManager,
    ): WeatherApi = WeatherApi(httpClient, json) { 
        widgetStateManager.getApiKey(WeatherSource.WEATHER_API) ?: com.weatherwidget.BuildConfig.WEATHER_API_KEY 
    }

    @Provides
    @Singleton
    fun provideSilurianApi(
        httpClient: HttpClient,
        json: Json,
        widgetStateManager: WidgetStateManager,
    ): SilurianApi = SilurianApi(httpClient, json) { 
        widgetStateManager.getApiKey(WeatherSource.SILURIAN) ?: com.weatherwidget.BuildConfig.SILURIAN_API_KEY 
    }

    @Provides
    @Singleton
    fun provideTomorrowIoApi(
        httpClient: HttpClient,
        json: Json,
        widgetStateManager: WidgetStateManager,
    ): TomorrowIoApi = TomorrowIoApi(httpClient, json) { 
        widgetStateManager.getApiKey(WeatherSource.TOMORROW_IO) ?: com.weatherwidget.BuildConfig.TOMORROW_IO_API_KEY 
    }

    @Provides
    @Singleton
    fun provideNominatimApi(
        httpClient: HttpClient,
        json: Json,
    ): NominatimApi = NominatimApi(httpClient, json)

    @Provides
    @Singleton
    fun provideIpGeolocationApi(
        httpClient: HttpClient,
        json: Json,
    ): IpGeolocationApi = IpGeolocationApi(httpClient, json)

    @Provides
    @Singleton
    fun provideSharedLocationResolver(
        nominatimApi: NominatimApi,
        ipGeolocationApi: IpGeolocationApi,
    ): SharedLocationResolver = SharedLocationResolver(nominatimApi, ipGeolocationApi)
}
