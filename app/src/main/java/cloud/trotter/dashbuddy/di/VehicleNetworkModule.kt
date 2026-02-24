package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.data.vehicle.api.FuelEconomyApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import timber.log.Timber
import javax.inject.Singleton

/**
 * Hilt module responsible for providing network dependencies specifically for
 * the fueleconomy.gov REST API.
 */
@Module
@InstallIn(SingletonComponent::class)
object VehicleNetworkModule {

    private const val BASE_URL = "https://www.fueleconomy.gov/ws/rest/vehicle/"

    @Provides
    @Singleton
    fun provideFuelEconomyApi(): FuelEconomyApi {
        // Custom OkHttp logger that pipes directly into Timber at the VERBOSE level.
        // This prevents OkHttp from cluttering the standard INFO logcat.
        val customTimberLogger = HttpLoggingInterceptor.Logger { message ->
            Timber.tag("FuelEconomyAPI").v(message)
        }

        val loggingInterceptor = HttpLoggingInterceptor(customTimberLogger).apply {
            // Logs headers and body. If this becomes too noisy, drop it to BASIC
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .build()

        // Configure JSON parsing to be highly forgiving since we don't control the government's API schemas
        val networkJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(networkJson.asConverterFactory(contentType))
            .build()
            .create(FuelEconomyApi::class.java)
    }
}