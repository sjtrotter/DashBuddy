package cloud.trotter.dashbuddy.core.network.di

import cloud.trotter.dashbuddy.core.network.BuildConfig
import cloud.trotter.dashbuddy.core.network.fuel.price.eia.EiaApi
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
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GasPriceApiModule {

    private const val BASE_URL = "https://api.eia.gov/"
    @Provides
    @Singleton
    fun provideEiaApi(): EiaApi {
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

        val client = OkHttpClient.Builder()
            .apply {
                // Debug-only, Timber-piped, and the EIA key travels as a query param —
                // redact it so the secret never reaches logcat or the persisted file log (#348).
                if (BuildConfig.DEBUG) {
                    val timberLogger = HttpLoggingInterceptor.Logger { message ->
                        Timber.tag("EiaApi").v(message)
                    }
                    addInterceptor(
                        HttpLoggingInterceptor(timberLogger).apply {
                            level = HttpLoggingInterceptor.Level.BODY
                            redactQueryParams("api_key")
                        }
                    )
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(EiaApi::class.java)
    }
}