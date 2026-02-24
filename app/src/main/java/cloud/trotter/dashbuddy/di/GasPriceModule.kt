package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.data.gas.GasPriceDataSource
import cloud.trotter.dashbuddy.data.gas.eia.EiaApi
import cloud.trotter.dashbuddy.data.gas.eia.EiaGasPriceDataSource
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GasPriceModule {

    @Binds
    abstract fun bindGasPriceDataSource(
        eiaImpl: EiaGasPriceDataSource
    ): GasPriceDataSource

    // The companion object allows us to mix @Provides and @Binds in the same module
    companion object {

        @Provides
        @Singleton
        fun provideEiaApi(): EiaApi {
            // We set ignoreUnknownKeys = true so the app doesn't crash if the gov
            // adds random new fields to their JSON response in the future!
            val json = Json { ignoreUnknownKeys = true }
            val contentType = "application/json".toMediaType()

            // (Optional) Add a logging interceptor so you can see the raw API requests in Logcat
            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .build()

            return Retrofit.Builder()
                .baseUrl("https://api.eia.gov/")
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(EiaApi::class.java)
        }
    }
}