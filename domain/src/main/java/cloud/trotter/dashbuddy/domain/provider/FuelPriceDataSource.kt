package cloud.trotter.dashbuddy.domain.provider

import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType

/**
 * The standard contract for fetching gas prices.
 * Any future API (GasBuddy, AAA, CollectAPI) must implement this interface.
 */
interface FuelPriceDataSource {
    /**
     * @param userLocation The current [UserLocation] - latitude, longitude, city, state, zip
     * @param fuelType The type of gas their vehicle takes
     * @return The price of a gallon of gas, or an exception.
     */
    suspend fun getFuelPrice(userLocation: UserLocation?, fuelType: FuelType): Result<Float>
}