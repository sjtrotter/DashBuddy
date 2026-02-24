package cloud.trotter.dashbuddy.data.gas

import cloud.trotter.dashbuddy.model.vehicle.FuelType

/**
 * The standard contract for fetching gas prices.
 * Any future API (GasBuddy, AAA, CollectAPI) must implement this interface.
 */
interface GasPriceDataSource {
    /**
     * @param lat The user's latitude (used by hyper-local APIs in the future)
     * @param lon The user's longitude (used by hyper-local APIs in the future)
     * @param fuelType The type of gas their vehicle takes
     * @return The price of a gallon of gas, or an exception.
     */
    suspend fun getGasPrice(lat: Double?, lon: Double?, fuelType: FuelType): Result<Float>
}