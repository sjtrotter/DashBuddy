package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.data.gas.eia.EiaApi
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GasPriceApiModule {
    @Provides
    @Singleton
    fun provideEiaApi(): EiaApi {
        val json = Json { ignoreUnknownKeys = true }
        val contentType = "application/json".toMediaType()

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