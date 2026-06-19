package cloud.trotter.dashbuddy.core.network.di

import cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.EpaApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    fun provideFuelEconomyApi(): EpaApi {
        // Same hardened client + lenient gov-API Json as the gas-price module, via the
        // shared factory — including the #348 secret-redaction + timeouts it previously
        // lacked.
        val client = NetworkClientFactory.okHttpClient(logTag = "FuelEconomyAPI")
        return NetworkClientFactory.retrofit(BASE_URL, client)
            .create(EpaApi::class.java)
    }
}
