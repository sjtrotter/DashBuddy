package cloud.trotter.dashbuddy.core.network.di

import cloud.trotter.dashbuddy.core.network.fuel.price.eia.EiaApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GasPriceApiModule {

    private const val BASE_URL = "https://api.eia.gov/"

    @Provides
    @Singleton
    fun provideEiaApi(): EiaApi {
        // The EIA key travels as the `api_key` query param — redaction is the factory
        // default, so it's stripped from logs without a per-module reminder (#348).
        val client = NetworkClientFactory.okHttpClient(logTag = "EiaApi")
        return NetworkClientFactory.retrofit(BASE_URL, client)
            .create(EiaApi::class.java)
    }
}
